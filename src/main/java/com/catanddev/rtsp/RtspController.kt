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
        val DEBUG = BuildConfig.DEBUG
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

            override fun onRtspFrameSizeChanged(width: Int, height: Int) {
                // Разрез из INFO_OUTPUT_FORMAT_CHANGED декодера — уже с учётом поворота (90/270).
                // Поворот декодер применяет сам при рендере в Surface, view ничего вращать не нужно.
                onVideoSizeChanged?.invoke(width, height, 0)
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
        isInitialized = true
        return isInitialized
    }

    fun initRtp(
        bindAddress: String,
        port: UShort,
        payloadType: Int,
        videoCodec: VideoCodec = this.videoCodec
    ): Boolean {
        if (mode != OPERATION_MODE.RTP) {
            Log.e(TAG, "Cannot initialize RTP in RTSP mode")
            return false
        }

        this.videoCodec = videoCodec
        Log.i(TAG, "Initializing RTP with ${if (videoCodec == VideoCodec.H265) "H.265" else "H.264"} codec")

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
        isInitialized = true
        return true
    }

    fun start(requestVideo: Boolean = true, requestAudio: Boolean = false, requestApplication: Boolean = false) {
        // Сначала запускаем декодеры (processor), затем RtpServer: к моменту прихода первых
        // пакетов декодер уже запущен, иначе кадры отбрасываются проверкой VideoDecodeThread.started.
        mRtspProcessor?.start(requestVideo, requestAudio, requestApplication)

        // Запускаем RtpServer для RTP режима
        if (mode == OPERATION_MODE.RTP) {
            mRtpServer?.start()
            Log.i(TAG, "RTP server started")
        }

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
        isInitialized = false
        // Surface'ом владеет StreamView (TextureView) — освобождать его здесь нельзя,
        // иначе убьём Surface, который переживает контроллер.
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
            onRtpVideoNalUnitReceived(data, offset, length, timestamp, 0, length > 0)
        }

        override fun onRtpVideoNalUnitReceived(
            data: ByteArray,
            offset: Int,
            length: Int,
            timestamp: Long,
            seq: Int,
            marker: Boolean
        ) {
            // Единый путь обработки как в RTSP-режиме: статистика, парсинг SPS (размер/поворот),
            // определение keyframe и постановка кадра в очередь декодера.
            mRtspProcessor?.onRtpVideoNalUnitReceived(data, offset, length, timestamp, seq, marker)
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
