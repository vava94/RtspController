package com.catanddev.rtsp.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import com.catanddev.rtsp.FrameHandler
import com.catanddev.rtsp.R
import com.catanddev.rtsp.RtspClient
import com.catanddev.rtsp.codec.FrameQueue
import com.catanddev.rtsp.codec.MediaCodecHelper
import com.catanddev.rtsp.codec.VideoCodecType
import com.catanddev.rtsp.codec.VideoDecodeThread
import com.catanddev.rtsp.codec.VideoDecoderSurfaceThread
import com.catanddev.rtsp.server.RtpServer
import com.catanddev.rtsp.utils.VideoCodecUtils
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.max


/**
 * Виджет для воспроизведения RTSP/RTP видеопотоков.
 * 
 * Основные возможности:
 * - Поддержка RTSP клиента для подключения к RTSP-серверам
 * - Поддержка RTP сервера для приема видеопотока по UDP
 * - Аппаратное декодирование видео через MediaCodec
 * - Отображение видео через TextureView с возможностью скругления углов
 * - Автоматическое определение размеров видео из SPS/PPS данных
 * - Настройка типов масштабирования (CENTER_INSIDE, CENTER_CROP)
 * 
 * Использование:
 * 1. Для RTSP потока: init() -> start()
 * 2. Для RTP потока: initRtp() -> startRtpServer()
 */
@SuppressLint("ViewConstructor")
class RtspStreamView : TextureView {

    /**
     * Поверхность для передачи декодированного видео в MediaCodec.
     * Создается из SurfaceTexture, который предоставляется TextureView.
     */
    private var surface: Surface? = null

    /**
     * Handler для выполнения кода в основном потоке (UI thread).
     * Используется для обновления UI и уведомления слушателей о событиях.
     */
    private val uiHandler = Handler(Looper.getMainLooper())

    // Поля для отображения состояния "NO SIGNAL"
    /**
     * Время получения последнего видеокадра (в миллисекундах).
     * Используется для обнаружения отсутствия сигнала.
     */
    private var lastFrameTime: Long = 0
    
    /**
     * Handler для отложенных операций.
     * Используется, например, для задержки при инициализации Surface.
     */
    private val handler = Handler(Looper.getMainLooper())
    
    /**
     * Флаг, указывающий, что состояние "без сигнала" уже было уведомлено.
     * Предотвращает повторные уведомления о потере соединения.
     */
    private var noSignalEmitted = false

    /**
     * Ширина и высота видеопотока в пикселях.
     * Эти размеры берутся из SPS (Sequence Parameter Set) данных для H.264/H.265,
     * либо могут быть установлены вручную через adjustVideoSize().
     * 
     * Важно: для видеопотоков эти размеры могут отличаться от размеров, в которых
     * видео отображается на экране (из-за масштабирования и поворота).
     */
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    /**
     * Тип масштабирования видео внутри виджета.
     * 
     * CENTER_INSIDE: вписывает видео целиком в виджет, сохраняя пропорции.
     *                Обычно сопровождается черными полосами.
     * CENTER_CROP:   заполняет весь виджет, обрезая лишнее по краям.
     *                Сохраняет пропорции, но часть видео может быть отсечена.
     */
    enum class ScaleType {
        CENTER_INSIDE,  // вписываем полностью, сохраняя пропорции
        CENTER_CROP     // заполняем всю область, обрезая лишнее
    }
    private var scaleType: ScaleType = ScaleType.CENTER_INSIDE

    /**
     * Флаг, указывающий, что размеры видео были успешно установлены.
     * Используется в onMeasure() для корректной обработки wrap_content.
     */
    private var hasVideoSize: Boolean = false

    /**
     * Текущий размер SurfaceTexture (ширина и высота в пикселях).
     * Используется при создании декодера для настройки его входных параметров.
     * По умолчанию установлены значения 1920x1080 (Full HD).
     */
    private var surfaceWidth = 1920
    private var surfaceHeight = 1080

