package com.catanddev.rtsp.parser

import android.util.Log
import com.catanddev.rtsp.utils.NetUtils
import java.io.IOException
import java.io.InputStream

object RtpHeaderParser {
    private val TAG: String = RtpHeaderParser::class.java.simpleName
    private const val DEBUG = false

    private const val RTP_HEADER_SIZE = 12

    @Throws(IOException::class)
    fun readHeader(inputStream: InputStream): RtpHeader? {
        // 24 01 00 1c 80 c8 00 06  7f 1d d2 c4
        // 24 01 00 1c 80 c8 00 06  13 9b cf 60
        // 24 02 01 12 80 e1 01 d2  00 07 43 f0
        val header = ByteArray(RTP_HEADER_SIZE)
        // Skip 4 bytes (TCP only). No those bytes in UDP.
        NetUtils.readData(inputStream, header, 0, 4)
        if (DEBUG && header[0].toInt() == 0x24) Log.d(
            TAG,
            if (header[1].toInt() == 0) "RTP packet" else "RTCP packet"
        )

        var packetSize = RtpHeader.getPacketSize(header)
        if (DEBUG) Log.d(
            TAG,
            "Packet size: $packetSize"
        )

        if (NetUtils.readData(inputStream, header, 0, header.size) == header.size) {
            val rtpHeader = RtpHeader.parseData(header, packetSize)
            if (rtpHeader == null) {
                // Header not found. Possible keep-alive response. Search for another RTP header.
                val foundHeader = RtpHeader.searchForNextRtpHeader(inputStream, header)
                if (foundHeader) {
                    packetSize = RtpHeader.getPacketSize(header)
                    if (NetUtils.readData(
                            inputStream,
                            header,
                            0,
                            header.size
                        ) == header.size
                    ) return RtpHeader.parseData(header, packetSize)
                }
            } else {
                return rtpHeader
            }
        }
        return null
    }

    class RtpHeader {
        var version: Int = 0
        var padding: Int = 0
        var extension: Int = 0
        var cc: Int = 0
        var marker: Int = 0
        var payloadType: Int = 0
        var sequenceNumber: Int = 0
        var timeStamp: Long = 0
        var ssrc: Long = 0
        var payloadSize: Int = 0

        val timestampMs: Long
            get() = (timeStamp * 11.111111).toLong()

        fun dumpHeader() {
            Log.d(
                "RTP", ("\t\tRTP header version: " + version
                        + ", padding: " + padding
                        + ", ext: " + extension
                        + ", cc: " + cc
                        + ", marker: " + marker
                        + ", payload type: " + payloadType
                        + ", seq num: " + sequenceNumber
                        + ", ts: " + timeStamp
                        + ", ssrc: " + ssrc
                        + ", payload size: " + payloadSize)
            )
        }

        companion object {
            // If RTP header found, return 4 bytes of the header
            @Throws(IOException::class)
            fun searchForNextRtpHeader(
                inputStream: InputStream,
                header: ByteArray /*out*/
            ): Boolean {
                if (header.size < 4) throw IOException("Invalid allocated buffer size")

                var bytesRemaining = 100000 // 100 KB max to check
                var foundFirstByte = false
                var foundSecondByte = false
                val oneByte = ByteArray(1)
                // Search for {0x24, 0x00}
                do {
                    if (bytesRemaining-- < 0) return false
                    // Read 1 byte
                    NetUtils.readData(inputStream, oneByte, 0, 1)
                    if (foundFirstByte) {
                        // Found 0x24. Checking for 0x00-0x02.
                        if (oneByte[0].toInt() == 0x00) foundSecondByte = true
                        else foundFirstByte = false
                    }
                    if (!foundFirstByte && oneByte[0].toInt() == 0x24) {
                        // Found 0x24
                        foundFirstByte = true
                    }
                } while (!foundSecondByte)
                header[0] = 0x24
                header[1] = oneByte[0]
                // Read 2 bytes more (packet size)
                NetUtils.readData(inputStream, header, 2, 2)
                return true
            }

            fun parseData(header: ByteArray, packetSize: Int): RtpHeader? {
                val rtpHeader = RtpHeader()
                rtpHeader.version = (header[0].toInt() and 0xFF) shr 6
                if (rtpHeader.version != 2) {
                    if (DEBUG) Log.e(TAG, "Not a RTP packet (" + rtpHeader.version + ")")
                    return null
                }

                // 80 60 40 91 fd ab d4 2a
                // 80 c8 00 06
                rtpHeader.padding = (header[0].toInt() and 0x20) shr 5 // 0b00100100
                rtpHeader.extension = (header[0].toInt() and 0x10) shr 4
                rtpHeader.marker = (header[1].toInt() and 0x80) shr 7
                rtpHeader.payloadType = header[1].toInt() and 0x7F
                rtpHeader.sequenceNumber =
                    (header[3].toInt() and 0xFF) + ((header[2].toInt() and 0xFF) shl 8)
                rtpHeader.timeStamp =
                    ((header[7].toInt() and 0xFF) + ((header[6].toInt() and 0xFF) shl 8) + ((header[5].toInt() and 0xFF) shl 16) + ((header[4].toInt() and 0xFF) shl 24)).toLong() and 0xffffffffL
                rtpHeader.ssrc =
                    ((header[7].toInt() and 0xFF) + ((header[6].toInt() and 0xFF) shl 8) + ((header[5].toInt() and 0xFF) shl 16) + ((header[4].toInt() and 0xFF) shl 24)).toLong() and 0xffffffffL
                rtpHeader.payloadSize = packetSize - RTP_HEADER_SIZE
                return rtpHeader
            }

            fun getPacketSize(header: ByteArray): Int {
                val packetSize =
                    ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                if (DEBUG) Log.d(
                    TAG,
                    "Packet size: $packetSize"
                )
                return packetSize
            }
        }
    }
}
