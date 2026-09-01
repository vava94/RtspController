package com.catanddev.rtsp


import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.view.Surface
import com.catanddev.rtsp.codec.MediaCodecHelper
import com.catanddev.rtsp.codec.VideoDecoderSurfaceThread
import com.catanddev.rtsp.server.RtpServer
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
    private var mRtpServer: RtpServer? = null

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

    fun initRtp(bindAddress: String, port: UShort, payloadType: Int): Boolean {
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

        // Создаем и настраиваем RtpServer
        mRtpServer = RtpServer(
            host = bindAddress,
            port = port.toInt(),
            videoCodec = when (videoCodec) {
                VideoCodec.H265 -> RtspClient.VIDEO_CODEC_H265
                else -> RtspClient.VIDEO_CODEC_H264
            },
            videoPayloadType = payloadType,
            listener = rtpServerListener,
            rtspProcessor = mRtspProcessor
        )

        Log.i(TAG, "RTP server initialized on $bindAddress:$port")
        return true
    }

    fun start(requestVideo: Boolean = true, requestAudio: Boolean = false, requestApplication: Boolean = false) {
        // Запускаем RtpServer для RTP режима
        if (mode == OPERATION_MODE.RTP) {
            mRtpServer?.start()
            Log.i(TAG, "RTP server started")
        }

        mRtspProcessor?.start(requestVideo, requestAudio, requestApplication)
        isActive = true
    }

    fun stop() {
        // Останавливаем RtpServer для RTP режима
        mRtpServer?.stop()
        Log.i(TAG, "RTP server stopped")

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
        mRtpServer = null
        mRtspProcessor = null
        surface.release()
    }

    /**
     * Listener для RtpServer - передает полученные данные в RtspProcessor
     */
    private val rtpServerListener = object : RtpServer.RtpServerListener {
        override fun onRtpServerStarting() {
            Log.i(TAG, "RTP server starting...")
        }

        override fun onRtpServerStarted() {
            Log.i(TAG, "RTP server started successfully")
        }

        override fun onRtpVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
            mRtspProcessor?.let { processor ->
                // Передаем NAL юнит в процессор для декодирования
                val isH265 = processor.videoMimeType == MediaFormat.MIMETYPE_VIDEO_HEVC
                processor.onRtpPacketReceived(data, length, timestamp, 0, length > 0)

                // Push NAL unit to video frame queue for decoding
                val isKeyframe = com.catanddev.rtsp.utils.VideoCodecUtils.isAnyKeyFrame(data, offset, length, isH265)
                processor.videoFrameQueue.push(
                    com.catanddev.rtsp.codec.FrameQueue.VideoFrame(
                        if (isH265) com.catanddev.rtsp.codec.VideoCodecType.H265
                        else com.catanddev.rtsp.codec.VideoCodecType.H264,
                        isKeyframe,
                        data,
                        offset,
                        length,
                        timestamp,
                        capturedTimestampMs = System.currentTimeMillis()
                    )
                )
            }
        }

        override fun onRtpAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
            Log.d(TAG, "RTP audio sample received: $length bytes")
        }

        override fun onRtpServerStopping() {
            Log.i(TAG, "RTP server stopping...")
        }

        override fun onRtpServerStopped() {
            Log.i(TAG, "RTP server stopped")
        }

        override fun onRtpServerFailed(message: String?) {
            Log.e(TAG, "RTP server failed: $message")
        }
    }
}