    /**
     * Радиус скругления углов виджета в пикселях.
     * Изменение этого значения немедленно применяется к визуальному отображению.
     */
    var cornerRadius = 0f
        set(value) {
            if (field != value) {
                field = value
                applyCornerRadius()
            }
        }

    /**
     * Paint объект с маской для применения скругления углов.
     * Использует PorterDuff.Mode.DST_IN для "вырезания" углов текстуры.
     */
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    /**
     * Слушатель событий кадров видео.
     * Получает уведомления о поступлении новых кадров для обновления времени последнего кадра.
     */
    private var frameListener = object : FrameHandler {
        override fun onFrameReceived() {
            lastFrameTime = System.currentTimeMillis()
            /*if (rtspProcessor.statusListener != null && noSignalEmitted) {
                uiHandler.post { rtspProcessor.statusListener?.onRtspStatusConnected() }
                noSignalEmitted = false
            }*/
        }
    }

    /**
     * Основной процессор RTSP/RTP потока.
     * 
     * Отвечает за:
     * - Инициализацию и управление RTSP соединением
     * - Создание и управление видео-декодерами
     * - Обмен данными с RTP сервером
     * - Управление очередью кадров
     * 
     * @param onVideoDecoderCreateRequested Лямбда-функция для создания видео-декодера
     * @param frameListener Слушатель событий получения кадров
     */
    private var rtspProcessor = RtspProcessor(
        onVideoDecoderCreateRequested = { videoMimeType, videoRotation, videoFrameQueue, videoDecoderListener, videoDecoderType,rtpStats ->
            /**
             * Создаем Surface из SurfaceTexture для передачи в декодер.
             * Если Surface еще не готова, декодер создается без Surface (для получения метаданных).
             */
            val surface = surface ?: run {
                Log.w(TAG, "Surface is null, decoder will be created without surface")
                null
            }
            VideoDecoderSurfaceThread(
                surface,
                videoMimeType,
                surfaceWidth,
                surfaceHeight,
                videoRotation,
                videoFrameQueue,
                videoDecoderListener,
                videoDecoderType,
                rtpStats
            )

        },
        null
    )

    /**
     * Вращение видео (по умолчанию 0 градусов).
     * Значение может быть 0, 90, 180 или 270 градусов.
     * 
     * Свойство является публичным и может быть изменено во время выполнения.
     */
    var videoRotation: Int
        get() = rtspProcessor.videoRotation
        set(value) {
            if (rtspProcessor.videoRotation != value) {
                rtspProcessor.videoRotation = value
            }
        }

    /**
     * Тип используемого декодера видео.
     * 
     * Возможные значения:
     * - DecoderType.MEDIA_CODEC: стандартный Android MediaCodec
     * - DecoderType.MEDIA_CODEC_2: альтернативный MediaCodec
     * - DecoderType.SOFTWARE: программный декодер
     * 
     * Изменение типа декодера происходит немедленно.
     */
    var videoDecoderType: VideoDecodeThread.DecoderType
        get() = rtspProcessor.videoDecoderType
        set(value) { rtspProcessor.videoDecoderType = value }

    /**
     * Экспериментальная настройка параметров SPS для снижения задержки.
     * 
     * При включении, параметры SPS обновляются с настройками низкой задержки.
     * Может улучшить отзывчивость видеопотока, но совместимость не гарантируется.
     */
    var experimentalUpdateSpsFrameWithLowLatencyParams: Boolean
        get() = rtspProcessor.experimentalUpdateSpsFrameWithLowLatencyParams
        set(value) { rtspProcessor.experimentalUpdateSpsFrameWithLowLatencyParams = value }

