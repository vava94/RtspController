package com.catanddev.rtsp


import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.util.Pair
import com.catanddev.rtsp.parser.AacParser
import com.catanddev.rtsp.parser.RtpH264Parser
import com.catanddev.rtsp.parser.RtpH265Parser
import com.catanddev.rtsp.parser.RtpHeaderParser
import com.catanddev.rtsp.parser.RtpParser
import com.catanddev.rtsp.utils.NetUtils
import com.catanddev.rtsp.utils.VideoCodecUtils
import com.catanddev.rtsp.utils.VideoCodecUtils.getNalUnitType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Serial
import java.math.BigInteger
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


class RtspClient private constructor(builder: Builder) {
    interface RtspClientListener {
        fun onRtspConnecting()
        fun onRtspConnected(sdpInfo: SdpInfo)
        fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long, seq: Int, marker: Boolean)
        fun onRtspAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long)
        fun onRtspApplicationDataReceived(
            data: ByteArray,
            offset: Int,
            length: Int,
            timestamp: Long
        )

        fun onRtspDisconnecting()
        fun onRtspDisconnected()
        fun onRtspFailedUnauthorized()
        fun onRtspFailed(message: String?)
    }

    fun interface RtspClientKeepAliveListener {
        fun onRtspKeepAliveRequested()
    }

    class SdpInfo {
        /**
         * Session name (RFC 2327). In most cases RTSP server name.
         */
        var sessionName: String? = null

        /**
         * Session description (RFC 2327).
         */
        var sessionDescription: String? = null
        var videoTrack: VideoTrack? = null
        var audioTrack: AudioTrack? = null
        var applicationTrack: ApplicationTrack? = null
    }

    abstract class Track {
        var request: String = ""
        var payloadType: Int = 0

        override fun toString(): String {
            return "Track{request='$request', payloadType=$payloadType}"
        }
    }

    class VideoTrack : Track() {
        var videoCodec: Int = VIDEO_CODEC_H264
        var sps: ByteArray? = null // Both H.264 and H.265
        var pps: ByteArray? = null // Both H.264 and H.265
        var vps: ByteArray? = null // H.265 only
    }

    class AudioTrack : Track() {
        var audioCodec: Int = AUDIO_CODEC_UNKNOWN
        var sampleRateHz: Int = 0 // 16000, 8000
        var channels: Int = 0 // 1 - mono, 2 - stereo
        var mode: String = "" // AAC-lbr, AAC-hbr
        var config: ByteArray? = null // config=1210fff15081ffdffc
    }

    class ApplicationTrack : Track(){}

    private class UnauthorizedException : IOException("Unauthorized")

    private object NoResponseHeadersException : IOException() {

        @Serial
        private const val serialVersionUID = 1L
    }

    private val rtspSocket = builder.rtspSocket
    private var uriRtsp: String
    private val exitFlag: AtomicBoolean
    private val listener: RtspClientListener

    //  private boolean sendOptionsCommand;
    private val requestVideo: Boolean
    private val requestAudio: Boolean
    private val requestApplication: Boolean
    private val debug: Boolean
    private val username: String?
    private val password: String?
    private val userAgent: String?

    init {
        uriRtsp = builder.uriRtsp
        exitFlag = builder.exitFlag
        listener = builder.listener
        //      sendOptionsCommand = builder.sendOptionsCommand;
        requestVideo = builder.requestVideo
        requestAudio = builder.requestAudio
        requestApplication = builder.requestApplication
        username = builder.username
        password = builder.password
        debug = builder.debug
        userAgent = builder.userAgent
    }

    fun execute() {
        if (DEBUG) Log.v(TAG, "execute()")
        listener.onRtspConnecting()
        try {
            // Буферизуем чтение: readLine/readUntilBytesFound/readData читают мелкими порциями,
            // без буфера это лишние системные вызовы на каждый байт/пакет.
            val inputStream = BufferedInputStream(rtspSocket.getInputStream(), 16 * 1024)
            val outputStream: OutputStream =
                if (debug) LoggerOutputStream(rtspSocket.getOutputStream()) else BufferedOutputStream(
                    rtspSocket.getOutputStream()
                )

            var sdpInfo = SdpInfo()
            val cSeq = AtomicInteger(0)
            var headers: ArrayList<Pair<String, String>>
            var status: Int

            var authToken: String? = null
            var digestRealmNonce: Pair<String, String>? = null


            checkExitFlag(exitFlag)

            sendOptionsCommand(outputStream, uriRtsp, cSeq.addAndGet(1), userAgent, null)
            status = readResponseStatusCode(inputStream)
            headers = readResponseHeaders(inputStream)
            dumpHeaders(headers)
            // Try once again with credentials
            if (status == 401) {
                digestRealmNonce = getHeaderWwwAuthenticateDigestRealmAndNonce(headers)
                if (digestRealmNonce == null) {
                    val basicRealm = getHeaderWwwAuthenticateBasicRealm(headers)
                    if (TextUtils.isEmpty(basicRealm)) {
                        throw IOException("Unknown authentication type")
                    }
                    // Basic auth
                    authToken = getBasicAuthHeader(username, password)
                } else {
                    // Digest auth
                    authToken = getDigestAuthHeader(
                        username,
                        password,
                        "OPTIONS",
                        uriRtsp,
                        digestRealmNonce.first,
                        digestRealmNonce.second
                    )
                }
                checkExitFlag(exitFlag)
                sendOptionsCommand(outputStream, uriRtsp, cSeq.addAndGet(1), userAgent, authToken)
                status = readResponseStatusCode(inputStream)
                headers = readResponseHeaders(inputStream)
                dumpHeaders(headers)
            }
            if (DEBUG) Log.i(
                TAG,
                "OPTIONS status: $status"
            )
            checkStatusCode(status)
            val capabilities = getSupportedCapabilities(headers)

            checkExitFlag(exitFlag)

            sendDescribeCommand(outputStream, uriRtsp, cSeq.addAndGet(1), userAgent, authToken)
            status = readResponseStatusCode(inputStream)
            headers = readResponseHeaders(inputStream)
            dumpHeaders(headers)
            // Try once again with credentials. OPTIONS command can be accepted without authentication.
            if (status == 401) {
                digestRealmNonce = getHeaderWwwAuthenticateDigestRealmAndNonce(headers)
                if (digestRealmNonce == null) {
                    val basicRealm = getHeaderWwwAuthenticateBasicRealm(headers)
                    if (TextUtils.isEmpty(basicRealm)) {
                        throw IOException("Unknown authentication type")
                    }
                    // Basic auth
                    authToken = getBasicAuthHeader(username, password)
                } else {
                    // Digest auth
                    authToken = getDigestAuthHeader(
                        username,
                        password,
                        "DESCRIBE",
                        uriRtsp,
                        digestRealmNonce.first,
                        digestRealmNonce.second
                    )
                }
                checkExitFlag(exitFlag)

                sendDescribeCommand(outputStream, uriRtsp, cSeq.addAndGet(1), userAgent, authToken)
                status = readResponseStatusCode(inputStream)
                headers = readResponseHeaders(inputStream)
                dumpHeaders(headers)
            }
            if (DEBUG) Log.i(
                TAG,
                "DESCRIBE status: $status"
            )
            checkStatusCode(status)

            val contentBaseUri = getHeaderContentBase(headers)
            if (contentBaseUri != null) {
                if (debug) Log.i(
                    TAG_DEBUG,
                    "RTSP URI changed to '$uriRtsp'"
                )
                uriRtsp = contentBaseUri
            }

            val contentLength = getHeaderContentLength(headers)
            if (contentLength > 0) {
                val content = readContentAsText(inputStream, contentLength)
                if (debug) Log.i(TAG_DEBUG, "" + content)
                try {
                    val params = getDescribeParams(content)
                    sdpInfo = getSdpInfoFromDescribeParams(params)
                    if (!requestVideo) sdpInfo.videoTrack = null
                    if (!requestAudio) sdpInfo.audioTrack = null
                    if (!requestApplication) sdpInfo.applicationTrack = null
                    // Only AAC supported
                    if (sdpInfo.audioTrack != null && sdpInfo.audioTrack!!.audioCodec == AUDIO_CODEC_UNKNOWN) {
                        Log.e(
                            TAG_DEBUG,
                            "Unknown RTSP audio codec (" + sdpInfo.audioTrack!!.audioCodec + ") specified in SDP"
                        )
                        sdpInfo.audioTrack = null
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }


            // SETUP rtsp://10.0.1.78:8080/video/h264/trackID=1 RTSP/1.0
// Transport: RTP/AVP/TCP;unicast;interleaved=0-1
// CSeq: 3
// User-Agent: Lavf58.29.100

// RTSP/1.0 200 OK
// CSeq: 3
// Transport: RTP/AVP/TCP;unicast;interleaved=0-1
// Session: Mzk5MzY2MzUwMTg3NTc2Mzc5NQ;timeout=30
            var session: String? = null
            var sessionTimeout = 0
            for (i in 0..2) {
                // 0 - video track, 1 - audio track, 2 - application track
                checkExitFlag(exitFlag)

                val track = when (i) {
                    0 -> if (requestVideo) sdpInfo.videoTrack else null
                    1 -> if (requestAudio) sdpInfo.audioTrack else null
                    else -> if (requestApplication) sdpInfo.applicationTrack else null
                }
                if (track != null) {
                    val uriRtspSetup = getUriForSetup(uriRtsp, track)
                    if (uriRtspSetup == null) {
                        Log.e(TAG, "Failed to get RTSP URI for SETUP")
                        continue
                    }
                    if (digestRealmNonce != null) authToken = getDigestAuthHeader(
                        username,
                        password,
                        "SETUP",
                        uriRtspSetup,
                        digestRealmNonce.first,
                        digestRealmNonce.second
                    )
                    sendSetupCommand(
                        outputStream,
                        uriRtspSetup,
                        cSeq.addAndGet(1),
                        userAgent,
                        authToken,
                        session,
                        (if (i == 0) "0-1" /*video*/ else "2-3" /*audio*/)
                    )
                    status = readResponseStatusCode(inputStream)
                    if (DEBUG) Log.i(
                        TAG,
                        "SETUP status: $status"
                    )
                    checkStatusCode(status)
                    headers = readResponseHeaders(inputStream)
                    dumpHeaders(headers)
                    session = getHeader(headers, "Session")
                    if (!TextUtils.isEmpty(session)) {
                        // ODgyODg3MjQ1MDczODk3NDk4Nw;timeout=30
                        var params = TextUtils.split(session, ";")
                        session = params[0]
                        // Getting session timeout
                        if (params.size > 1) {
                            params = TextUtils.split(params[1], "=")
                            if (params.size > 1) {
                                try {
                                    sessionTimeout = params[1].toInt()
                                } catch (e: NumberFormatException) {
                                    Log.e(TAG, "Failed to parse RTSP session timeout: $e")
                                }
                            }
                        }
                    }
                    if (DEBUG) Log.d(
                        TAG,
                        "SETUP session: $session, timeout: $sessionTimeout"
                    )
                    if (TextUtils.isEmpty(session)) throw IOException("Failed to get RTSP session")
                }
            }

            if (TextUtils.isEmpty(session)) throw IOException("Failed to get any media track")

// PLAY rtsp://10.0.1.78:8080/video/h264 RTSP/1.0
// Range: npt=0.000-
// CSeq: 5
// User-Agent: Lavf58.29.100
// Session: Mzk5MzY2MzUwMTg3NTc2Mzc5NQ

// RTSP/1.0 200 OK
// CSeq: 5
// RTP-Info: url=/video/h264;seq=56
// Session: Mzk5MzY2MzUwMTg3NTc2Mzc5NQ;timeout=30
            checkExitFlag(exitFlag)

            if (digestRealmNonce != null) authToken = getDigestAuthHeader(
                username,
                password,
                "PLAY",
                uriRtsp,  /*?*/
                digestRealmNonce.first,
                digestRealmNonce.second
            )
            sendPlayCommand(
                outputStream, uriRtsp, cSeq.addAndGet(1), userAgent, authToken,
                session!!
            )
            status = readResponseStatusCode(inputStream)
            if (DEBUG) Log.i(
                TAG,
                "PLAY status: $status"
            )
            checkStatusCode(status)
            headers = readResponseHeaders(inputStream)
            dumpHeaders(headers)

            listener.onRtspConnected(sdpInfo)

            if (sdpInfo.videoTrack != null || sdpInfo.audioTrack != null || sdpInfo.applicationTrack != null) {
                if (digestRealmNonce != null) authToken = getDigestAuthHeader(
                    username,
                    password,
                    if (hasCapability(
                            RTSP_CAPABILITY_GET_PARAMETER, capabilities
                        )
                    ) "GET_PARAMETER" else "OPTIONS",
                    uriRtsp,
                    digestRealmNonce.first,
                    digestRealmNonce.second
                )
                val authTokenFinal = authToken
                val sessionFinal = session
                val keepAliveListener = RtspClientKeepAliveListener {
                    try {
                        //GET_PARAMETER rtsp://10.0.1.155:554/cam/realmonitor?channel=1&subtype=1/ RTSP/1.0
                        //CSeq: 6
                        //User-Agent: Lavf58.45.100
                        //Session: 4066342621205
                        //Authorization: Digest username="admin", realm="Login to cam", nonce="8fb58500489d60f99a40b43f3c8574ef", uri="rtsp://10.0.1.155:554/cam/realmonitor?channel=1&subtype=1/", response="692a26124a1ee9562135785ace33a23b"

                        //RTSP/1.0 200 OK
                        //CSeq: 6
                        //Session: 4066342621205

                        if (debug) Log.d(TAG_DEBUG, "Sending keep-alive")
                        if (hasCapability(
                                RTSP_CAPABILITY_GET_PARAMETER,
                                capabilities
                            )
                        ) sendGetParameterCommand(
                            outputStream,
                            uriRtsp,
                            cSeq.addAndGet(1),
                            userAgent,
                            sessionFinal,
                            authTokenFinal
                        )
                        else sendOptionsCommand(
                            outputStream,
                            uriRtsp,
                            cSeq.addAndGet(1),
                            userAgent,
                            authTokenFinal
                        )

                        // Do not read response right now, since it may contain unread RTP frames.
                        // RtpHeader.searchForNextRtpHeader will handle that.
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }

                // Blocking call unless exitFlag set to true, thread.interrupt() called or connection closed.
                try {
                    readRtpData(
                        inputStream,
                        sdpInfo,
                        exitFlag,
                        listener,
                        sessionTimeout / 2 * 1000,
                        keepAliveListener
                    )
                }
                catch (e: IOException){
                    e.printStackTrace()
                }
                finally {
                    // Cleanup resources on server side
                    if (hasCapability(RTSP_CAPABILITY_TEARDOWN, capabilities)) {
                        if (digestRealmNonce != null) authToken = getDigestAuthHeader(
                            username,
                            password,
                            "TEARDOWN",
                            uriRtsp,
                            digestRealmNonce.first,
                            digestRealmNonce.second
                        )
                        sendTeardownCommand(
                            outputStream,
                            uriRtsp,
                            cSeq.addAndGet(1),
                            userAgent,
                            authToken,
                            sessionFinal
                        )
                    }
                }
            } else {
                listener.onRtspFailed("No tracks found. RTSP server issue.")
            }

            listener.onRtspDisconnecting()
            listener.onRtspDisconnected()
        } catch (e: UnauthorizedException) {
            e.printStackTrace()
            listener.onRtspFailedUnauthorized()
        } catch (_: InterruptedException) {
            // Thread interrupted. Expected behavior.
            listener.onRtspDisconnecting()
            listener.onRtspDisconnected()
        } catch (e: Exception) {
            e.printStackTrace()
            listener.onRtspFailed(e.message)
        }
        try {
            rtspSocket.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @Throws(IOException::class)
    private fun readResponseStatusCode(inputStream: InputStream): Int {
//        String line = readLine(inputStream);
//        if (debug)
//            Log.d(TAG_DEBUG, "" + line);
        var line: String? = ""
        val rtspHeader = "RTSP/1.0 ".toByteArray()

        while(!exitFlag.get() && readUntilBytesFound(inputStream, rtspHeader)) {
            line = readLine(inputStream)
            if (line == null) break

            if (debug) Log.d(TAG_DEBUG, "" + line)
            //            int indexRtsp = line.indexOf("TSP/1.0 "); // 8 characters, 'R' already found
//            if (indexRtsp >= 0) {
            val indexCode = line.indexOf(' ')
            val code = line.substring(0, indexCode)
            try {
                val statusCode = code.toInt()
                //                if (debug)
//                    Log.d(TAG_DEBUG, "Status code: " + statusCode);
                return statusCode
            } catch (_: java.lang.NumberFormatException) {
                // Does not fulfill standard "RTSP/1.1 200 OK" token
                // Continue search for
            }
//            }
        }
        if (debug) Log.w(TAG_DEBUG, "Could not obtain status code")
        return -1
    }

    @Throws(IOException::class)
    private fun readResponseHeaders(inputStream: InputStream): ArrayList<Pair<String, String>> {
        val headers = ArrayList<Pair<String, String>>()
        var line: String? =  ""
        while (!exitFlag.get()){
            line = readLine(inputStream)
            if(line == null || line.isEmpty()) break

            if (debug) Log.d(TAG_DEBUG, "" + line)
            if (CRLF == line) {
                return headers
            } else {
                val pairs = line.split(":".toRegex(), limit = 2).toTypedArray()
                if (pairs.size == 2) {
                    headers.add(
                        Pair.create(
                            pairs[0].trim { it <= ' ' },
                            pairs[1].trim { it <= ' ' })
                    )
                }
            }
        }
        return headers
    }

    @Throws(IOException::class)
    private fun readUntilBytesFound(inputStream: InputStream, array: ByteArray): Boolean {
        val buffer = ByteArray(array.size)

        // Fill in buffer
        if (NetUtils.readData(
                inputStream,
                buffer,
                0,
                buffer.size
            ) != buffer.size
        ) return false // EOF


        while (!exitFlag.get()) {
            // Check if buffer is the same one
            if (memcmp(buffer, 0, array, 0, buffer.size)) {
                return true
            }
            // ABCDEF -> BCDEFF
            shiftLeftArray(buffer, buffer.size)
            // Read 1 byte into last buffer item
            if (NetUtils.readData(inputStream, buffer, buffer.size - 1, 1) != 1) {
                return false // EOF
            }
        }
        return false
    }

    //    private boolean readUntilByteFound(@NonNull InputStream inputStream, byte bt) throws IOException {
    //        byte[] buffer = new byte[1];
    //        int readBytes;
    //        while (!exitFlag.get()) {
    //            readBytes = inputStream.read(buffer, 0, 1);
    //            if (readBytes == -1) // EOF
    //                return false;
    //            if (readBytes == 1 && buffer[0] == bt) {
    //                return true;
    //            }
    //        }
    //        return false;
    //    }
    @Throws(IOException::class)
    private fun readLine(inputStream: InputStream): String? {
        val bufferLine = ByteArray(MAX_LINE_SIZE)
        var offset = 0
        var readBytes: Int
        do {
            // Didn't find "\r\n" within 4K bytes
            if (offset >= MAX_LINE_SIZE) {
                throw NoResponseHeadersException
            }

            // Read 1 byte
            readBytes = inputStream.read(bufferLine, offset, 1)
            if (readBytes == 1) {
                // Check for EOL
                // Some cameras like Linksys WVC200 do not send \n instead of \r\n
                if (offset > 0 &&  /*bufferLine[offset-1] == '\r' &&*/bufferLine[offset] == '\n'.code.toByte()) {
                    // Found empty EOL. End of header section
                    if (offset == 1) return "" //break;


                    // Found EOL. Add to array.
                    return String(bufferLine, 0, offset - 1)
                } else {
                    offset++
                }
            }
        } while (readBytes > 0 && !exitFlag.get())
        return null
    }

    class Builder(
        internal val rtspSocket: Socket,
        val uriRtsp: String,
        val exitFlag: AtomicBoolean,
        val listener: RtspClientListener
    ) {
        //      private boolean sendOptionsCommand = true;
        var requestVideo: Boolean = true
        var requestAudio: Boolean = true
        var requestApplication: Boolean = true
        var debug: Boolean = false
        var username: String? = null
        var password: String? = null
        var userAgent: String? = DEFAULT_USER_AGENT

        fun withDebug(debug: Boolean): Builder {
            this.debug = debug
            return this
        }

        fun withCredentials(username: String?, password: String?): Builder {
            this.username = username
            this.password = password
            return this
        }

        fun withUserAgent(userAgent: String?): Builder {
            this.userAgent = userAgent
            return this
        }

        //        @NonNull
        //        public Builder sendOptionsCommand(boolean sendOptionsCommand) {
        //            this.sendOptionsCommand = sendOptionsCommand;
        //            return this;
        //        }
        fun requestVideo(requestVideo: Boolean): Builder {
            this.requestVideo = requestVideo
            return this
        }

        fun requestAudio(requestAudio: Boolean): Builder {
            this.requestAudio = requestAudio
            return this
        }

        fun requestApplication(requestApplication: Boolean): Builder {
            this.requestApplication = requestApplication
            return this
        }

        fun build(): RtspClient {
            return RtspClient(this)
        }

        companion object {
            private const val DEFAULT_USER_AGENT = "Lavf58.29.100"
        }
    }

    companion object {
        private val TAG: String = RtspClient::class.java.simpleName
        val TAG_DEBUG: String = TAG + " DBG"
        private val DEBUG = BuildConfig.DEBUG
        private val EMPTY_ARRAY = ByteArray(0)

        const val RTSP_CAPABILITY_NONE: Int = 0
        const val RTSP_CAPABILITY_OPTIONS: Int = 1 shl 1
        const val RTSP_CAPABILITY_DESCRIBE: Int = 1 shl 2
        const val RTSP_CAPABILITY_ANNOUNCE: Int = 1 shl 3
        const val RTSP_CAPABILITY_SETUP: Int = 1 shl 4
        const val RTSP_CAPABILITY_PLAY: Int = 1 shl 5
        const val RTSP_CAPABILITY_RECORD: Int = 1 shl 6
        const val RTSP_CAPABILITY_PAUSE: Int = 1 shl 7
        const val RTSP_CAPABILITY_TEARDOWN: Int = 1 shl 8
        const val RTSP_CAPABILITY_SET_PARAMETER: Int = 1 shl 9
        const val RTSP_CAPABILITY_GET_PARAMETER: Int = 1 shl 10
        const val RTSP_CAPABILITY_REDIRECT: Int = 1 shl 11

        fun hasCapability(capability: Int, capabilitiesMask: Int): Boolean {
            return (capabilitiesMask and capability) != 0
        }

        const val VIDEO_CODEC_H264: Int = 0
        const val VIDEO_CODEC_H265: Int = 1

        const val AUDIO_CODEC_UNKNOWN: Int = -1
        const val AUDIO_CODEC_AAC: Int = 0
        const val AUDIO_CODEC_OPUS: Int = 1

        private fun getAudioCodecName(codec: Int): String {
            return when (codec) {
                AUDIO_CODEC_AAC -> "AAC"
                AUDIO_CODEC_OPUS -> "Opus"
                else -> "Unknown"
            }
        }

        private const val CRLF = "\r\n"

        // Size of buffer for reading from the connection
        private const val MAX_LINE_SIZE = 4098

        private fun getUriForSetup(uriRtsp: String, track: Track?): String? {
            if (DEBUG) Log.v(
                TAG,
                "getUriForSetup(uriRtsp='$uriRtsp', track=$track)"
            )
            if (track == null) return null
            if (track.request.isEmpty()) {
                // a=control:trackID=1 is missed
                Log.w(TAG, "Track request is empty. Skipping it.")
                track.request = uriRtsp
            }
            var uriRtspSetup = uriRtsp
            // Absolute URL
            if (track.request.startsWith("rtsp://") || track.request.startsWith("rtsps://")) {
                uriRtspSetup = track.request
                // Relative URL
            } else {
                if (!track.request.startsWith("/") && !uriRtspSetup.endsWith("/")) {
                    track.request = "/" + track.request
                }
                uriRtspSetup += track.request
            }
            return uriRtspSetup.trim { it <= ' ' }
        }

        @Throws(InterruptedException::class)
        private fun checkExitFlag(exitFlag: AtomicBoolean) {
            if (exitFlag.get()) throw InterruptedException()
        }

        @Throws(IOException::class)
        private fun checkStatusCode(code: Int) {
            when (code) {
                200 -> {}
                401 -> throw UnauthorizedException()
                else -> throw IOException("Invalid status code $code")
            }
        }

        @Throws(IOException::class)
        private fun readRtpData(
            inputStream: InputStream,
            sdpInfo: SdpInfo,
            exitFlag: AtomicBoolean,
            listener: RtspClientListener,
            keepAliveTimeout: Int,
            keepAliveListener: RtspClientKeepAliveListener
        ) {

            var data = EMPTY_ARRAY // Usually not bigger than MTU = 15KB
            val videoParser: RtpParser = when (sdpInfo.videoTrack?.videoCodec) {
                VIDEO_CODEC_H265 -> RtpH265Parser()
                else -> RtpH264Parser()
            }

            val audioParser: AacParser? = when (sdpInfo.audioTrack?.audioCodec) {
                AUDIO_CODEC_AAC -> AacParser(sdpInfo.audioTrack!!.mode)
                else -> null
            }

            var nalUnitSps = (if (sdpInfo.videoTrack != null) sdpInfo.videoTrack!!.sps else null) /* Возможны ошибки */
            var nalUnitPps = (if (sdpInfo.videoTrack != null) sdpInfo.videoTrack!!.pps else null) /* Возможны ошибки */
            var nalUnitSei = EMPTY_ARRAY
            var nalUnitAud = EMPTY_ARRAY
            var videoSeqNum = 0

            var keepAliveSent = System.currentTimeMillis()

            while (!exitFlag.get()) {

                val header = RtpHeaderParser.readHeader(inputStream)
                    ?: continue


                if (header.payloadSize > data.size) data = ByteArray(header.payloadSize)

                NetUtils.readData(inputStream, data, 0, header.payloadSize)

                // Check if keep-alive should be sent
                val l = System.currentTimeMillis()

                if (keepAliveTimeout > 0 && l - keepAliveSent > keepAliveTimeout) {
                    keepAliveSent = l
                    keepAliveListener.onRtspKeepAliveRequested()
                }

                // Video
                if (sdpInfo.videoTrack != null && header.payloadType == sdpInfo.videoTrack!!.payloadType) {

                    if (videoSeqNum > header.sequenceNumber) {
                        Log.w(
                        TAG,
                        "Invalid video seq num " + videoSeqNum + "/" + header.sequenceNumber
                        )
                    }

                    videoSeqNum = header.sequenceNumber
                    val nalUnit: ByteArray?

                    // If extendion bit set in header, skip extension data
                    if (header.extension == 1) {
                        val skipBytes = (data[2].toInt() shl 8 or data[3].toInt()) * 4 + 4
                        nalUnit = videoParser.processRtpPacketAndGetNalUnit(
                            data.copyOfRange(skipBytes, data.size),
                            header.payloadSize - skipBytes, header.marker == 1
                        )
                    } else {
                        nalUnit = videoParser.processRtpPacketAndGetNalUnit(
                            data,
                            header.payloadSize,
                            header.marker == 1
                        )
                    }

                    if (nalUnit != null) {
                        val isH265 = sdpInfo.videoTrack!!.videoCodec == VIDEO_CODEC_H265
                        val type = getNalUnitType(
                            nalUnit,
                            0,
                            nalUnit.size,
                            isH265
                        )

                        when (type) {

                            VideoCodecUtils.NAL_SPS -> {
                                nalUnitSps = nalUnit
                                // Looks like there is NAL_IDR_SLICE as well. Send it now.
                                if (nalUnit.size > VideoCodecUtils.MAX_NAL_SPS_SIZE)
                                    listener.onRtspVideoNalUnitReceived(
                                        nalUnit,
                                        0,
                                        nalUnit.size,
                                        header.timestampMs,
                                        header.sequenceNumber,
                                        header.marker == 1
                                    )
                            }

                            VideoCodecUtils.NAL_PPS -> {
                                nalUnitPps = nalUnit
                                // Looks like there is NAL_IDR_SLICE as well. Send it now.
                                if (nalUnit.size > VideoCodecUtils.MAX_NAL_SPS_SIZE)
                                    listener.onRtspVideoNalUnitReceived(
                                        nalUnit,
                                        0,
                                        nalUnit.size,
                                        header.timestampMs,
                                        header.sequenceNumber,
                                        header.marker == 1
                                    )
                            }

                            VideoCodecUtils.NAL_AUD -> {
                                nalUnitAud = nalUnit
                            }

                            VideoCodecUtils.NAL_SEI -> {
                                nalUnitSei = nalUnit
                            }

                            VideoCodecUtils.NAL_IDR_SLICE -> {
                                // Combine IDR with SPS/PPS
                                if (nalUnitSps != null && nalUnitPps != null) {
                                    val nalUnitSpsPpsIdr =
                                        ByteArray(nalUnitAud.size + nalUnitSps.size + nalUnitPps.size + nalUnitSei.size + nalUnit.size)
                                    var offset = 0

                                    System.arraycopy(
                                        nalUnitSps,
                                        0,
                                        nalUnitSpsPpsIdr,
                                        offset,
                                        nalUnitSps.size
                                    )
                                    offset += nalUnitSps.size
                                    System.arraycopy(
                                        nalUnitPps,
                                        0,
                                        nalUnitSpsPpsIdr,
                                        offset,
                                        nalUnitPps.size
                                    )
                                    offset += nalUnitPps.size
                                    System.arraycopy(
                                        nalUnitAud,
                                        0,
                                        nalUnitSpsPpsIdr,
                                        offset,
                                        nalUnitAud.size
                                    )
                                    offset += nalUnitAud.size
                                    System.arraycopy(
                                        nalUnitSei,
                                        0,
                                        nalUnitSpsPpsIdr,
                                        offset,
                                        nalUnitSei.size
                                    )
                                    offset += nalUnitSei.size
                                    System.arraycopy(
                                        nalUnit,
                                        0,
                                        nalUnitSpsPpsIdr,
                                        offset,
                                        nalUnit.size
                                    )
                                    listener.onRtspVideoNalUnitReceived(
                                        nalUnitSpsPpsIdr,
                                        0,
                                        nalUnitSpsPpsIdr.size,
                                        header.timestampMs,
                                        header.sequenceNumber,
                                        header.marker == 1
                                    )

                                    // Send it only once
                                    nalUnitSps = null
                                    nalUnitPps = null
                                    nalUnitSei = EMPTY_ARRAY
                                    nalUnitAud = EMPTY_ARRAY
                                }
                            }
                            else -> {
                                if (nalUnitSei.isEmpty() && nalUnitAud.isEmpty())
                                    listener.onRtspVideoNalUnitReceived(nalUnit, 0, nalUnit.size, header.timestampMs, header.sequenceNumber, header.marker == 1)
                                else {
                                    val nalUnitAudSeiSlice = ByteArray(nalUnitAud.size + nalUnitSei.size + nalUnit.size)
                                    var offset = 0

                                    System.arraycopy(nalUnitAud, 0, nalUnitAudSeiSlice, offset, nalUnitAud.size)
                                    offset += nalUnitAud.size
                                    System.arraycopy(nalUnitSei, 0, nalUnitAudSeiSlice, offset, nalUnitSei.size)
                                    offset += nalUnitSei.size
                                    System.arraycopy(nalUnit, 0, nalUnitAudSeiSlice, offset, nalUnit.size)
                                    listener.onRtspVideoNalUnitReceived(
                                        nalUnitAudSeiSlice,
                                        0,
                                        nalUnitAudSeiSlice.size,
                                        header.timestampMs,
                                        header.sequenceNumber,
                                        header.marker == 1
                                    )
                                    nalUnitSei = EMPTY_ARRAY
                                    nalUnitAud = EMPTY_ARRAY

                                }
                            }
                        }
                    }


                }
                // Audio
                else if (sdpInfo.audioTrack != null && header.payloadType == sdpInfo.audioTrack!!.payloadType) {
                    if (audioParser != null) {
                        val sample =
                            audioParser.processRtpPacketAndGetSample(data, header.payloadSize)
                        if (sample != null) listener.onRtspAudioSampleReceived(
                            sample,
                            0,
                            sample.size,
                            header.timestampMs
                        )
                    }

                    // Application
                } else if (sdpInfo.applicationTrack != null && header.payloadType == sdpInfo.applicationTrack!!.payloadType) {
                    listener.onRtspApplicationDataReceived(
                        data,
                        0,
                        header.payloadSize,
                        header.timestampMs
                    )


                }
                // Unknown
                else {
                    // https://www.iana.org/assignments/rtp-parameters/rtp-parameters.xhtml
                    if (DEBUG && header.payloadType >= 96 && header.payloadType <= 127) Log.w(
                        TAG,
                        "Invalid RTP payload type " + header.payloadType
                    )
                }
            }
        }

        @Throws(IOException::class)
        private fun sendSimpleCommand(
            command: String,
            outputStream: OutputStream,
            request: String,
            cSeq: Int,
            userAgent: String?,
            session: String?,
            authToken: String?
        ) {
            outputStream.write(("$command $request RTSP/1.0$CRLF").toByteArray())
            if (authToken != null) outputStream.write(("Authorization: $authToken$CRLF").toByteArray())
            outputStream.write(("CSeq: $cSeq$CRLF").toByteArray())
            if (userAgent != null) outputStream.write(("User-Agent: $userAgent$CRLF").toByteArray())
            if (session != null) outputStream.write(("Session: $session$CRLF").toByteArray())
            outputStream.write(CRLF.toByteArray())
            outputStream.flush()
        }

        @Throws(IOException::class)
        private fun sendOptionsCommand(
            outputStream: OutputStream,
            request: String,
            cSeq: Int,
            userAgent: String?,
            authToken: String?
        ) {
            if (DEBUG) Log.v(
                TAG,
                "sendOptionsCommand(request=\"$request\", cSeq=$cSeq)"
            )
            sendSimpleCommand("OPTIONS", outputStream, request, cSeq, userAgent, null, authToken)
        }

        @Throws(IOException::class)
        private fun sendGetParameterCommand(
            outputStream: OutputStream,
            request: String,
            cSeq: Int,
            userAgent: String?,
            session: String?,
            authToken: String?
        ) {
            if (DEBUG) Log.v(
                TAG,
                "sendGetParameterCommand(request=\"$request\", cSeq=$cSeq)"
            )
            sendSimpleCommand(
                "GET_PARAMETER",
                outputStream,
                request,
                cSeq,
                userAgent,
                session,
                authToken
            )
        }

        @Throws(IOException::class)
        private fun sendDescribeCommand(
            outputStream: OutputStream,
            request: String,
            cSeq: Int,
            userAgent: String?,
            authToken: String?
        ) {
            if (DEBUG) Log.v(
                TAG,
                "sendDescribeCommand(request=\"$request\", cSeq=$cSeq)"
            )
            outputStream.write(("DESCRIBE $request RTSP/1.0$CRLF").toByteArray())
            outputStream.write(("Accept: application/sdp$CRLF").toByteArray())
            if (authToken != null) outputStream.write(("Authorization: $authToken$CRLF").toByteArray())
            outputStream.write(("CSeq: $cSeq$CRLF").toByteArray())
            if (userAgent != null) outputStream.write(("User-Agent: $userAgent$CRLF").toByteArray())
            outputStream.write(CRLF.toByteArray())
            outputStream.flush()
        }

        @Throws(IOException::class)
        private fun sendTeardownCommand(
            outputStream: OutputStream,
            request: String,
            cSeq: Int,
            userAgent: String?,
            authToken: String?,
            session: String?
        ) {
            if (DEBUG) Log.v(
                TAG,
                "sendTeardownCommand(request=\"$request\", cSeq=$cSeq)"
            )
            outputStream.write(("TEARDOWN $request RTSP/1.0$CRLF").toByteArray())
            if (authToken != null) outputStream.write(("Authorization: $authToken$CRLF").toByteArray())
            outputStream.write(("CSeq: $cSeq$CRLF").toByteArray())
            if (userAgent != null) outputStream.write(("User-Agent: $userAgent$CRLF").toByteArray())
            if (session != null) outputStream.write(("Session: $session$CRLF").toByteArray())
            outputStream.write(CRLF.toByteArray())
            outputStream.flush()
        }

        @Throws(IOException::class)
        private fun sendSetupCommand(
            outputStream: OutputStream,
            request: String,
            cSeq: Int,
            userAgent: String?,
            authToken: String?,
            session: String?,
            interleaved: String
        ) {
            if (DEBUG) Log.v(
                TAG,
                "sendSetupCommand(request=\"$request\", cSeq=$cSeq)"
            )
            outputStream.write(("SETUP $request RTSP/1.0$CRLF").toByteArray())
            outputStream.write(("Transport: RTP/AVP/TCP;unicast;interleaved=$interleaved$CRLF").toByteArray())
            if (authToken != null) outputStream.write(("Authorization: $authToken$CRLF").toByteArray())
            outputStream.write(("CSeq: $cSeq$CRLF").toByteArray())
            if (userAgent != null) outputStream.write(("User-Agent: $userAgent$CRLF").toByteArray())
            if (session != null) outputStream.write(("Session: $session$CRLF").toByteArray())
            outputStream.write(CRLF.toByteArray())
            outputStream.flush()
        }

        @Throws(IOException::class)
        private fun sendPlayCommand(
            outputStream: OutputStream,
            request: String,
            cSeq: Int,
            userAgent: String?,
            authToken: String?,
            session: String
        ) {
            if (DEBUG) Log.v(
                TAG,
                "sendPlayCommand(request=\"$request\", cSeq=$cSeq)"
            )
            outputStream.write(("PLAY $request RTSP/1.0$CRLF").toByteArray())
            outputStream.write(("Range: npt=0.000-$CRLF").toByteArray())
            if (authToken != null) outputStream.write(("Authorization: $authToken$CRLF").toByteArray())
            outputStream.write(("CSeq: $cSeq$CRLF").toByteArray())
            if (userAgent != null) outputStream.write(("User-Agent: $userAgent$CRLF").toByteArray())
            outputStream.write(("Session: $session$CRLF").toByteArray())
            outputStream.write(CRLF.toByteArray())
            outputStream.flush()
        }

        /**
         * Get a list of tracks from SDP. Usually contains video and audio track only.
         * @return array of 3 tracks. First is video track, second audio track, third application track.
         */
        private fun getTracksFromDescribeParams(params: List<Pair<String, String>>): Array<Track?> {
            val tracks = arrayOfNulls<Track>(3)
            var currentTrack: Track? = null
            for (param in params) {
                when (param.first) {
                    "m" -> {
                        // m=video 0 RTP/AVP 96
                        if (param.second.startsWith("video")) {
                            currentTrack = VideoTrack()
                            tracks[0] = currentTrack

                            // m=audio 0 RTP/AVP 97
                        } else if (param.second.startsWith("audio")) {
                            currentTrack = AudioTrack()
                            tracks[1] = currentTrack

                            // m=application 0 RTP/AVP 99
                            // a=rtpmap:99 com.my/90000
                        } else if (param.second.startsWith("application")) {
                            currentTrack = ApplicationTrack()
                            tracks[2] = currentTrack
                        } else if (param.second.startsWith("text")) {
                            Log.w(TAG, "Media track 'text' is not supported")
                        } else if (param.second.startsWith("message")) {
                            Log.w(TAG, "Media track 'message' is not supported")
                        } else {
                            currentTrack = null
                        }

                        if (currentTrack != null) {
                            // m=<media> <port>/<number of ports> <proto> <fmt> ...
                            val values = TextUtils.split(param.second, " ")
                            try {
                                currentTrack.payloadType =
                                    (if (values.size > 3) values[3].toInt() else -1)
                            } catch (_: Exception) {
                                currentTrack.payloadType = -1
                            }
                            if (currentTrack.payloadType == -1) Log.e(
                                TAG,
                                "Failed to get payload type from \"m=" + param.second + "\""
                            )
                        }
                    }

                    "a" ->                     // a=control:trackID=1
                        if (currentTrack != null) {
                            if (param.second.startsWith("control:")) {
                                currentTrack.request = param.second.substring(8)

                                // a=fmtp:96 packetization-mode=1; profile-level-id=4D4029; sprop-parameter-sets=Z01AKZpmBkCb8uAtQEBAQXpw,aO48gA==
                                // a=fmtp:97 streamtype=5; profile-level-id=15; mode=AAC-hbr; config=1408; sizeLength=13; indexLength=3; indexDeltaLength=3; profile=1; bitrate=32000;
                                // a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1408
                                // a=fmtp:96 streamtype=5; profile-level-id=14; mode=AAC-lbr; config=1388; sizeLength=6; indexLength=2; indexDeltaLength=2; constantDuration=1024; maxDisplacement=5
                                // a=fmtp:96 profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1210fff15081ffdffc
                                // a=fmtp:96
                            } else if (param.second.startsWith("fmtp:")) {
                                // Video
                                if (currentTrack is VideoTrack) {
                                    updateVideoTrackFromDescribeParam(
                                        tracks[0] as VideoTrack,
                                        param
                                    )
                                    // Audio
                                } else if (currentTrack is AudioTrack) {
                                    updateAudioTrackFromDescribeParam(
                                        tracks[1] as AudioTrack,
                                        param
                                    )
                                }

                                // a=rtpmap:96 H264/90000
                                // a=rtpmap:97 mpeg4-generic/16000/1
                                // a=rtpmap:97 MPEG4-GENERIC/16000
                                // a=rtpmap:97 G726-32/8000
                                // a=rtpmap:96 mpeg4-generic/44100/2
                            } else if (param.second.startsWith("rtpmap:")) {
                                // Video
                                var values = TextUtils.split(param.second, " ")
                                if (currentTrack is VideoTrack) {
                                    if (values.size > 1) {
                                        values = TextUtils.split(values[1], "/")
                                        if (values.size > 0) {
                                            when (values[0].lowercase(Locale.getDefault())) {
                                                "h264" -> (tracks[0] as VideoTrack).videoCodec =
                                                    VIDEO_CODEC_H264

                                                "h265" -> (tracks[0] as VideoTrack).videoCodec =
                                                    VIDEO_CODEC_H265

                                                else -> Log.w(
                                                    TAG,
                                                    "Unknown video codec \"" + values[0] + "\""
                                                )
                                            }
                                            Log.i(TAG, "Video: " + values[0])
                                        }
                                    }

                                    // Audio
                                }
                                else if (currentTrack is AudioTrack) {
                                    if (values.size > 1) {
                                        val track = (tracks[1] as AudioTrack)
                                        values = TextUtils.split(values[1], "/")
                                        if (values.size > 1) {
                                            when (values[0].lowercase(Locale.getDefault())) {
                                                "mpeg4-generic" -> track.audioCodec =
                                                    AUDIO_CODEC_AAC

                                                "opus" -> track.audioCodec = AUDIO_CODEC_OPUS
                                                else -> {
                                                    Log.w(
                                                        TAG,
                                                        "Unknown audio codec \"" + values[0] + "\""
                                                    )
                                                    track.audioCodec = AUDIO_CODEC_UNKNOWN
                                                }
                                            }
                                            track.sampleRateHz = values[1].toInt()
                                            // If no channels specified, use mono, e.g. "a=rtpmap:97 MPEG4-GENERIC/8000"
                                            track.channels =
                                                if (values.size > 2) values[2].toInt() else 1
                                            Log.i(
                                                TAG,
                                                "Audio: " + getAudioCodecName(track.audioCodec) + ", sample rate: " + track.sampleRateHz + " Hz, channels: " + track.channels
                                            )
                                        }
                                    }

                                    // Application
                                } else {
                                    // Do nothing
                                }
                            }
                        }
                }
            }
            return tracks
        }

        //v=0
        //o=- 1542237507365806 1542237507365806 IN IP4 10.0.1.111
        //s=Media Presentation
        //e=NONE
        //b=AS:50032
        //t=0 0
        //a=control:*
        //a=range:npt=0.000000-
        //m=video 0 RTP/AVP 96
        //c=IN IP4 0.0.0.0
        //b=AS:50000
        //a=framerate:25.0
        //a=transform:1.000000,0.000000,0.000000;0.000000,1.000000,0.000000;0.000000,0.000000,1.000000
        //a=control:trackID=1
        //a=rtpmap:96 H264/90000
        //a=fmtp:96 packetization-mode=1; profile-level-id=4D4029; sprop-parameter-sets=Z01AKZpmBkCb8uAtQEBAQXpw,aO48gA==
        //m=audio 0 RTP/AVP 97
        //c=IN IP4 0.0.0.0
        //b=AS:32
        //a=control:trackID=2
        //a=rtpmap:97 G726-32/8000
        // v=0
        // o=- 14190294250618174561 14190294250618174561 IN IP4 127.0.0.1
        // s=IP Webcam
        // c=IN IP4 0.0.0.0
        // t=0 0
        // a=range:npt=now-
        // a=control:*
        // m=video 0 RTP/AVP 96
        // a=rtpmap:96 H264/90000
        // a=control:h264
        // a=fmtp:96 packetization-mode=1;profile-level-id=42C028;sprop-parameter-sets=Z0LAKIyNQDwBEvLAPCIRqA==,aM48gA==;
        // a=cliprect:0,0,1920,1080
        // a=framerate:30.0
        // a=framesize:96 1080-1920
        // Pair first - name, e.g. "a"; second - value, e.g "cliprect:0,0,1920,1080"
        private fun getDescribeParams(text: String): List<Pair<String, String>> {
            val list = ArrayList<Pair<String, String>>()
            val params = TextUtils.split(text, "\r\n")
            for (param in params) {
                val i = param.indexOf('=')
                if (i > 0) {
                    val name = param.substring(0, i).trim { it <= ' ' }
                    val value = param.substring(i + 1)
                    list.add(Pair.create(name, value))
                }
            }
            return list
        }

        private fun getSdpInfoFromDescribeParams(params: List<Pair<String, String>>): SdpInfo {
            val sdpInfo = SdpInfo()

            val tracks = getTracksFromDescribeParams(params)
            sdpInfo.videoTrack = (tracks[0] as VideoTrack?)
            sdpInfo.audioTrack = (tracks[1] as AudioTrack?)
            sdpInfo.applicationTrack = (tracks[2] as ApplicationTrack?)

            for (param in params) {
                when (param.first) {
                    "s" -> sdpInfo.sessionName = param.second
                    "i" -> sdpInfo.sessionDescription = param.second
                }
            }
            return sdpInfo
        }

        // a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1408
        private fun getSdpAParams(param: Pair<String, String>): List<Pair<String, String>>? {
            if (param.first == "a" && param.second.startsWith("fmtp:") && param.second.length > 8) { //
                val value = param.second.substring(8)
                    .trim { it <= ' ' }  // fmtp can be '96' (2 chars) and '127' (3 chars)
                val paramsA = TextUtils.split(value, ";")
                // streamtype=5
                // profile-level-id=1
                // mode=AAC-hbr
                val retParams = ArrayList<Pair<String, String>>()
                for (paramA in paramsA) {
                    var paramA = paramA
                    paramA = paramA.trim { it <= ' ' }
                    // sprop-parameter-sets=Z0LAKIyNQDwBEvLAPCIRqA==,aM48gA==
                    val i = paramA.indexOf("=")
                    if (i != -1) retParams.add(
                        Pair.create(
                            paramA.substring(0, i),
                            paramA.substring(i + 1)
                        )
                    )
                }
                return retParams
            } else {
                Log.w(TAG, "Not a valid fmtp")
            }
            return null
        }

        private fun getNalUnitFromSprop(nalBase64: String): ByteArray {
            val nal = Base64.decode(nalBase64, Base64.NO_WRAP)
            val nalWithStart = ByteArray(nal.size + 4)
            // Add 00 00 00 01 NAL unit header
            nalWithStart[0] = 0
            nalWithStart[1] = 0
            nalWithStart[2] = 0
            nalWithStart[3] = 1
            System.arraycopy(nal, 0, nalWithStart, 4, nal.size)
            return nalWithStart
        }

        private fun updateVideoTrackFromDescribeParam(
            videoTrack: VideoTrack,
            param: Pair<String, String>
        ) {
            // a=fmtp:96 packetization-mode=1;profile-level-id=42C028;sprop-parameter-sets=Z0LAKIyNQDwBEvLAPCIRqA==,aM48gA==;
            // a=fmtp:96 packetization-mode=1; profile-level-id=4D4029; sprop-parameter-sets=Z01AKZpmBkCb8uAtQEBAQXpw,aO48gA==
            // a=fmtp:99 sprop-parameter-sets=Z0LgKdoBQBbpuAgIMBA=,aM4ySA==;packetization-mode=1;profile-level-id=42e029
            // a=fmtp:98 profile-id=1;sprop-sps=QgEBAWAAAAMAgAAAAwAAAwB4oAWCAJB/ja7tTd3Jdf+ACAAFtwUFBQQAAA+gAAGGoch3uUQD6AARlAB9AAIygg==;sprop-pps=RAHBcrAiQA==;sprop-vps=QAEMAf//AWAAAAMAgAAAAwAAAwB4rAk=
            val params = getSdpAParams(param)
            if (params != null) {
                for (pair in params) {
                    when (pair.first.lowercase(Locale.getDefault())) {
                        "sprop-sps" -> {
                            videoTrack.sps = getNalUnitFromSprop(pair.second)
                        }

                        "sprop-pps" -> {
                            videoTrack.pps = getNalUnitFromSprop(pair.second)
                        }

                        "sprop-vps" -> {
                            videoTrack.vps = getNalUnitFromSprop(pair.second)
                        }

                        "sprop-parameter-sets" -> {
                            val paramsSpsPps = TextUtils.split(pair.second, ",")
                            if (paramsSpsPps.size > 1) {
                                videoTrack.sps = getNalUnitFromSprop(
                                    paramsSpsPps[0]
                                )
                                videoTrack.pps = getNalUnitFromSprop(
                                    paramsSpsPps[1]
                                )
                                //                            Base64.decode(paramsSpsPps[0], Base64.NO_WRAP);
//                            byte[] pps = Base64.decode(paramsSpsPps[1], Base64.NO_WRAP);
//                            byte[] nalSps = new byte[sps.length + 4];
//                            byte[] nalPps = new byte[pps.length + 4];
//                            // Add 00 00 00 01 NAL unit header
//                            nalSps[0] = 0;
//                            nalSps[1] = 0;
//                            nalSps[2] = 0;
//                            nalSps[3] = 1;
//                            System.arraycopy(sps, 0, nalSps, 4, sps.length);
//                            nalPps[0] = 0;
//                            nalPps[1] = 0;
//                            nalPps[2] = 0;
//                            nalPps[3] = 1;
//                            System.arraycopy(pps, 0, nalPps, 4, pps.length);
//                            videoTrack.sps = nalSps;
//                            videoTrack.pps = nalPps;
                            }
                        }

                        "packetization-mode" -> {
                            // 0 - single NAL unit (default)
                            // 1 - non-interleaved mode (STAP-A and FU-A NAL units)
                            // 2 - interleaved mode
                            try {
                                val mode = pair.second.toInt()
                                if (mode == 2) Log.e(
                                    TAG,
                                    "Interleaved packetization mode is not supported"
                                )
                            } catch (_: NumberFormatException) {
                            }
                        }
                    }
                }
            }
        }

        private fun getBytesFromHexString(config: String): ByteArray {
            // "1210fff1" -> [12, 10, ff, f1]
            return BigInteger(config, 16).toByteArray()
        }

        private fun updateAudioTrackFromDescribeParam(
            audioTrack: AudioTrack,
            param: Pair<String, String>
        ) {
            // a=fmtp:96 streamtype=5; profile-level-id=14; mode=AAC-lbr; config=1388; sizeLength=6; indexLength=2; indexDeltaLength=2; constantDuration=1024; maxDisplacement=5
            // a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1408
            // a=fmtp:96 profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1210fff15081ffdffc
            val params = getSdpAParams(param)
            if (params != null) {
                for (pair in params) {
                    when (pair.first.lowercase(Locale.getDefault())) {
                        "mode" -> audioTrack.mode = pair.second
                        "config" -> audioTrack.config = getBytesFromHexString(pair.second)
                    }
                }
            }
        }

        /**
         * Search for header "Content-Base: rtsp://example.com/stream/"
         * and return "rtsp://example.com/stream/"
         */
        private fun getHeaderContentBase(headers: ArrayList<Pair<String, String>>): String? {
            val contentBase = getHeader(headers, "content-base")
            if (!TextUtils.isEmpty(contentBase)) {
                return contentBase
            }
            return null
        }

        private fun getHeaderContentLength(headers: ArrayList<Pair<String, String>>): Int {
            val length = getHeader(headers, "content-length")
            if (!TextUtils.isEmpty(length)) {
                try {
                    return length!!.toInt()
                } catch (_: NumberFormatException) {
                }
            }
            return -1
        }

        private fun getSupportedCapabilities(headers: ArrayList<Pair<String, String>>): Int {
            for (head in headers) {
                val h = head.first.lowercase(Locale.getDefault())
                // Public: OPTIONS, DESCRIBE, SETUP, PLAY, GET_PARAMETER, SET_PARAMETER, TEARDOWN
                if ("public" == h) {
                    var mask = 0
                    val tokens = TextUtils.split(head.second.lowercase(Locale.getDefault()), ",")
                    for (token in tokens) {
                        when (token.trim { it <= ' ' }) {
                            "options" -> mask = mask or RTSP_CAPABILITY_OPTIONS
                            "describe" -> mask = mask or RTSP_CAPABILITY_DESCRIBE
                            "announce" -> mask = mask or RTSP_CAPABILITY_ANNOUNCE
                            "setup" -> mask = mask or RTSP_CAPABILITY_SETUP
                            "play" -> mask = mask or RTSP_CAPABILITY_PLAY
                            "record" -> mask = mask or RTSP_CAPABILITY_RECORD
                            "pause" -> mask = mask or RTSP_CAPABILITY_PAUSE
                            "teardown" -> mask = mask or RTSP_CAPABILITY_TEARDOWN
                            "set_parameter" -> mask = mask or RTSP_CAPABILITY_SET_PARAMETER
                            "get_parameter" -> mask = mask or RTSP_CAPABILITY_GET_PARAMETER
                            "redirect" -> mask = mask or RTSP_CAPABILITY_REDIRECT
                        }
                    }
                    return mask
                }
            }
            return RTSP_CAPABILITY_NONE
        }

        private fun getHeaderWwwAuthenticateDigestRealmAndNonce(headers: ArrayList<Pair<String, String>>): Pair<String, String>? {
            for (head in headers) {
                val h = head.first.lowercase(Locale.getDefault())
                // WWW-Authenticate: Digest realm="AXIS_00408CEF081C", nonce="00054cecY7165349339ae05f7017797d6b0aaad38f6ff45", stale=FALSE
                // WWW-Authenticate: Basic realm="AXIS_00408CEF081C"
                // WWW-Authenticate: Digest realm="Login to 4K049EBPAG1D7E7", nonce="de4ccb15804565dc8a4fa5b115695f4f"
                if ("www-authenticate" == h && head.second.lowercase(Locale.getDefault())
                        .startsWith("digest")
                ) {
                    val v = head.second.substring(7).trim { it <= ' ' }
                    var end: Int

                    var begin = v.indexOf("realm=")
                    begin = v.indexOf('"', begin) + 1
                    end = v.indexOf('"', begin)
                    val digestRealm = v.substring(begin, end)

                    begin = v.indexOf("nonce=")
                    begin = v.indexOf('"', begin) + 1
                    end = v.indexOf('"', begin)
                    val digestNonce = v.substring(begin, end)

                    return Pair.create(digestRealm, digestNonce)
                }
            }
            return null
        }

        private fun getHeaderWwwAuthenticateBasicRealm(headers: ArrayList<Pair<String, String>>): String? {
            for (head in headers) {
                // Session: ODgyODg3MjQ1MDczODk3NDk4Nw
                val h = head.first.lowercase(Locale.getDefault())
                var v = head.second.lowercase(Locale.getDefault())
                // WWW-Authenticate: Digest realm="AXIS_00408CEF081C", nonce="00054cecY7165349339ae05f7017797d6b0aaad38f6ff45", stale=FALSE
                // WWW-Authenticate: Basic realm="AXIS_00408CEF081C"
                if ("www-authenticate" == h && v.startsWith("basic")) {
                    v = v.substring(6).trim { it <= ' ' }
                    // realm=
                    // AXIS_00408CEF081C
                    val tokens = TextUtils.split(v, "\"")
                    if (tokens.size > 2) return tokens[1]
                }
            }
            return null
        }

        // Basic authentication
        private fun getBasicAuthHeader(username: String?, password: String?): String {
            val auth = (username ?: "") + ":" + (password ?: "")
            return "Basic " + String(
                Base64.encode(
                    auth.toByteArray(StandardCharsets.ISO_8859_1),
                    Base64.NO_WRAP
                )
            )
        }

        // Digest authentication
        private fun getDigestAuthHeader(
            username: String?,
            password: String?,
            method: String,
            digestUri: String,
            realm: String,
            nonce: String
        ): String? {
            var username = username
            var password = password
            try {
                val md = MessageDigest.getInstance("MD5")

                if (username == null) username = ""
                if (password == null) password = ""

                // calc A1 digest
                md.update(username.toByteArray(StandardCharsets.ISO_8859_1))
                md.update(':'.code.toByte())
                md.update(realm.toByteArray(StandardCharsets.ISO_8859_1))
                md.update(':'.code.toByte())
                md.update(password.toByteArray(StandardCharsets.ISO_8859_1))
                val ha1 = md.digest()

                // calc A2 digest
                md.reset()
                md.update(method.toByteArray(StandardCharsets.ISO_8859_1))
                md.update(':'.code.toByte())
                md.update(digestUri.toByteArray(StandardCharsets.ISO_8859_1))
                val ha2 = md.digest()

                // calc response
                md.update(getHexStringFromBytes(ha1).toByteArray(StandardCharsets.ISO_8859_1))
                md.update(':'.code.toByte())
                md.update(nonce.toByteArray(StandardCharsets.ISO_8859_1))
                md.update(':'.code.toByte())
                // TODO add support for more secure version of digest auth
                //md.update(nc.getBytes(StandardCharsets.ISO_8859_1));
                //md.update((byte) ':');
                //md.update(cnonce.getBytes(StandardCharsets.ISO_8859_1));
                //md.update((byte) ':');
                //md.update(qop.getBytes(StandardCharsets.ISO_8859_1));
                //md.update((byte) ':');
                md.update(getHexStringFromBytes(ha2).toByteArray(StandardCharsets.ISO_8859_1))
                val response = getHexStringFromBytes(md.digest())

                //            log.trace("username=\"{}\", realm=\"{}\", nonce=\"{}\", uri=\"{}\", response=\"{}\"",
//                    userName, digestRealm, digestNonce, digestUri, response);
                return "Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$digestUri\", response=\"$response\""
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }

        private fun getHexStringFromBytes(bytes: ByteArray): String {
            val buf = StringBuilder()
            for (b in bytes) buf.append(String.format("%02x", b))
            return buf.toString()
        }

        @Throws(IOException::class)
        private fun readContentAsText(inputStream: InputStream, length: Int): String {
            if (length <= 0) return ""
            val b = ByteArray(length)
            val read = readData(inputStream, b, 0, length)
            return String(b, 0, read)
        }

        // int memcmp ( const void * ptr1, const void * ptr2, size_t num );

        fun memcmp(
            source1: ByteArray,
            offsetSource1: Int,
            source2: ByteArray,
            offsetSource2: Int,
            num: Int
        ): Boolean {
            if (source1.size - offsetSource1 < num || source2.size - offsetSource2 < num) return false
            // Прямое сравнение по индексам: без аллокаций (sliceArray создавал 2 массива на вызов).
            for (i in 0 until num) {
                if (source1[offsetSource1 + i] != source2[offsetSource2 + i]) return false
            }
            return true
        }

        private fun shiftLeftArray(array: ByteArray, num: Int) {
            // ABCDEF -> BCDEF
            if (num - 1 >= 0) System.arraycopy(array, 1, array, 0, num - 1)
        }

        @Throws(IOException::class)
        private fun readData(
            inputStream: InputStream,
            buffer: ByteArray,
            offset: Int,
            length: Int
        ): Int {
            if (DEBUG) Log.v(
                TAG,
                "readData(offset=$offset, length=$length)"
            )
            var readBytes: Int
            var totalReadBytes = 0
            do {
                readBytes =
                    inputStream.read(buffer, offset + totalReadBytes, length - totalReadBytes)
                if (readBytes > 0) totalReadBytes += readBytes
            } while (readBytes >= 0 && totalReadBytes < length)
            return totalReadBytes
        }

        private fun dumpHeaders(headers: ArrayList<Pair<String, String>>) {
            if (DEBUG) {
                for (head in headers) {
                    Log.d(TAG, head.first + ": " + head.second)
                }
            }
        }

        private fun getHeader(headers: ArrayList<Pair<String, String>>, header: String): String? {
            for (head in headers) {
                // Session: ODgyODg3MjQ1MDczODk3NDk4Nw
                val h = head.first.lowercase(Locale.getDefault())
                if (header.lowercase(Locale.getDefault()) == h) {
                    return head.second
                }
            }
            // Not found
            return null
        }
    }
}

internal class LoggerOutputStream(out: OutputStream) : BufferedOutputStream(out) {
    private var logging = true

    @Synchronized
    fun setLogging(logging: Boolean) {
        this.logging = logging
    }

    @Synchronized
    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        super.write(b, off, len)
        if (logging) Log.i(RtspClient.TAG_DEBUG, String(b, off, len))
    }
}
