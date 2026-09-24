package com.catanddev.rtsp.server

import android.util.Log
import com.catanddev.rtsp.BuildConfig
import com.catanddev.rtsp.parser.RtpHeaderParser
import com.catanddev.rtsp.parser.RtpH264Parser
import com.catanddev.rtsp.parser.RtpH265Parser
import com.catanddev.rtsp.parser.RtpParser
import com.catanddev.rtsp.utils.VideoCodecUtils
import com.catanddev.rtsp.RtspClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.experimental.and
import kotlin.experimental.or

class RtpServer {

    private var rtspProcessor: com.catanddev.rtsp.widget.RtspProcessor? = null

    interface RtpServerListener {
        fun onRtpServerStarting()
        fun onRtpServerStarted()
        fun onRtpVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long)

        /**
         * Расширенная версия с RTP sequence number и marker.
         * Нужна для достоверной статистики (потери/джиттер) и определения конца кадра.
         * По умолчанию делегирует в базовый метод — существующие реализации не ломаются.
         */
        fun onRtpVideoNalUnitReceived(
            data: ByteArray,
            offset: Int,
            length: Int,
            timestamp: Long,
            seq: Int,
            marker: Boolean
        ) {
            onRtpVideoNalUnitReceived(data, offset, length, timestamp)
        }

        /**
         * Учёт каждого видеопакета RTP (до сборки NAL-юнитов). Нужен для корректной
         * сетевой статистики (потери/джиттер/входной битрейт). По умолчанию ничего не делает.
         */
        fun onRtpPacketReceived(seq: Int, timestampMs: Long, payloadSize: Int, marker: Boolean) {}

