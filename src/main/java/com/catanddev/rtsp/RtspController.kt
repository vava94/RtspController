package com.catanddev.rtsp


import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.view.Surface
import com.catanddev.rtsp.codec.MediaCodecHelper
import com.catanddev.rtsp.codec.VideoDecoderSurfaceThread
import com.catanddev.rtsp.stats.RtpStats
import com.catanddev.rtsp.widget.RtspProcessor


class RtspController(
    context: Context,
    val mode: OPERATION_MODE,
    val surface: Surface,
    viewWidth: Int,
    viewHeight: Int,
    val frameHandler: RtspControllerCallbacks?) {

    companion object {
        val TAG = RtspController::class.java.simpleName
        val DEBUG = true
    }

    interface RtspControllerCallbacks{
        fun onFrameAvailable()
    }

    enum class OPERATION_MODE{
        RTSP,
        RTP
    }

    enum class VideoCodec {
        H264,
        H265
    }

    var isInitialized = false
        private set

    var isActive = false
        private set


    var currentViewWidth = viewWidth
        private set
    var currentViewHeight = viewHeight
        private set

    var videoCodec = VideoCodec.H264
        private set

    private var mRtspProcessor: RtspProcessor? = null

    // Callback для синхронизации размеров с внешним view
    var onVideoSizeChanged: ((width: Int, height: Int, rotation: Int) -> Unit)? = null

    init {
        MediaCodecHelper.initialize(context, /*glRenderer*/ "")
    }

    private fun mInitProcessor() {
        mRtspProcessor = RtspProcessor(
            onVideoDecoderCreateRequested = { videoMimeType, videoRotation, videoFrameQueue, videoDecoderListener, videoDecoderType, rtpStats ->
                VideoDecoderSurfaceThread(
                    surface,
                    videoMimeType,
                    currentViewWidth,
                    currentViewHeight,
                    videoRotation,
                    videoFrameQueue,
                    videoDecoderListener,
                    videoDecoderType,
                    rtpStats
                )
            },
            frameHandler = frameHandler,
        )

        // Настраиваем callback для синхронизации размеров
        mRtspProcessor?.statusListener = object : com.catanddev.rtsp.widget.RtspStatusListener {
            override fun onRtspVideoSizeChanged(width: Int, height: Int, rotation: Int) {
                onVideoSizeChanged?.invoke(width, height, rotation)
            }
        }
    }

    fun initRTSP(address: Uri, username:String, password :String): Boolean {
        if (mode == OPERATION_MODE.RTP) {
            Log.e(TAG,"Cannot initialize rtsp in rtp mode")
            return false
        }

        if (mRtspProcessor != null) {
            mRtspProcessor?.stop()
            mRtspProcessor?.stopDecoders()
            mRtspProcessor = null
        }

        mInitProcessor()

        mRtspProcessor!!.init(address, username, password)
        return isInitialized
    }

    fun initRtp(bindAddress: String, port: UShort): Boolean {
        if (mode != OPERATION_MODE.RTP) {
            Log.e(TAG, "Cannot initialize RTP in RTSP mode")
            return false
        }

        videoCodec = when (videoCodec) {
            VideoCodec.H265 -> {
                Log.i(TAG, "Initializing RTP with H.265 codec")
                VideoCodec.H265
            }
            else -> {
                Log.i(TAG, "Initializing RTP with H.264 codec")
                VideoCodec.H264
            }
        }

        if (mRtspProcessor != null) {
            mRtspProcessor?.stop()
            mRtspProcessor?.stopDecoders()
            mRtspProcessor = null
        }

        mInitProcessor()

        val mime = when (videoCodec) {
            VideoCodec.H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> MediaFormat.MIMETYPE_VIDEO_AVC
        }

        mRtspProcessor?.let {
            it.initRtp(bindAddress, port.toInt(), when (videoCodec) {
                VideoCodec.H265 -> RtspClient.VIDEO_CODEC_H265
                else -> RtspClient.VIDEO_CODEC_H264
            })
            it.videoMimeType = mime
        }

        return true
    }

    fun start(requestVideo: Boolean = true, requestAudio: Boolean = false, requestApplication: Boolean = false) {
        mRtspProcessor?.start(requestVideo, requestAudio, requestApplication)
        isActive = true
    }

    fun stop() {
        mRtspProcessor?.stop()
        mRtspProcessor?.stopDecoders()
        isActive = false
    }

    fun getStats(): RtpStats? {
        mRtspProcessor?.rtpStats?.calculateStats()
        return mRtspProcessor?.rtpStats
    }

    fun updateViewSize(newWidth:Int, newHeight:Int){
        if (
            (currentViewWidth == newWidth && currentViewHeight==newHeight) ||
            newWidth <= 0 ||
            newHeight <= 0
            )
            return

        currentViewWidth = newWidth
        currentViewHeight = newHeight
        mRtspProcessor?.updateDecoderSurface(surface)
    }

    fun destroy() {
        stop()
        mRtspProcessor = null
        surface.release()
    }
}