    /**
     * Режим отладки (логирования) процессора RTSP.
     * 
     * При включении выводит подробную информацию о процессе декодирования,
     * обмена пакетами и состоянии соединения.
     */
    var debug = true
    /**
     * Поля для управления RTP сервером.
     */
    private var rtpServer: RtpServer? = null
    /**
     * Атомарный флаг для безопасной остановки RTP сервера.
     * Используется в отдельном потоке для сигнализации о необходимости завершения.
     */
    private val rtpExitFlag = AtomicBoolean(false)
    /**
     * Флаг, указывающий, что RTP сервер запущен и ожидает данные.
     */
    private var isRtpServerStarted = false

    /**
     * Приостанавливает видео-декодер без остановки RTP сервера.
     * 
     * Полезно при переключении между потоками или при необходимости
     * временно прекратить обработку видео, сохраняя UDP-сокет открытым.
     * 
     * Важно: RTP сервер продолжает принимать пакеты, но они не декодируются.
     */
    fun pauseDecoder() {
        Log.i(TAG, "pauseDecoder() - pausing decoder only, keeping server running")
        rtspProcessor.stopDecoders()
    }

    /**
     * Переподключает видео-декодер к уже запущенному RTP серверу.
     * 
     * Используется при изменении параметров декодера или при необходимости
     * пересоздать декодер без остановки RTP сервера.
     * 
     * Порядок действий:
     * 1. Проверяет, что RTP сервер запущен
     * 2. Обновляет Surface в декодере
     * 3. Перезапускает декодер
     * 
     * @throws IllegalStateException если RTP сервер не запущен
     */
    fun reconnectDecoder() {
        Log.i(TAG, "reconnectDecoder() - reconnecting decoder to running server")

        if (!isRtpServerStarted) {
            Log.w(TAG, "No RTP server running, cannot reconnect decoder")
            return
        }

        // Проверяем, что Surface существует
        surface?.let { surface ->
            // Обновляем Surface в декодере
            rtspProcessor.updateDecoderSurface(surface)
        } ?: run {
            Log.w(TAG, "Surface is null, cannot reconnect decoder")
            return
        }

        // Перезапускаем декодер
        rtspProcessor.start(requestVideo = true, requestAudio = false)
    }

    /**
     * Инициализация виджета при создании.
     * 
     * Устанавливает слушатель SurfaceTexture, который управляет жизненным циклом
     * Surface и协调ирует работу с декодером.
     */
    init {
        // Устанавливаем слушатель SurfaceTexture
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            /**
             * Вызывается, когда SurfaceTexture становится доступным.
             *
             * Порядок действий:
             * 1. Создаем Surface из SurfaceTexture
             * 2. Ждем стабилизации (100ms) для корректной инициализации
             * 3. Обновляем Surface в декодере
             * 4. Если RTP сервер запущен, запускаем декодер
             */
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "onSurfaceTextureAvailable() - SurfaceTexture available, size: ${width}x${height}")

                // Создаем Surface из SurfaceTexture
                surface = Surface(surfaceTexture)