        fun onRtpAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long)
        fun onRtpServerStopping()
        fun onRtpServerStopped()
        fun onRtpServerFailed(message: String?)
    }

    private val host: String
    private val port: Int
    private val exitFlag: AtomicBoolean
    private val listener: RtpServerListener
    private val debug: Boolean

    private val packetSize: Int

    // Video stream parameters
    private val videoCodec: Int
    private var videoPayloadType: Int = 96

    // Парсеры
    private lateinit var videoParser: RtpParser

    // Переиспользуемый буфер пакета (обработка однопоточная — можно не аллоцировать на каждый
    // пакет). Растёт лениво до нужного размера. Payload парсится прямо из него по offset.
    private var packetBuffer = ByteArray(0)

    // Статистика
    private var packetsReceived = 0L
    private var nalUnitsReceived = 0L

    // Для сборки фрагментированных NAL юнитов H.265 - не используется, так как используется RtpH265Parser
    @Suppress("unused")
    private var fuBuffer: ByteArrayOutputStream? = null
    @Suppress("unused")
    private var fuNalType: Int = -1
    @Suppress("unused")
    private var fuTimestamp: Long = 0
    @Suppress("unused")
    private var fuSequenceStart: Int = -1

    constructor(
        host: String = "0.0.0.0",
        port: Int = 50003,
        videoCodec: Int = RtspClient.VIDEO_CODEC_H264,
        videoPayloadType: Int = 96,
        exitFlag: AtomicBoolean = AtomicBoolean(false),
        listener: RtpServerListener,
        debug: Boolean = false,
        packetSize: Int = 65507,
        rtspProcessor: com.catanddev.rtsp.widget.RtspProcessor? = null
    ) {
        this.host = host
        this.port = port
        this.videoCodec = videoCodec
        this.videoPayloadType = videoPayloadType
        this.exitFlag = exitFlag
        this.listener = listener
        this.debug = debug
        this.packetSize = packetSize
        this.rtspProcessor = rtspProcessor

        // Инициализируем парсер в зависимости от кодека
        videoParser = when (videoCodec) {
            RtspClient.VIDEO_CODEC_H265 -> RtpH265Parser()
            else -> RtpH264Parser()
        }
    }

    fun start() {
        Thread {
            runServer()
        }.apply {
            name = "RTP Server Thread"
            start()
        }
    }

    fun stop() {
        exitFlag.set(true)
    }

    private fun runServer() {
        if (DEBUG) Log.v(TAG, "runServer() - Starting RTP server on $host:$port")

        listener.onRtpServerStarting()

        var socket: DatagramSocket? = null

        try {
            val address = InetAddress.getByName(host)
            socket = DatagramSocket(port, address)
            socket.soTimeout = 1000 // 1 second timeout для graceful exit
            // Увеличиваем буфер приёма ядра, иначе при пиках битрейта пакеты дропаются молча
            try {
                socket.receiveBufferSize = 4 * 1024 * 1024
            } catch (e: Exception) {
                Log.w(TAG, "Cannot set receive buffer size: ${e.message}")
            }

            if (debug) {
                Log.i(TAG_DEBUG, "RTP Server created socket successfully")
                Log.i(TAG_DEBUG, "RTP Server listening on $host:$port, codec: $videoCodec")
            }

            listener.onRtpServerStarted()

            val buffer = ByteArray(packetSize) // Configurable UDP packet size
            val packet = DatagramPacket(buffer, buffer.size)

            while (!exitFlag.get()) {
                try {
                    socket.receive(packet)
                    packetsReceived++

                    // Копируем в переиспользуемый буфер (учитывает packet.offset) вместо copyOfRange,
                    // чтобы не создавать новый массив на каждый пакет.
                    val dataLength = packet.length
                    if (packetBuffer.size < dataLength) packetBuffer = ByteArray(dataLength)
                    System.arraycopy(packet.data, packet.offset, packetBuffer, 0, dataLength)

                    // Парсим RTP заголовок
                    val header = RtpHeaderParser.RtpHeader.parseData(packetBuffer, dataLength)
                    if (header == null) {
                        if (debug) Log.w(TAG_DEBUG, "Invalid RTP packet received")
                        continue
                    }

                    if (debug && packetsReceived % 100 == 0L) {
                        header.dumpHeader()
                    }

                    // Проверяем payload type для видео
                    if (header.payloadType == videoPayloadType) {
                        processVideoPacket(packetBuffer, dataLength, header, videoParser)
                    }

                } catch (e: java.net.SocketTimeoutException) {
                    // Timeout для проверки флага выхода
                    continue
                } catch (e: IOException) {
                    if (!exitFlag.get()) {
                        Log.e(TAG, "Error receiving packet", e)
                        listener.onRtpServerFailed(e.message)
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing packet", e)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Exception in runServer: ${e.message}")
            listener.onRtpServerFailed(e.message)
        } finally {
            socket?.close()
            listener.onRtpServerStopping()
            listener.onRtpServerStopped()

            if (debug) {
                Log.i(TAG_DEBUG, "RTP Server stopped. Packets: $packetsReceived, NALs: $nalUnitsReceived")
            }
        }
    }

    private fun processVideoPacket(
        data: ByteArray,
        dataLength: Int,
        header: RtpHeaderParser.RtpHeader,
        parser: RtpParser
    ) {
        val headerSize = 12 // Базовый размер RTP заголовка
        var payloadStart = headerSize

        // Обработка extension header
        if (header.extension == 1) {
            val extensionLengthInWords =
                ((data[payloadStart + 2].toInt() and 0xFF) shl 8) or
                        (data[payloadStart + 3].toInt() and 0xFF)
            val extensionSize = 4 + extensionLengthInWords * 4 // 4 байта заголовка + данные
            payloadStart += extensionSize
        }

        // CSRC (contributing sources) - если есть
        if (header.cc > 0) {
            payloadStart += header.cc * 4
        }

        // Размер payload - это общий размер пакета минус смещение до payload
        val payloadSize = dataLength - payloadStart

        if (payloadSize <= 0) {
            Log.w(TAG_DEBUG, "Empty payload in RTP packet")
            return
        }

        // Сетевые метрики — на каждый RTP-пакет (до сборки NAL).
        listener.onRtpPacketReceived(
            header.sequenceNumber,
            header.timestampMs,
            payloadSize,
            header.marker == 1
        )

        if (debug) {
            Log.d(TAG_DEBUG, "RTP Packet: headerSize=$payloadStart, payloadSize=$payloadSize, " +
                    "marker=${header.marker}, seq=${header.sequenceNumber}")

            if (payloadSize > 0) {
                val firstByte = data[payloadStart]
                val nalType: Int = (firstByte.toInt() shr 1) and 0x3F
                val preview = (0 until minOf(4, payloadSize)).joinToString(" ") {
                    "%02x".format(data[payloadStart + it])
                }
                Log.d(TAG_DEBUG, "NAL type: $nalType, first bytes: $preview")
            }
        }

        // Используем RtpParser для обработки всех кодеков (H.264 и H.265).
        // Payload передаём по offset прямо из буфера пакета — без копирования.
        val nalUnit = parser.processRtpPacketAndGetNalUnit(
            data,
            payloadStart,
            payloadSize,
            header.marker == 1
        )

        if (nalUnit != null) {
            nalUnitsReceived++
            sendToListener(
                nalUnit, 0, nalUnit.size,
                header.timestampMs, header.sequenceNumber, header.marker == 1
            )
        }
    }

    // processH265FuPacket и addStartCode удалены, так как функциональность теперь обрабатывается через RtpParser
    // Вся обработка H.265 FU-A пакетов делегирована RtpH265Parser, который правильно сохраняет temporal_id (tid)
    // и корректно собирает фрагментированные NAL-юниты

    private fun sendToListener(
        data: ByteArray,
        offset: Int,
        length: Int,
        timestamp: Long,
        seq: Int,
        marker: Boolean
    ) {
        listener.onRtpVideoNalUnitReceived(data, offset, length, timestamp, seq, marker)
    }

    companion object {
        private val TAG: String = RtpServer::class.java.simpleName
        private val TAG_DEBUG: String = "$TAG DBG"
        private val DEBUG = BuildConfig.DEBUG
    }
}