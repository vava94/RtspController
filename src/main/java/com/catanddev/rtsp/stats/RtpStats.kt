package com.catanddev.rtsp.stats

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


class RtpStats {

    data class Stats(
        var width: Int = 0,
        var height: Int = 0,
        var fps: Double = 0.0,
        var bitrateMbps: Double = 0.0,
        var jitterMs: Double = 0.0,
        var currentRtpTimestamp: Int = 0,
        var clockRate: Long = 90000,

        // --- Сетевые метрики (накопительные) ---
        var packetsLost: Int = 0,
        var packetLossPercent: Double = 0.0,
        var outOfOrderPackets: Int = 0,
        var maxBurstLoss: Int = 0,
        var totalPacketsReceived: Long = 0,

        // --- Статистика приёма ---
        var bytesReceived: Long = 0,
        var inputBitrateMbps: Double = 0.0,

        // --- Задержка ---
        var networkLatencyMs: Double = 0.0,
        var decodeLatencyMs: Double = 0.0,
        var totalLatencyMs: Double = 0.0,

        // --- Packet gap ---
        var packetGapAvgMs: Double = 0.0,
        var packetGapMaxMs: Double = 0.0,
        var packetGapMinMs: Double = 0.0
    )

    val stats = Stats()

    // Параметры
    companion object {
        private const val JITTER_DECAY = 0.0625
        private const val MAX_LATENCY_SAMPLES = 100
        private const val SEQ_HALF_RANGE = 0x8000
    }

    private val queueMutex = Any()

    // Джиттер (RFC 3550). Время в миллисекундах (timestamp уже переведён из RTP-единиц).
    private var transit: Double = 0.0
    private var transitInitialized = false
    private var jitter = 0.0
    private var lastRtpTimestamp = 0

    // FPS и битрейт
    private var frameCount = 0L
    private var byteCount = 0L          // Видео (сумма собранных NAL-юнитов)
    private var lastFrameCount = 0L

    // Интервалы между RTP-пакетами (за интервал расчёта)
    private var lastPacketTime: Long = 0
    private var gapCount = 0L
    private var gapSumMs = 0.0
    private var gapMaxMs = 0.0
    private var gapMinMs = Double.MAX_VALUE

    // Задержка декодирования: RTP-время кадра (мс) -> время постановки в декодер (wall clock)
    private val framePushTimes = HashMap<Long, Long>()

    // Анализ sequence number
    private var lastSeq = 0
    private var firstPacket = true
    private var packetsLostTotal = 0        // накопительно
    private var outOfOrderTotal = 0         // накопительно
    private var currentBurst = 0
    private var maxBurst = 0
    private var totalPacketsReceived = 0L

    private var lastCalculationTime: Long = 0

    /**
     * Учёт каждого принятого RTP-пакета.
     *
     * Именно на уровне RTP-пакетов корректно считаются потери, джиттер, межинтервальные
     * задержки и входной битрейт. Учёт на уровне NAL-юнитов (как было раньше) неверен:
     * один NAL может собираться из десятков пакетов, поэтому разница sequence number
     * между соседними NAL — это число фрагментов, а не потери.
     *
     * @param size размер RTP payload в байтах
     * @param timestampMs RTP-время пакета, уже переведённое в миллисекунды
     */
    fun onRtpPacket(size: Int, timestampMs: Long, seq: Int, marker: Boolean) {
        val arrivalTime = System.currentTimeMillis()

        synchronized(queueMutex) {
            // Приём
            stats.bytesReceived += size
            totalPacketsReceived++
            stats.totalPacketsReceived = totalPacketsReceived

            // Потери / переупорядочивание по sequence number (RFC 3550 A.1)
            if (!firstPacket) {
                val expectedSeq = (lastSeq + 1) and 0xFFFF
                val actualSeq = seq and 0xFFFF

                if (actualSeq != expectedSeq) {
                    // Расстояние "вперёд" по модулю 2^16.
                    val forwardDiff = (actualSeq - expectedSeq) and 0xFFFF
                    if (forwardDiff < SEQ_HALF_RANGE) {
                        // Реальный пропуск вперёд — потерянные пакеты.
                        packetsLostTotal += forwardDiff
                        currentBurst += forwardDiff
                        maxBurst = max(maxBurst, currentBurst)
                    } else {
                        // Пакет пришёл позже (переупорядочивание/дубликат) — это не потеря.
                        outOfOrderTotal++
                    }
                } else {
                    currentBurst = 0
                }
            } else {
                firstPacket = false
            }
            lastSeq = seq

            // Джиттер (RFC 3550). timestampMs уже в мс, поэтому transit — тоже в мс.
            val transitTime = (arrivalTime - timestampMs).toDouble()
            if (transitInitialized) {
                val d = transitTime - transit
                jitter += JITTER_DECAY * (abs(d) - jitter)
            } else {
                transitInitialized = true
            }
            transit = transitTime
            lastRtpTimestamp = timestampMs.toInt()
            stats.currentRtpTimestamp = lastRtpTimestamp
            stats.jitterMs = jitter

            // Интервалы между пакетами
            if (lastPacketTime != 0L) {
                val gapMs = (arrivalTime - lastPacketTime).toDouble()
                gapSumMs += gapMs
                gapCount++
                gapMaxMs = max(gapMaxMs, gapMs)
                gapMinMs = min(gapMinMs, gapMs)
            }
            lastPacketTime = arrivalTime

            // Маркер = последний пакет кадра
            if (marker) {
                frameCount++
            }
        }
    }

