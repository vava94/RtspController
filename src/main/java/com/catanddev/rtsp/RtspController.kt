package com.catanddev.rtsp


import android.content.Context
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

    var isInitialized = false
        private set

    var isActive = false
        private set


    var currentViewWidth = viewWidth
        private set
    var currentViewHeight = viewHeight
        private set

    private var mRtspProcessor: RtspProcessor? = null


    init {
        MediaCodecHelper.initialize(context, /*glRenderer*/ "")
    }


    private fun mInitProcessor() {

        mRtspProcessor = RtspProcessor(
            onVideoDecoderCreateRequested = { videoMimeType, videoRotation, videoFrameQueue, videoDecoderListener, videoDecoderType, rtpStats ->
                /**
                 * Создаем Surface из SurfaceTexture для передачи в декодер.
                 * Если Surface еще не готова, декодер создается без Surface (для получения метаданных).
                 */

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
        if (mode == OPERATION_MODE.RTP) {
            Log.e(TAG,"Cannot initialize rtsp in rtp mode")
            return false
        }
        TODO("Not yet implemented")
        return isInitialized
    }

    fun start(requestVideo: Boolean, requestAudio: Boolean, requestApplication: Boolean = false) {
        if (mode == OPERATION_MODE.RTSP) {
            mRtspProcessor?.start(requestVideo, requestAudio, requestApplication)
            isActive = true
        }
    }

    fun stop() {
        if (mode == OPERATION_MODE.RTSP) {
            mRtspProcessor?.stop()
            mRtspProcessor?.stopDecoders()
            isActive = false
        }
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

        TODO("Not Yet Implemented")
    }
}
