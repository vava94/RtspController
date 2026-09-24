package com.catanddev.rtsp.stats

import android.util.Log
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

        // --- Новые сетевые метрики ---
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
        private const val MAX_FRAME_INTERVALS = 30
        private const val DEFAULT_CLOCK_RATE = 90000L
        private const val JITTER_DECAY = 0.0625
    }

    private val queueMutex = Any()

    // Для джиттера (RFC 3550)
    private var lastRtpTimestamp = 0
    private var transit: Double = 0.0
    private var jitter = 0.0

    // Для автоопределения clock rate
    private val frameIntervals = mutableListOf<Double>()
    private var lastFrameTime: Long = 0
    private var lastFrameTimestamp = 0
    private var clockRateLocked = false

    // Для FPS и битрейта
    private var frameCount = 0L
    private var byteCount = 0L
    private var lastFrameCount = 0L

    // Для задержки
    private var lastPacketTime: Long = 0
    private var lastFrameArrival: Long = 0
    private var framePushedTime: Long = 0

    // Для packet gap
    private var gapCount = 0L
    private var gapSumMs = 0.0
    private var gapMaxMs = 0.0
    private var gapMinMs = Double.MAX_VALUE

    // Для точек синхронизации задержки
    private data class SyncPoint(
        val networkTime: Long,
        val decodeTime: Long,
        val rtpTimestamp: Int
    )
    private val syncPoints = mutableListOf<SyncPoint>()
    private val maxSyncPoints = 100

    // --- Новые поля для сетевых метрик ---
    // Sequence number анализ
    private var lastSeq = 0
    private var packetsLostTotal = 0
    private var outOfOrderTotal = 0
    private var currentBurst = 0
    private var maxBurst = 0
    private var firstPacket = true
    private var totalPacketsReceived = 0L

    private var lastCalculationTime: Long = 0
    fun onPacket(size: Int, timestamp: Long, seq: Int, marker: Boolean) {
        val arrivalTime = System.currentTimeMillis()

        synchronized(queueMutex) {
            // Обновляем статистику приёма
            stats.bytesReceived += size
            byteCount += size
            totalPacketsReceived++
            stats.totalPacketsReceived = totalPacketsReceived

            // Анализ последовательности пакетов
            if (!firstPacket) {
                val expectedSeq = (lastSeq + 1) and 0xFFFF
                val actualSeq = seq and 0xFFFF

                if (actualSeq != expectedSeq) {
                    val lostCount = if (actualSeq > expectedSeq) {
                        actualSeq - expectedSeq
                    } else {
                        actualSeq + 0x10000 - expectedSeq
                    }

                    packetsLostTotal += lostCount
                    currentBurst += lostCount
                    maxBurst = max(maxBurst, currentBurst)


                    // Проверка на out-of-order
                    if (actualSeq < lastSeq && (lastSeq - actualSeq) < 0x8000) {
                        outOfOrderTotal++
                    }
                } else {
                    currentBurst = 0
                }
            } else {
                firstPacket = false
            }

            lastSeq = seq

            // Обновляем джиттер (RFC 3550)
            if (lastRtpTimestamp != 0) {
                val transitTime = arrivalTime - ((timestamp * 1000.0) / stats.clockRate)
                val d = transitTime - transit
                transit = transitTime

                jitter += JITTER_DECAY * (kotlin.math.abs(d) - jitter)
            }
            lastRtpTimestamp = timestamp.toInt()

            stats.currentRtpTimestamp = lastRtpTimestamp
            stats.jitterMs = jitter

            // Обновляем packet gap
            if (lastPacketTime != 0L) {
                val gapMs = (arrivalTime - lastPacketTime).toDouble()
                gapSumMs += gapMs
                gapCount++
                gapMaxMs = max(gapMaxMs, gapMs)
                gapMinMs = min(gapMinMs, gapMs)
            }
            lastPacketTime = arrivalTime

            // Если это маркерный пакет (начало кадра)
            if (marker) {
                onFrameReceived(timestamp.toInt(), arrivalTime)
            }
        }
    }

    private fun onFrameReceived(rtpTimestamp: Int, arrivalTime: Long) {
        frameCount++
        lastFrameArrival = arrivalTime

        // Обновляем интервалы между кадрами для определения FPS
        if (lastFrameTime != 0L) {
            val intervalMs = (arrivalTime - lastFrameTime).toDouble()
            frameIntervals.add(intervalMs)
            if (frameIntervals.size > MAX_FRAME_INTERVALS) {
                frameIntervals.removeAt(0)
            }
        }
        val previousTime = lastFrameTime
        val previousTimestamp = lastFrameTimestamp
        lastFrameTime = arrivalTime
        lastFrameTimestamp = rtpTimestamp

        // Автоопределение clock rate
        if (!clockRateLocked && frameIntervals.size >= 10) {
            // Пытаемся определить clock rate по интервалам кадров
            // Предполагаем, что интервал между кадрами в RTP timestamp примерно постоянен
            if (previousTimestamp != 0 && rtpTimestamp != previousTimestamp) {
                val rtpInterval = (rtpTimestamp - previousTimestamp) and 0xFFFFFFFFL.toInt()
                val timeIntervalMs = (arrivalTime - previousTime).toDouble()
                val estimatedClockRate = ((rtpInterval * 1000.0) / timeIntervalMs).toLong()
                
                // Если оценка близка к стандартным значениям, используем её
                if (estimatedClockRate in 8000..90000) {
                    stats.clockRate = estimatedClockRate
                    clockRateLocked = true
                    Log.i("RtpStats", "Clock rate auto-detected: $estimatedClockRate")
                }
            }
        }
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

            // Рассчитываем FPS
            stats.fps = (frameCount - lastFrameCount) / timeDiffSec
            lastFrameCount = frameCount

            // Рассчитываем bitrate (используем степени двойки для двоичных кратных: 1 MiB = 1048576 байт)
            stats.bitrateMbps = (byteCount * 8.0 / (timeDiffSec * 1_048_576))
            byteCount = 0

            // Рассчитываем input bitrate (используем степени двойки для двоичных кратных)
            stats.inputBitrateMbps = (stats.bytesReceived * 8.0 / (timeDiffSec * 1_048_576))
            stats.bytesReceived = 0

            // Рассчитываем packet gap
            if (gapCount > 0) {
                stats.packetGapAvgMs = gapSumMs / gapCount
                stats.packetGapMaxMs = gapMaxMs
                stats.packetGapMinMs = if (gapMinMs == Double.MAX_VALUE) 0.0 else gapMinMs
            }

            // Рассчитываем сетевые метрики
            stats.packetsLost = packetsLostTotal
            stats.packetLossPercent = if (totalPacketsReceived > 0) {
                (packetsLostTotal * 100.0 / totalPacketsReceived)
            } else 0.0
            stats.outOfOrderPackets = outOfOrderTotal
            stats.maxBurstLoss = maxBurst

            // Очищаем временные данные
            gapCount = 0
            gapSumMs = 0.0
            gapMaxMs = 0.0
            gapMinMs = Double.MAX_VALUE

            // Сбрасываем счётчики для следующего интервала
            packetsLostTotal = 0
            outOfOrderTotal = 0
            currentBurst = 0
        }
    }

    fun onFramePushed() {
        synchronized(queueMutex) {
            framePushedTime = System.currentTimeMillis()
            val currentRtpTimestamp = stats.currentRtpTimestamp
            
            // Сохраняем точку синхронизации
            val syncPoint = SyncPoint(
                networkTime = framePushedTime,
                decodeTime = 0,
                rtpTimestamp = currentRtpTimestamp
            )
            syncPoints.add(syncPoint)
            if (syncPoints.size > maxSyncPoints) {
                syncPoints.removeAt(0)
            }
        }
    }

    fun onFrameDecoded(decodeTime: Long) {
        synchronized(queueMutex) {
            // Находим последнюю точку синхронизации
            val syncPoint = syncPoints.lastOrNull()
            if (syncPoint != null) {
                // Обновляем статистику задержки
                val networkLatency = System.currentTimeMillis() - syncPoint.networkTime
                val decodeLatency = decodeTime - syncPoint.networkTime
                
                stats.networkLatencyMs = networkLatency.toDouble()
                stats.decodeLatencyMs = decodeLatency.toDouble()
                stats.totalLatencyMs = (networkLatency + decodeLatency).toDouble()
                
                // Обновляем точку синхронизации
                val updatedSyncPoint = syncPoint.copy(decodeTime = decodeTime)
                val index = syncPoints.indexOf(syncPoint)
                if (index >= 0) {
                    syncPoints[index] = updatedSyncPoint
                }
            }

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
            frameIntervals.clear()
            syncPoints.clear()
            
            jitter = 0.0
            lastRtpTimestamp = 0
            lastFrameTime = 0
            lastFrameTimestamp = 0
            clockRateLocked = false
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
            packetsLostTotal = 0
            outOfOrderTotal = 0
            currentBurst = 0
            maxBurst = 0
            firstPacket = true
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