    /**
     * Учёт видеоданных (размер собранного NAL-юнита) для метрики видеобитрейта.
     */
    fun onVideoNalUnit(size: Int) {
        synchronized(queueMutex) {
            byteCount += size
        }
    }

    /**
     * @deprecated Используйте [onRtpPacket] для сетевых метрик и [onVideoNalUnit] для видеобитрейта.
     */
    @Deprecated("Use onRtpPacket(...) for network stats and onVideoNalUnit(...) for video bitrate")
    fun onPacket(size: Int, timestamp: Long, seq: Int, marker: Boolean) {
        onRtpPacket(size, timestamp, seq, marker)
    }

    fun calculateStats() {
        synchronized(queueMutex) {
            val currentTime = System.currentTimeMillis()
            val timeDiffMs = if (lastCalculationTime != 0L) {
                currentTime - lastCalculationTime
            } else {
                1000L // Первый вызов, используем 1 секунду по умолчанию
            }
            lastCalculationTime = currentTime
            val timeDiffSec = timeDiffMs / 1000.0

            // FPS
            stats.fps = (frameCount - lastFrameCount) / timeDiffSec
            lastFrameCount = frameCount

            // Видеобитрейт (по собранным NAL-юнитам)
            stats.bitrateMbps = (byteCount * 8.0 / (timeDiffSec * 1_048_576))
            byteCount = 0

            // Входной (сетевой) битрейт (по RTP-пакетам)
            stats.inputBitrateMbps = (stats.bytesReceived * 8.0 / (timeDiffSec * 1_048_576))
            stats.bytesReceived = 0

            // Packet gap
            if (gapCount > 0) {
                stats.packetGapAvgMs = gapSumMs / gapCount
                stats.packetGapMaxMs = gapMaxMs
                stats.packetGapMinMs = if (gapMinMs == Double.MAX_VALUE) 0.0 else gapMinMs
            }

            // Накопительные сетевые метрики
            stats.packetsLost = packetsLostTotal
            val totalSeen = packetsLostTotal + totalPacketsReceived
            stats.packetLossPercent = if (totalSeen > 0) {
                packetsLostTotal * 100.0 / totalSeen
            } else {
                0.0
            }
            stats.outOfOrderPackets = outOfOrderTotal
            stats.maxBurstLoss = maxBurst

            // Сбрасываем только интервальные счётчики
            gapCount = 0
            gapSumMs = 0.0
            gapMaxMs = 0.0
            gapMinMs = Double.MAX_VALUE
        }
    }

    /**
     * Вызывается перед постановкой кадра в декодер.
     * [timestampMs] — RTP-время кадра, по нему результат декодирования сопоставляется с push.
     */
    fun onFramePushed(timestampMs: Long) {
        synchronized(queueMutex) {
            if (framePushTimes.size >= MAX_LATENCY_SAMPLES) framePushTimes.clear()
            framePushTimes[timestampMs] = System.currentTimeMillis()
        }
    }

    @Deprecated("Use onFramePushed(timestampMs)")
    fun onFramePushed() {
        onFramePushed(stats.currentRtpTimestamp.toLong())
    }

    /**
     * Вызывается после декодирования кадра. [timestampMs] должен совпадать со значением,
     * переданным в [onFramePushed] (presentation timestamp декодера).
     *
     * Считается задержка «постановка в декодер -> готовый кадр». Сетевую задержку без
     * синхронизации часов с сервером (RTCP SR / NTP) измерить невозможно, поэтому
     * [Stats.networkLatencyMs] остаётся 0, а [Stats.totalLatencyMs] равен задержке декодирования.
     */
    fun onFrameDecoded(timestampMs: Long) {
        synchronized(queueMutex) {
            val pushTime = framePushTimes.remove(timestampMs) ?: return
            val decodeLatency = (System.currentTimeMillis() - pushTime).toDouble()
            stats.decodeLatencyMs = decodeLatency
            stats.totalLatencyMs = decodeLatency
        }
    }

    fun setResolution(width: Int, height: Int) {
        stats.width = width
        stats.height = height
    }

    /**
     * Сбрасывает всю статистику
     */
    fun reset() {
        synchronized(queueMutex) {
            framePushTimes.clear()

            jitter = 0.0
            transit = 0.0
            transitInitialized = false
            lastRtpTimestamp = 0
            frameCount = 0L
            byteCount = 0L
            lastFrameCount = 0L
            lastPacketTime = 0L
            lastCalculationTime = 0L

            gapCount = 0L
            gapSumMs = 0.0
            gapMaxMs = 0.0
            gapMinMs = Double.MAX_VALUE

            lastSeq = 0
            firstPacket = true
            packetsLostTotal = 0
            outOfOrderTotal = 0
            currentBurst = 0
            maxBurst = 0
            totalPacketsReceived = 0L

            stats.apply {
                fps = 0.0
                bitrateMbps = 0.0
                jitterMs = 0.0
                packetsLost = 0
                packetLossPercent = 0.0
                outOfOrderPackets = 0
                maxBurstLoss = 0
                totalPacketsReceived = 0
                bytesReceived = 0
                inputBitrateMbps = 0.0
                networkLatencyMs = 0.0
                decodeLatencyMs = 0.0
                totalLatencyMs = 0.0
                packetGapAvgMs = 0.0
                packetGapMaxMs = 0.0
                packetGapMinMs = 0.0
            }
        }
    }
}