                // Даем время Surface полностью инициализироваться
                handler.postDelayed({
                    // Обновляем Surface в декодере, если он существует
                    rtspProcessor.updateDecoderSurface(surface!!)
                    Log.i(TAG, "Surface created and ready for decoder")

                    // Если RTP сервер запущен, запускаем декодер
                    if (isRtpServerStarted) {
                        Log.i(TAG, "RTP server is running, starting decoder...")
                        rtspProcessor.start(requestVideo = true, requestAudio = false)
                    }
                }, 100) // 100ms задержка для стабилизации Surface
            }

            /**
             * Вызывается при изменении размера SurfaceTexture.
             *
             * Обновляет внутренние переменные surfaceWidth и surfaceHeight,
             * которые используются при создании видео-декодера.
             */
            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "onSurfaceTextureSizeChanged(size=${width}x${height})")
                surfaceWidth = width
                surfaceHeight = height

                // Обновляем Surface в декодере при изменении
                handler.post {
                    surface?.let { s ->
                        rtspProcessor.updateDecoderSurface(s)
                        Log.i(TAG, "Surface changed to ${width}x$height")
                    }
                }
            }

            /**
             * Вызывается при уничтожении SurfaceTexture.
             *
             * Порядок действий:
             * 1. Останавливаем только декодеры (НЕ RTP сервер!)
             * 2. Освобождаем Surface
             * 3. Сбрасываем ссылку на surface
             * 4. Возвращаем true, чтобы TextureView мог освободить SurfaceTexture
             */
            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                Log.i(TAG, "onSurfaceTextureDestroyed()")
                // Останавливаем только декодеры, НЕ RTP сервер
                rtspProcessor.stopDecoders()
                // Освобождаем Surface
                surface?.release()
                surface = null
                // Возвращаем true, чтобы TextureView мог освободить SurfaceTexture
                return true
            }

            /**
             * Вызывается при каждом обновлении SurfaceTexture.
             *
             * Используется для уведомления об обновлении текстуры.
             * Можно использовать для отслеживания рендеринга или выполнения
             * дополнительных операций при каждом кадре.
             */
            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                // Используется для уведомления об обновлении текстуры
                // Можно использовать для отслеживания рендеринга
            }
        }



    }

    // ========== Конструкторы ==========

    /**
     * Создает новый экземпляр RtspStreamView.
     */
    constructor(context: Context) : this(context, null)

    /**
     * Создает новый экземпляр RtspStreamView с атрибутами XML.
     */
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    /**
     * Создает новый экземпляр RtspStreamView с атрибутами и стилем.
     * 
     * Выполняет инициализацию виджета через initView().
     */
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initView(context, attrs, defStyleAttr)
    }

    /**
     * Инициализация виджета.
     * 
     * Выполняет следующие действия:
     * 1. Инициализирует MediaCodecHelper для аппаратного декодирования
     * 2. Считывает настройки скругления из XML-атрибутов
     * 3. Применяет скругление углов
     * 4. Устанавливает размеры видео по умолчанию (1920x1080)
     */
    private fun initView(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
        Log.i(TAG, "initView()")
        MediaCodecHelper.initialize(context, /*glRenderer*/ "")
        getCornerRadius(attrs)
        applyCornerRadius()
        adjustMaxVideoSize()
    }

    // ========== Публичные методы ==========

    /**
     * Установить размеры видео (ширина и высота в пикселях).
     * Вызывайте этот метод, когда получили SPS/PPS или любой другой источник размера.
     * @throws IllegalArgumentException если размеры некорректны
     */
    fun adjustMaxVideoSize() {

        // Для получения разрешения экрана (всего устройства) используйте:
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        var maxVideoWidth = 0
        var maxVideoHeight = 0
        // Ландшафтный
        if (screenWidth > screenHeight) {
            maxVideoWidth = screenWidth
            maxVideoHeight = (screenWidth * 0.5625).toInt()
        } else {
            maxVideoWidth  = screenHeight
            maxVideoHeight = (screenHeight * 0.5625).toInt()
        }
        if (maxVideoHeight > screenHeight) {
            maxVideoWidth = (maxVideoHeight / 0.5625).toInt()
        }

        videoWidth = maxVideoWidth
        videoHeight= maxVideoHeight
        hasVideoSize = true
        requestLayout()

    }

    /**
     * Получить текущий тип масштабирования.
     * 
     * @return текущий ScaleType (CENTER_INSIDE или CENTER_CROP)
     */
    fun getScaleType(): ScaleType = scaleType

    /**
     * Установить тип масштабирования видео.
     * 
     * @param type тип масштабирования (CENTER_INSIDE или CENTER_CROP)
     */
    fun setScaleType(type: ScaleType) {
        if (this.scaleType != type) {
            this.scaleType = type
            // Пересчитываем размеры при изменении типа масштабирования
            requestLayout()
        }
    }

    // ========== Переопределение onMeasure для поддержки wrap_content ==========

    /**
     * Вычисляет размеры виджета на основе размеров видео и типа масштабирования.
     * 
     * Поддерживает режим wrap_content: если размеры видео установлены,
     * виджет подстраивается под них с учетом масштабирования.
     * 
     * Логика работы:
     * 1. Проверяет, установлены ли размеры видео
     * 2. Если нет - использует размеры по умолчанию от родителя
     * 3. Если да - вычисляет желаемый размер с учетом:
     *    - Режима измерения (EXACTLY/AT_MOST/UNSPECIFIED)
     *    - Поворота видео (если 90/270 градусов, меняем местами ширину и высоту)
     *    - Типа масштабирования (CENTER_INSIDE или CENTER_CROP)
     * 4. Применяет resolveSize для учета ограничений родителя
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {

        if (!hasVideoSize || videoWidth <= 0 || videoHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        /**
         * Реальные размеры видео.
         * Если поворот 90 или 270 градусов, меняем ширину и высоту местами.
         */
        val (effW, effH) = if (videoRotation % 180 == 0) {
            videoWidth.toFloat() to videoHeight.toFloat()
        } else {
            videoHeight.toFloat() to videoWidth.toFloat()
        }

        /**
         * Желаемые размеры с учетом режима измерения.
         */
        val desiredWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize.toFloat() // Фиксированный размер от родителя
            MeasureSpec.AT_MOST -> min(widthSize.toFloat(), effW) // Ограничение сверху
            else -> effW // Используем размер видео
        }
        val desiredHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize.toFloat()
            MeasureSpec.AT_MOST -> min(heightSize.toFloat(), effH)
            else -> effH
        }

        /**
         * Коэффициенты масштабирования для ширины и высоты.
         */
        val scaleX = desiredWidth / effW
        val scaleY = desiredHeight / effH
        
        /**
         * Выбор коэффициента масштабирования в зависимости от типа масштабирования.
         */
        val scale = when (scaleType) {
            ScaleType.CENTER_INSIDE -> min(scaleX, scaleY)
            ScaleType.CENTER_CROP -> maxOf(scaleX, scaleY) // Заполняем, обрезаем лишнее
        }

        val finalWidth = (effW * scale).toInt()
        val finalHeight = (effH * scale).toInt()

        setMeasuredDimension(
            resolveSize(finalWidth, widthMeasureSpec),
            resolveSize(finalHeight, heightMeasureSpec)
        )
    }

    // ========== Методы для работы с RTSP потоком ==========

    /**
     * Инициализация RTSP клиента.
     * 
     * Подключается к RTSP серверу по указанному URI.
     * Поддерживает аутентификацию через username/password.
     * 
     * @param uri URI RTSP сервера (rtsp://host:port/path)
     * @param username имя пользователя для аутентификации (опционально)
     * @param password пароль для аутентификации (опционально)
     * @param userAgent строка User-Agent для HTTP-запросов (опционально)
     */
    fun init(uri: Uri, username: String?, password: String?, userAgent: String?) {
        Log.i(TAG, "init(uri='$uri', username='$username', password='$password', userAgent='$userAgent')")
        stopRtpServer() // Останавливаем RTP если был запущен
        rtspProcessor.init(uri, username, password, userAgent)
    }

    // ========== Методы для работы с RTP потоком ==========

    /**
     * Инициализация для работы с RTP потоком.
     * 
     * Подготовливает виджет к приему видеопотока по UDP протоколу.
     * Не запускает сервер - для этого нужно вызвать startRtpServer().
     * 
     * @param host IP-адрес или хост для прослушивания (по умолчанию "0.0.0.0" - все интерфейсы)
     * @param port UDP порт для прослушивания (по умолчанию 8554)
     * @param videoCodec тип кодека видео (H264 или H265, по умолчанию H264)
     * @param videoPayloadType payload type для видео пакетов (по умолчанию 96)
     * @param packetSize максимальный размер UDP пакета (по умолчанию 65507 байт)
     */
    fun initRtp(
        host: String = "0.0.0.0",
        port: Int = 8554,
        videoCodec: Int = RtspClient.VIDEO_CODEC_H264,
        videoPayloadType: Int = 96,
        packetSize: Int = 65507
    ) {
        Log.i(TAG, "initRtp(host='$host', port=$port, codec=$videoCodec, payloadType=$videoPayloadType)")

        // Останавливаем RTSP если был запущен
        rtspProcessor.stop()

        // Инициализируем процессор для RTP
        rtspProcessor.initRtp(host, port, videoCodec)

        // Конфигурируем RTP Сервер
        rtpServer = RtpServer(
            host = host,
            port = port,
            videoCodec = videoCodec,
            videoPayloadType = videoPayloadType,
            exitFlag = rtpExitFlag,
            listener = object : RtpServer.RtpServerListener {
                override fun onRtpServerStarting() {
                    Log.i(TAG, "onRtpServerStarting()")
                    uiHandler.post {
                        rtspProcessor.statusListener?.onRtspStatusConnecting()
                    }
                }

                override fun onRtpServerStarted() {
                    Log.i(TAG, "onRtpServerStarted() - RTP Server listening on $host:$port")
                    // Запускаем декодеры для RTP потока
                    uiHandler.post {
                        rtspProcessor.statusListener?.onRtspStatusConnected()
                    }
                }

                override fun onRtpVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
                    if (debug) Log.i(TAG, "onRtpVideoNalUnitReceived(length=$length, timestamp=$timestamp, isH265=${videoCodec == RtspClient.VIDEO_CODEC_H265})")

                    // Проверяем, запущен ли декодер
                    if (!VideoDecodeThread.started) {
                        Log.w(TAG, "Video decoder not started, starting decoder...")
                        // Запускаем декодер
                        rtspProcessor.start(requestVideo = true, requestAudio = false)
                        return
                    }

                    val isH265 = videoCodec == RtspClient.VIDEO_CODEC_H265
                    val isKeyframe = VideoCodecUtils.isAnyKeyFrame(data, offset, length, isH265)

                    val videoFrame = FrameQueue.VideoFrame(
                        if (isH265) VideoCodecType.H265 else VideoCodecType.H264,
                        isKeyframe,
                        data,
                        offset,
                        length,
                        timestamp,
                        capturedTimestampMs = System.currentTimeMillis()
                    )

                    if (debug) Log.d(TAG, "Pushing video frame to queue: size=$length, isKeyframe=$isKeyframe")

                    // Используем общую очередь кадров в rtspProcessor
                    rtspProcessor.getVideoFrameQueue().push(videoFrame)

                    // Уведомляем слушателей данных
                    rtspProcessor.dataListener?.onRtspDataVideoNalUnitReceived(data, offset, length, timestamp)
                }

                override fun onRtpAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
                    Log.d(TAG, "onRtpAudioSampleReceived(length=$length, timestamp=$timestamp)")
                    rtspProcessor.dataListener?.onRtspDataAudioSampleReceived(data, offset, length, timestamp)
                }

                override fun onRtpServerStopping() {
                    Log.i(TAG, "onRtpServerStopping()")
                    uiHandler.post {
                        rtspProcessor.statusListener?.onRtspStatusDisconnecting()
                    }
                }

                override fun onRtpServerStopped() {
                    Log.i(TAG, "onRtpServerStopped()")
                    rtspProcessor.stopDecoders()
                    uiHandler.post {
                        rtspProcessor.statusListener?.onRtspStatusDisconnected()
                    }
                }

                override fun onRtpServerFailed(message: String?) {
                    Log.e(TAG, "onRtpServerFailed(message='$message')")
                    uiHandler.post {
                        rtspProcessor.statusListener?.onRtspStatusFailed(message)
                    }
                }
            },
            debug = false, // Включаем дебаг логирование для RTP сервера
            packetSize = packetSize
        )
    }

    /**
     * Запуск RTP Сервера.
     * 
     * Начинает прослушивание UDP порта и ожидание видеопотока.
     * Если сервер еще не инициализирован, вызывается initRtp() с параметрами по умолчанию.
     * 
     * Порядок действий:
     * 1. Проверяет, не запущен ли уже сервер
     * 2. Инициализирует сервер, если необходимо
     * 3. Сбрасывает флаг остановки
     * 4. Запускает RTP сервер в отдельном потоке
     */
    fun startRtpServer() {
        Log.i(TAG, "startRtpServer() - isRtpServerStarted: $isRtpServerStarted")
        if (isRtpServerStarted) {
            Log.w(TAG, "RTP server already started")
            return
        }

        if (rtpServer == null) {
            // Если сервер не инициализирован, инициализируем с параметрами по умолчанию
            Log.w(TAG, "RTP server not initialized, initializing with default parameters")
            initRtp() // Инициализируем с параметрами по умолчанию
        }

        rtpExitFlag.set(false)
        isRtpServerStarted = true

        // Запускаем RTP сервер
        rtpServer?.start()
        Log.i(TAG, "RTP server started")
    }

    /**
     * Проверка запущен ли RTP Сервер.
     * 
     * @return true, если RTP сервер запущен и ожидает данные
     */
    fun isServerRunning(): Boolean {
        return isRtpServerStarted
    }

    /**
     * Остановка RTP Сервера.
     * 
     * Останавливает прослушивание UDP порта и освобождает ресурсы.
     * 
     * Порядок действий:
     * 1. Устанавливает флаг остановки
     * 2. Сбрасывает флаг запущенного сервера
     * 3. Останавливает RTP сервер
     * 4. Освобождает ссылку на сервер
     * 5. Останавливает процессор RTSP
     */
    fun stopRtpServer() {
        Log.i(TAG, "stopRtpServer()")
        rtpExitFlag.set(true)
        isRtpServerStarted = false
        rtpServer?.stop()
        rtpServer = null
        rtspProcessor.stop()
    }

    /**
     * Проверка запущен ли RTP Сервер.
     * 
     * @return true, если RTP сервер запущен и ожидает данные
     */
    fun isRtpServerStarted(): Boolean {
        return isRtpServerStarted
    }

    /**
     * Считывает радиус скругления углов из XML-атрибутов.
     * 
     * Использует typed array для получения значений из ресурсов стилей.
     * 
     * @param attrs атрибуты XML-разметки
     */
    private fun getCornerRadius(attrs: AttributeSet?) {
        context?.let { ctx ->
            val typedArray = ctx.obtainStyledAttributes(attrs, R.styleable.RtspStreamView)
            cornerRadius = typedArray.getDimension(R.styleable.RtspStreamView_corner_radius, 0f)
            typedArray.recycle()
        }
    }

    /**
     * Применяет скругление углов через ViewOutlineProvider.
     * 
     * Создает outline с закругленными углами и включает clipToOutline,
     * чтобы содержимое (видео) обрезалось по этим углам.
     * 
     * Поведение:
     * - Если cornerRadius > 0: создает roundRect outline и включает clip
     * - Если cornerRadius == 0: использует стандартный BACKGROUND outline
     */
    private fun applyCornerRadius() {
        if (cornerRadius > 0f) {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                }
            }
            clipToOutline = true
        } else {
            clipToOutline = false
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }

    /**
     * Запуск RTSP клиента.
     * 
     * Подключается к инициализированному RTSP серверу и запрашивает указанные треки.
     * 
     * @param requestVideo запрашивать видео-трек (по умолчанию true)
     * @param requestAudio запрашивать аудио-трек (по умолчанию false)
     * @param requestApplication запрашивать application-трек (по умолчанию false)
     * 
     * @see [RFC 4566 Section 5.14](https://datatracker.ietf.org/doc/html/rfc4566#section-5.14) - описаниe a= lines
     */
    fun start(requestVideo: Boolean, requestAudio: Boolean, requestApplication: Boolean = false) {
        Log.i(TAG, "start(requestVideo=$requestVideo, requestAudio=$requestAudio, requestApplication=$requestApplication)")
        rtspProcessor.start(requestVideo, requestAudio, requestApplication)
    }

    /**
     * Остановка RTSP клиента.
     * 
     * Отключается от RTSP сервера и останавливает все декодеры.
     * Также останавливает RTP сервер, если он запущен.
     */
    fun stop() {
        Log.i(TAG, "stop()")
        rtspProcessor.stop()
        stopRtpServer()
    }

    /**
     * Проверка запущен ли виджет.
     * 
     * Возвращает true, если запущен либо RTSP процессор, либо RTP сервер.
     * 
     * @return true, если виджет активен и обрабатывает поток
     */
    fun isStarted(): Boolean {
        return rtspProcessor.isStarted() || isRtpServerStarted
    }

    /**
     * Установка слушателя статуса соединения.
     * 
     * Получает уведомления о событиях: подключение,disconnect, ошибка и т.д.
     * 
     * @param listener слушатель событий статуса
     */
    fun setStatusListener(listener: RtspStatusListener?) {
        Log.i(TAG, "setStatusListener()")
        rtspProcessor.statusListener = listener
    }

    /**
     * Установка слушателя данных.
     * 
     * Получает уведомления о сырых данных (NAL-юниты, аудиосэмплы).
     * Полезно для анализа или перенаправления данных.
     * 
     * @param listener слушатель данных потока
     */
    fun setDataListener(listener: RtspDataListener?) {
        Log.i(TAG, "setDataListener()")
        rtspProcessor.dataListener = listener
    }


    /**
     * Runnable для проверки тайм-аута получения кадров.
     * Используется для обнаружения состояния "без сигнала".
     */
    private val checkFrameTimeout = Runnable {
        // Принудительно перерисовываем view для проверки состояния кадра
        invalidate()
    }

    companion object {
        /**
         * Тег для логирования.
         * Используется всеми компонентами класса для унифицированного вывода логов.
         */
        private val TAG: String = RtspStreamView::class.java.simpleName
    }
}

