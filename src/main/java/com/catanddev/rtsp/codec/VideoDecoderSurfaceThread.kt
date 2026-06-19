package com.catanddev.rtsp.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class VideoDecoderSurfaceThread (
    private var surface: Surface?,
    mimeType: String,
    width: Int,
    height: Int,
    rotation: Int, // 0, 90, 180, 270
    videoFrameQueue: VideoFrameQueue,
    videoDecoderListener: VideoDecoderListener,
    videoDecoderType: DecoderType = DecoderType.HARDWARE,
    rtpStats: com.catanddev.rtsp.stats.RtpStats? = null
): VideoDecodeThread(
    mimeType, width, height, rotation, videoFrameQueue, videoDecoderListener, videoDecoderType, rtpStats) {

    private val TAG = VideoDecoderSurfaceThread::class.java.simpleName

    @Synchronized
    fun updateSurface(newSurface: Surface) {
        surface = newSurface
        Log.d(TAG, "Surface updated to new instance")
    }

    @Synchronized
    private fun isSurfaceValid(): Boolean {
        return try {
            surface!!.isValid
        } catch (e: Exception) {
            Log.e(TAG, "Error checking surface validity: ${e.message}")
            false
        }
    }

    private fun waitForSurfaceValid(timeoutMs: Long = 3000): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isSurfaceValid()) {
                Log.d(TAG, "Surface became valid after ${System.currentTimeMillis() - startTime}ms")
                return true
            }
            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        Log.w(TAG, "Timeout waiting for Surface to become valid after $timeoutMs ms")
        return false
    }

    override fun decoderCreated(mediaCodec: MediaCodec, mediaFormat: MediaFormat) {
        Log.d(TAG, "decoderCreated() called, checking surface validity...")

        synchronized(this) {
            // Ждем пока Surface станет валидным
            if (!isSurfaceValid()) {
                Log.w(TAG, "Surface is not valid yet, waiting up to 3 seconds...")
                if (!waitForSurfaceValid(3000)) {
                    Log.e(TAG, "Timeout waiting for Surface to become valid. Will try to configure anyway.")
                    // Не выбрасываем исключение, а пытаемся сконфигурировать
                }
            }

            try {
                mediaCodec.configure(mediaFormat, surface, null, 0)
                Log.i(TAG, "Decoder successfully configured with surface")
            } catch (e: IllegalArgumentException) {
                if (e.message?.contains("surface has been released") == true) {
                    Log.e(TAG, "Surface was released during configuration. Attempting recovery...")
                    // Даем больше времени на восстановление
                    Thread.sleep(100)

                    if (!isSurfaceValid()) {
                        // Если все еще невалиден, пытаемся продолжить с текущим Surface
                        Log.w(TAG, "Surface still not valid, but attempting configuration anyway...")
                        try {
                            mediaCodec.configure(mediaFormat, surface, null, 0)
                            Log.i(TAG, "Decoder successfully configured after surface recovery")
                        } catch (e2: Exception) {
                            Log.e(TAG, "Final configuration attempt failed: ${e2.message}")
                            // Теперь выбрасываем исключение, так как все попытки провалились
                            throw IllegalStateException("Surface is not valid for decoder configuration after recovery attempts")
                        }
                    } else {
                        // Повторная попытка конфигурации
                        mediaCodec.configure(mediaFormat, surface, null, 0)
                        Log.i(TAG, "Decoder successfully configured after surface recovery")
                    }
                } else {
                    throw e
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Decoder is in illegal state for configuration: ${e.message}")
                // Для IllegalStateException тоже даем шанс на восстановление
                Thread.sleep(50)
                try {
                    mediaCodec.configure(mediaFormat, surface, null, 0)
                    Log.i(TAG, "Decoder successfully configured after illegal state recovery")
                } catch (e2: Exception) {
                    Log.e(TAG, "Recovery from illegal state failed: ${e2.message}")
                    throw e
                }
            }
        }
    }

    override fun releaseOutputBuffer(mediaCodec: MediaCodec, outIndex: Int, render: Boolean) {
        synchronized(this) {
            val shouldRender = render && isSurfaceValid()

            if (DEBUG) {
                if (!isSurfaceValid()) {
                    Log.w(TAG, "Surface is not valid, skipping render for buffer $outIndex")
                } else if (render) {
                    Log.d(TAG, "Rendering buffer $outIndex")
                }
            }

            try {
                mediaCodec.releaseOutputBuffer(outIndex, shouldRender)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to release output buffer $outIndex: ${e.message}")
                // Не прерываем выполнение, просто логируем ошибку
            } catch (e: MediaCodec.CodecException) {
                Log.e(TAG, "Codec exception when releasing buffer $outIndex: ${e.diagnosticInfo}")
                if (!e.isRecoverable && !e.isTransient) {
                    throw e
                }
            }
        }
    }

    override fun decoderDestroyed(mediaCodec: MediaCodec) {
        Log.d(TAG, "decoderDestroyed() called")
        // Не освобождаем Surface здесь, так как он управляется извне (RtspStreamView)
    }

    companion object {
        private const val DEBUG = false
    }
}