package com.catanddev.rtsp.codec

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaCodec.OnFrameRenderedListener
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.catanddev.rtsp.utils.MediaCodecUtils
import com.catanddev.rtsp.utils.capabilitiesToString
import androidx.media3.common.util.Util
import com.catanddev.rtsp.Logger.dumbLog
import com.catanddev.rtsp.utils.VideoCodecUtils
import java.lang.Integer.min
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

abstract class VideoDecodeThread (
    protected val mimeType: String,
    protected val width: Int,
    protected val height: Int,
    protected val rotation: Int, // 0, 90, 180, 270
    protected val videoFrameQueue: VideoFrameQueue,
    protected val videoDecoderListener: VideoDecoderListener,
    protected var videoDecoderType: DecoderType,
    protected val rtpStats: com.catanddev.rtsp.stats.RtpStats? = null
) : Thread() {

    enum class DecoderType {
        HARDWARE,
        SOFTWARE // fallback
    }

    interface VideoDecoderListener {
        /** Video decoder successfully started */
        fun onVideoDecoderStarted() {}
        /** Video decoder successfully stopped */
        fun onVideoDecoderStopped() {}
        /** Fatal error occurred */
        fun onVideoDecoderFailed(message: String?) {}
        /** Resolution changed */
        fun onVideoDecoderFormatChanged(width: Int, height: Int) {}
        /** First video frame rendered */
        fun onVideoDecoderFirstFrameRendered() {}
    }

    protected val uiHandler = Handler(Looper.getMainLooper())
    protected var exitFlag = AtomicBoolean(false)
    protected var firstFrameRendered = false

    /** Decoder latency used for statistics */
    @Volatile private var decoderLatency = -1
    /** Flag for allowing calculating latency */
    private var decoderLatencyRequested = false
    /** Network latency used for statistics */
    @Volatile private var networkLatency = -1
    private var videoDecoderName: String? = null
    private var firstFrameDecoded = false

    fun stopAsync() {
        if (debug) Log.v(TAG, "stopAsync()")
        exitFlag.set(true)
        // Wake up sleep() code
        interrupt()
    }

    /**
     * Currently used video decoder. Video decoder can be changed on runtime.
     * If videoDecoderType set to HARDWARE, it can be switched to SOFTWARE in case of decoding issue
     * (e.g. hardware decoder does not support the stream resolution).
     * If videoDecoderType set to SOFTWARE, it will always remain SOFTWARE (no any changes).
     */
    fun getCurrentVideoDecoderType(): DecoderType {
        return videoDecoderType
    }

    fun getCurrentVideoDecoderName(): String? {
        return videoDecoderName
    }

    /**
     * Get frames decoding/rendering latency in msec. Returns -1 if not supported.
     */
    fun getCurrentVideoDecoderLatencyMsec(): Int {
        decoderLatencyRequested = true
        return decoderLatency
    }

    /**
     * Get network latency in msec. Returns -1 if not supported.
     */
    fun getCurrentNetworkLatencyMsec(): Int {
        return networkLatency
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun getDecoderSafeWidthHeight(decoder: MediaCodec): Pair<Int, Int> {
        val capabilities = decoder.codecInfo.getCapabilitiesForType(mimeType).videoCapabilities
        return if (capabilities!!.isSizeSupported(width, height)) {
            Pair(width, height)
        } else {
            val widthAlignment = capabilities.widthAlignment
            val heightAlignment = capabilities.heightAlignment
            Pair(
                Util.ceilDivide(width, widthAlignment) * widthAlignment,
                Util.ceilDivide(height, heightAlignment) * heightAlignment)
        }
    }

    @SuppressLint("InlinedApi")
    private fun getWidthHeight(mediaFormat: MediaFormat): Pair<Int, Int> {
        // Sometimes height obtained via KEY_HEIGHT is not valid, e.g. can be 1088 instead 1080
        // (no problems with width though). Use crop parameters to correctly determine height.
        val hasCrop =
            mediaFormat.containsKey(MediaFormat.KEY_CROP_RIGHT) && mediaFormat.containsKey(MediaFormat.KEY_CROP_LEFT) &&
                    mediaFormat.containsKey(MediaFormat.KEY_CROP_BOTTOM) && mediaFormat.containsKey(MediaFormat.KEY_CROP_TOP)
        val width =
            if (hasCrop)
                mediaFormat.getInteger(MediaFormat.KEY_CROP_RIGHT) - mediaFormat.getInteger(MediaFormat.KEY_CROP_LEFT) + 1
            else
                mediaFormat.getInteger(MediaFormat.KEY_WIDTH)
        var height =
            if (hasCrop)
                mediaFormat.getInteger(MediaFormat.KEY_CROP_BOTTOM) - mediaFormat.getInteger(MediaFormat.KEY_CROP_TOP) + 1
            else
                mediaFormat.getInteger(MediaFormat.KEY_HEIGHT)
        // Fix for 1080p resolution for Samsung S21
        // {crop-right=1919, max-height=4320, sar-width=1, color-format=2130708361, mime=video/raw,
        // hdr-static-info=java.nio.HeapByteBuffer[pos=0 lim=25 cap=25],
        // priority=0, color-standard=1, feature-secure-playback=0, color-transfer=3, sar-height=1,
        // crop-bottom=1087, max-width=8192, crop-left=0, width=1920, color-range=2, crop-top=0,
        // rotation-degrees=0, frame-rate=30, height=1088}
        //height /= 256 // (16*16) 1088 -> 1080
//        if (height == 1088)
//            height = 1080
        return Pair(width, height)
    }

    private fun getDecoderMediaFormat(decoder: MediaCodec): MediaFormat {
        if (debug) Log.v(TAG, "getDecoderMediaFormat()")
        val safeWidthHeight = getDecoderSafeWidthHeight(decoder)
        val format = MediaFormat.createVideoFormat(mimeType, safeWidthHeight.first, safeWidthHeight.second)
        if (debug)
            Log.d(TAG, "Configuring surface ${safeWidthHeight.first}x${safeWidthHeight.second} w/ '$mimeType'")
        else
            Log.i(TAG, "Configuring surface ${safeWidthHeight.first}x${safeWidthHeight.second} w/ '$mimeType'")
        format.setInteger(MediaFormat.KEY_ROTATION, rotation)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024) // 1MB для FullHD
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            // format.setFeatureEnabled(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency, true)
//            // Request low-latency for the decoder. Not all of the decoders support that.
//            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
//        }

        val succeeded = MediaCodecHelper.setDecoderLowLatencyOptions(format, decoder.codecInfo, 1)
        Log.i(TAG, "Low-latency: $succeeded")

        return format
    }

    /** Decoder created */
    abstract fun decoderCreated(mediaCodec: MediaCodec, mediaFormat: MediaFormat)

    /** Frame processed */
    abstract fun releaseOutputBuffer(mediaCodec: MediaCodec, outIndex: Int, render: Boolean)

    /** Decoder stopped and released */
    abstract fun decoderDestroyed(mediaCodec: MediaCodec)

    private fun createVideoDecoderAndStart(decoderType: DecoderType): MediaCodec {
        if (debug) Log.v(TAG, "createVideoDecoderAndStart(decoderType=$decoderType)")

        @SuppressLint("UnsafeOptInUsageError")
        val decoder = when (decoderType) {
            DecoderType.HARDWARE -> {
                val hwDecoders = MediaCodecUtils.getHardwareDecoders(mimeType)
                if (hwDecoders.isEmpty()) {
                    Log.w(TAG, "Cannot get hardware video decoders for mime type '$mimeType'. Using default one.")
                    MediaCodec.createDecoderByType(mimeType)
                } else {
                    val lowLatencyDecoder = MediaCodecUtils.getLowLatencyDecoder(hwDecoders)
                    val name = lowLatencyDecoder?.let {
                        Log.i(TAG, "[$name] Dedicated low-latency decoder found '${lowLatencyDecoder.name}'")
                        lowLatencyDecoder.name
                    } ?: hwDecoders[0].name
                    MediaCodec.createByCodecName(name)
                }
            }
            DecoderType.SOFTWARE -> {
                val swDecoders = MediaCodecUtils.getSoftwareDecoders(mimeType)
                if (swDecoders.isEmpty()) {
                    Log.w(TAG, "Cannot get software video decoders for mime type '$mimeType'. Using default one .")
                    MediaCodec.createDecoderByType(mimeType)
                } else {
                    val name = swDecoders[0].name
                    MediaCodec.createByCodecName(name)
                }
            }
        }
        this.videoDecoderType = decoderType

        this.videoDecoderName = decoder.name

        val frameRenderedListener = OnFrameRenderedListener { _, _, _ ->
            if (!firstFrameRendered) {
                firstFrameRendered = true
                uiHandler.post {
                    videoDecoderListener.onVideoDecoderFirstFrameRendered()
                }
            }
        }
        decoder.setOnFrameRenderedListener(frameRenderedListener, null)
        val format = getDecoderMediaFormat(decoder)
        decoderCreated(decoder, format)
        decoder.start()

        val capabilities = decoder.codecInfo.getCapabilitiesForType(mimeType)

        val lowLatencySupport =
            capabilities.isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        Log.i(TAG, "[$name] Video decoder '${decoder.name}' started " +
                "(${ if (decoder.codecInfo.isHardwareAccelerated) "hardware" else "software" }, " +
                "${capabilities.capabilitiesToString()}, " +
                "${if (lowLatencySupport) "w/" else "w/o"} low-latency support)")

        return decoder
    }

    private fun stopAndReleaseVideoDecoder(decoder: MediaCodec) {
        if (debug) Log.v(TAG, "stopAndReleaseVideoDecoder()")
        val type = videoDecoderType.toString().lowercase()
        Log.i(TAG, "Stopping $type video decoder...")
        try {
            decoder.stop()
            Log.i(TAG, "Decoder successfully stopped")
        } catch (e3: Throwable) {
            Log.e(TAG, "Failed to stop decoder", e3)
        }
        Log.i(TAG, "Releasing decoder...")
        try {
            decoder.release()
            Log.i(TAG, "Decoder successfully released")
        } catch (e3: Throwable) {
            Log.e(TAG, "Failed to release decoder", e3)
        }
        videoFrameQueue.clear()
        decoderDestroyed(decoder)
    }


    override fun run() {
        if (debug) Log.d(TAG, "$name started")

        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        videoDecoderListener.onVideoDecoderStarted()

        try {
            Log.i(TAG, "Starting hardware video decoder...")
            var decoder = try {
                createVideoDecoderAndStart(videoDecoderType)
            }
            catch (e: Throwable) {
                Log.e(TAG, "Failed to start $videoDecoderType video decoder (${e.message})", e)
                Log.i(TAG, "Starting software video decoder...")
                try {
                    createVideoDecoderAndStart(DecoderType.SOFTWARE)
                } catch (e2: Throwable) {
                    Log.e(TAG, "Failed to start video software decoder. Exiting...", e2)
                    // Unexpected behavior
                    videoDecoderListener.onVideoDecoderFailed("Cannot initialize video decoder for mime type '$mimeType'")
                    return
                }
            }
            val bufferInfo = MediaCodec.BufferInfo()

            try {
                var widthHeightFromStream: Pair<Int, Int>? = null
                // Map for calculating decoder rendering latency.
                // key - original frame timestamp, value - timestamp when frame was added to the map
                val keyframesTimestamps = HashMap<Long, Long>()
                var frameQueuedMs = System.currentTimeMillis()
                var frameAlreadyDequeued = false
                var inIndex = -1
                var byteBuffer: ByteBuffer? = null
                var render = false
                var timeout = 0L
                var outIndex = 0
                var flags = 0
                // Main loop
                started = true
                while (!exitFlag.get()) {
                    try {
                        inIndex = decoder.dequeueInputBuffer(DEQUEUE_INPUT_TIMEOUT_US)
                        if (inIndex >= 0) {

                            byteBuffer = decoder.getInputBuffer(inIndex)
                            byteBuffer?.rewind()
                            val frame = videoFrameQueue.pop()

                            if (frame == null) {
                                if (debug) Log.d(TAG, "Empty video frame")
                                // Release input buffer
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                            }
                            else {
                                // Add timestamp for keyframe to calculating latency further.
                                if ((debug || decoderLatencyRequested) && frame.isKeyframe) {
                                    if (keyframesTimestamps.size > 5) {
                                                        // Something wrong with map. Allow only 5 map entries.
                                        keyframesTimestamps.clear()
                                    }
                                    val l = System.currentTimeMillis()
                                    keyframesTimestamps[frame.timestampMs] = l
                                    if (debug) Log.d(TAG, "Added $l")
                                }
                                // Calculate network latency
                                // networkLatency = if (frame.capturedTimestampMs > -1)
                                //     (frame.timestampMs - frame.capturedTimestampMs).toInt()
                                // else
                                //     -1

                                byteBuffer?.put(frame.data, frame.offset, frame.length)
                                if (debug) {
                                    val l = System.currentTimeMillis()
                                    Log.i(TAG, "\tFrame queued (${l - frameQueuedMs}) ${if (frame.isKeyframe) "key frame" else ""}")
                                    frameQueuedMs = l
                                }

                                flags = if (frame.isKeyframe)
                                    (MediaCodec.BUFFER_FLAG_KEY_FRAME /*or MediaCodec.BUFFER_FLAG_CODEC_CONFIG*/) else 0
                                decoder.queueInputBuffer(inIndex, frame.offset, frame.length, frame.timestampMs, flags)

                                // Notify RTP stats about frame push
                                rtpStats?.onFramePushed()

                                if (frame.isKeyframe) {
                                    // Obtain width and height from stream
                                    widthHeightFromStream = try {
                                        VideoCodecUtils.getWidthHeightFromArray(
                                            frame.data,
                                            frame.offset,
                                            // Check only first 100 bytes maximum. That's enough for finding SPS NAL unit.
                                            min(frame.length, VideoCodecUtils.MAX_NAL_SPS_SIZE),
                                            isH265 = frame.codecType == VideoCodecType.H265
                                        )
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            }
                        }

                        if (exitFlag.get()) break

                        do {
                            timeout = if (frameAlreadyDequeued || !firstFrameDecoded) 0L else DEQUEUE_OUTPUT_BUFFER_TIMEOUT_US
                            outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeout)
                            when (outIndex) {
                                // Resolution changed
                                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    Log.d(TAG, "Decoder format changed: ${decoder.outputFormat}")
                                    // Decoder can contain different resolution (it can make downsampling).
                                    // If resolution successfully obtained from SPS frame, use it.
                                    val widthHeightFromDecoder = getWidthHeight(decoder.outputFormat)
                                    val widthHeight = widthHeightFromStream ?: widthHeightFromDecoder
                                    Log.i(TAG, "Video decoder resolution: ${widthHeightFromDecoder.first}x${widthHeightFromDecoder.second}, stream resolution: ${widthHeightFromStream?.first}x${widthHeightFromStream?.second}")

//                                    val widthHeightFromDecoder = getWidthHeight(decoder.outputFormat)
                                    val rotation = if (decoder.outputFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                                        decoder.outputFormat.getInteger(MediaFormat.KEY_ROTATION)
                                    } else {
                                        // Some devices like Samsung SM-A505U (Android 11) do not allow
                                        // video stream rotation on decoding for hardware decoder
                                        Log.w(TAG, "Video stream rotation is not supported by this Android device (${Build.MODEL} - ${Build.DEVICE}, codec: '${decoder.name}')")
                                        0
                                    }
                                    uiHandler.post {
                                        // Run in UI thread
                                        when (rotation) {
                                            90, 270 -> videoDecoderListener.onVideoDecoderFormatChanged(widthHeight.second, widthHeight.first)
                                            else -> videoDecoderListener.onVideoDecoderFormatChanged(widthHeight.first, widthHeight.second)
                                        }
                                    }
                                    frameAlreadyDequeued = true
                                }
                                // No any frames in queue
                                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                    if (debug) Log.d(TAG, "No output from decoder available")
                                    frameAlreadyDequeued = true
                                }
                                // Frame decoded
                                else -> {
                                    if (outIndex >= 0) {
                                        if (debug || decoderLatencyRequested) {
                                            val ts = bufferInfo.presentationTimeUs
                                            keyframesTimestamps.remove(ts)?.apply {
                                                decoderLatency = (System.currentTimeMillis() - this).toInt()
                                            }
                                        }


                                        render = bufferInfo.size != 0 && !exitFlag.get()
                                        if (debug) Log.i(TAG, "\tFrame decoded [outIndex=$outIndex, render=$render]")
                                        releaseOutputBuffer(decoder, outIndex, render)
                                        // Notify RTP stats about frame decoded
                                        rtpStats?.onFrameDecoded(bufferInfo.presentationTimeUs)
                                        if (!firstFrameDecoded && render) {
                                            firstFrameDecoded = true
                                        }
                                        frameAlreadyDequeued = false
                                    } else {
                                        Log.e(TAG, "Obtaining frame failed w/ error code $outIndex")
                                    }
                                }
                            }
                        } while (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)

                        // All decoded frames have been rendered, we can stop playing now
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            if (debug) Log.d(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM")
                            break
                        }
                    }
                    catch (_: InterruptedException) {
                    }
                    catch (e: IllegalStateException) {
                        // Restarting decoder in software mode
                        Log.e(TAG, "${e.message}", e)
                        stopAndReleaseVideoDecoder(decoder)
                        Log.i(TAG, "Starting software video decoder...")
                        decoder = createVideoDecoderAndStart(DecoderType.SOFTWARE)
                        Log.i(TAG, "Software video decoder '${decoder.name}' started (${decoder.codecInfo.getCapabilitiesForType(mimeType).capabilitiesToString()})")
                    }
                    catch (e: MediaCodec.CodecException) {
                        Log.w(TAG, "${e.diagnosticInfo}\nisRecoverable: $${e.isRecoverable}, isTransient: ${e.isTransient}")
                        if (e.isRecoverable) {
                            // Recoverable error.
                            // Calling stop(), configure(), and start() to recover.
                            Log.i(TAG, "Recovering video decoder...")
                            try {
                                decoder.stop()
                                val format = getDecoderMediaFormat(decoder)
                                decoderCreated(decoder, format)
                                decoder.start()
                                Log.i(TAG, "Video decoder recovering succeeded")
                            } catch (e2: Throwable) {
                                Log.e(TAG, "Video decoder recovering failed")
                                Log.e(TAG, "${e2.message}", e2)
                            }
                        } else if (e.isTransient) {
                            // Transient error. Resources are temporarily unavailable and
                            // the method may be retried at a later time.
                            Log.w(TAG, "Video decoder resource temporarily unavailable")
                        } else {
                            // Fatal error. Restarting decoder in software mode.
                            stopAndReleaseVideoDecoder(decoder)
                            Log.i(TAG, "Starting video software decoder...")
                            decoder = createVideoDecoderAndStart(DecoderType.SOFTWARE)
                            Log.i(TAG, "Software video decoder '${decoder.name}' started (${decoder.codecInfo.getCapabilitiesForType(mimeType).capabilitiesToString()})")
                        }
                    }
                    catch (e: Throwable) {
                        Log.e(TAG, "${e.message}", e)
                    }
                } // while

                started = false

                // Drain decoder
                inIndex = decoder.dequeueInputBuffer(DEQUEUE_INPUT_TIMEOUT_US)
                if (inIndex >= 0) {
                    decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    Log.w(TAG, "Not able to signal end of stream")
                }

            } catch (e2: Throwable) {
                Log.e(TAG, "${e2.message}", e2)
            } finally {
                stopAndReleaseVideoDecoder(decoder)
            }

        } catch (e: Throwable) {
            Log.e(TAG, "$name stopped due to '${e.message}'")
            videoDecoderListener.onVideoDecoderFailed(e.message)
            // While configuring stopAsync can be called and surface released. Just exit.
            if (!exitFlag.get()) e.printStackTrace()
            return
        }

        videoDecoderListener.onVideoDecoderStopped()
        if (debug) Log.d(TAG, "$name stopped")
    }


    companion object {
        internal val TAG: String = VideoDecodeThread::class.java.simpleName
        private const val debug = false

        private val DEQUEUE_INPUT_TIMEOUT_US = TimeUnit.MILLISECONDS.toMicros(1)
        private val DEQUEUE_OUTPUT_BUFFER_TIMEOUT_US = TimeUnit.MILLISECONDS.toMicros(1)
        var started = false
    }

}