/**
 * Потоковая функция для преобразования SPS данных в строку отладки.
 * 
 * Возвращает строку с основными параметрами последовательности видео:
 * - размеры изображения (ширина, высота)
 * - профиль и уровень кодека
 * - параметры кадров и буферов
 * 
 * Используется для логирования и отладки декодера.
 */
@OptIn(UnstableApi::class)
fun NalUnitUtil.SpsData.spsDataToString(): String {
    return "" +
        "width=${this.width}, " +
        "height=${this.height}, " +
        "profile_idc=${this.profileIdc}, " +
        "constraint_set_flags=${this.constraintsFlagsAndReservedZero2Bits}, " +
        "level_idc=${this.levelIdc}, " +
        "max_num_ref_frames=${this.maxNumRefFrames}, " +
        "frame_mbs_only_flag=${this.frameMbsOnlyFlag}, " +
        "log2_max_frame_num=${this.frameNumLength}, " +
        "pic_order_cnt_type=${this.picOrderCountType}, " +
        "log2_max_pic_order_cnt_lsb=${this.picOrderCntLsbLength}, " +
        "delta_pic_order_always_zero_flag=${this.deltaPicOrderAlwaysZeroFlag}, " +
        "max_reorder_frames=${this.maxNumReorderFrames}"
}

/**
 * Преобразует массив байтов в строку hex-символов.
 * 
 * Используется для отладочного вывода бинарных данных.
 * 
 * @param offset начальный смещение в массиве
 * @param maxLength максимальное количество байт для отображения
 * @return строка с hex-представлением данных (например: "DE AD BE EF ")
 */
fun ByteArray.toHexString(offset: Int, maxLength: Int): String {
    val length = min(maxLength, size - offset)
    return sliceArray(offset until (offset + length))
        .joinToString(separator = "") { byte ->
            "%02x ".format(byte).uppercase()
        }
}