package com.catanddev.rtsp.utils

import android.annotation.SuppressLint
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Arrays
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


object NetUtils {
    private val TAG: String = NetUtils::class.java.simpleName
    private const val DEBUG = false
    private const val MAX_LINE_SIZE = 4098

    @Throws(Exception::class)
    fun createSslSocketAndConnect(dstName: String, dstPort: Int, timeout: Int): SSLSocket {
        if (DEBUG) Log.v(
            TAG,
            "createSslSocketAndConnect(dstName=$dstName, dstPort=$dstPort, timeout=$timeout)"
        )

        //        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
//        trustManagerFactory.init((KeyStore) null);
//        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
//        if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
//           throw new IllegalStateException("Unexpected default trust managers:" + Arrays.toString(trustManagers));
//        }
//        X509TrustManager trustManager = (X509TrustManager) trustManagers[0];
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(FakeX509TrustManager()), null)
        val sslSocket = sslContext.socketFactory.createSocket() as SSLSocket
        sslSocket.connect(InetSocketAddress(dstName, dstPort), timeout)
        sslSocket.setSoLinger(false, 1)
        sslSocket.soTimeout = timeout
        return sslSocket
    }

    @Throws(IOException::class)
    fun createSocketAndConnect(dstName: String, dstPort: Int, timeout: Int): Socket {
        if (DEBUG) Log.v(
            TAG,
            "createSocketAndConnect(dstName=$dstName, dstPort=$dstPort, timeout=$timeout)"
        )
        val socket = Socket()
        socket.connect(InetSocketAddress(dstName, dstPort), timeout)
        socket.setSoLinger(false, 1)
        socket.soTimeout = timeout
        return socket
    }

    @Throws(IOException::class)
    fun createSocket(timeout: Int): Socket {
        val socket = Socket()
        socket.setSoLinger(false, 1) // 1 sec for flush() before close()
        socket.soTimeout = timeout // 10 sec timeout for read(), not for write()
        return socket
    }

    @Throws(IOException::class)
    fun closeSocket(socket: Socket?) {
        if (DEBUG) Log.v(TAG, "closeSocket()")
        if (socket != null) {
            try {
                socket.shutdownInput()
            } catch (_: Exception) {
            }
            try {
                socket.shutdownOutput()
            } catch (_: Exception) {
            }
            socket.close()
        }
    }

    @Throws(IOException::class)
    fun readResponseHeaders(inputStream: InputStream): ArrayList<String> {
//        Assert.assertNotNull("Input stream should not be null", inputStream);
        val headers = ArrayList<String>()
        var line: String?
        while (true) {
            line = readLine(inputStream)
            if (line != null) {
                if (line == "\r\n") return headers
                else headers.add(line)
            } else {
                break
            }
        }
        return headers
    }

    @Throws(IOException::class)
    fun readLine(inputStream: InputStream): String? {
//        Assert.assertNotNull("Input stream should not be null", inputStream);
        val bufferLine = ByteArray(MAX_LINE_SIZE)
        var offset = 0
        var readBytes: Int
        do {
            // Didn't find "\r\n" within 4K bytes
            if (offset >= MAX_LINE_SIZE) {
                throw IOException("Invalid headers")
            }

            // Read 1 byte
            readBytes = inputStream.read(bufferLine, offset, 1)
            if (readBytes == 1) {
                // Check for EOL
                // Some cameras like Linksys WVC200 do not send \n instead of \r\n
                if (offset > 0 &&  /*bufferLine[offset-1] == '\r' &&*/bufferLine[offset] == '\n'.code.toByte()) {
                    // Found empty EOL. End of header section
                    if (offset == 1) break

                    // Found EOL. Add to array.
                    return String(bufferLine, 0, offset - 1)
                } else {
                    offset++
                }
            }
        } while (readBytes > 0)
        return null
    }

    fun getResponseStatusCode(headers: ArrayList<String>): Int {
//        Assert.assertNotNull("Headers should not be null", headers);
        // Search for HTTP status code header
        for (header in headers) {
            var indexHttp = header.indexOf("HTTP/1.1 ") // 9 characters
            if (indexHttp == -1) indexHttp = header.indexOf("HTTP/1.0 ")
            if (indexHttp >= 0) {
                val indexCode = header.indexOf(' ', 9)
                val code = header.substring(9, indexCode)
                try {
                    return code.toInt()
                } catch (e: NumberFormatException) {
                    // Does not fulfill standard "HTTP/1.1 200 Ok" token
                    // Continue search for
                }
            }
        }
        // Not found
        return -1
    }

    //    @Nullable
    //    static String readContentAsText(@Nullable InputStream inputStream) throws IOException {
    //        if (inputStream == null)
    //            return null;
    //        BufferedReader r = new BufferedReader(new InputStreamReader(inputStream));
    //        StringBuilder total = new StringBuilder();
    //        String line;
    //        while ((line = r.readLine()) != null) {
    //            total.append(line);
    //            total.append("\r\n");
    //        }
    //        return total.toString();
    //    }
    @Throws(IOException::class)
    fun readContentAsText(inputStream: InputStream, length: Int): String {
//        Assert.assertNotNull("Input stream should not be null", inputStream);
        if (length <= 0) return ""
        val b = ByteArray(length)
        val read = readData(inputStream, b, 0, length)
        return String(b, 0, read)
    }


    fun readData(inputStream: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var readBytes = 0
        var totalReadBytes = 0
        do {
            try {
                readBytes = inputStream.read(buffer, offset + totalReadBytes, length - totalReadBytes)
            } catch (e: IOException) {
                Log.e("NetUtil", e.toString())
            }

            if (readBytes > 0) totalReadBytes += readBytes
        } while ((readBytes >= 0) && (totalReadBytes < length))
        return totalReadBytes
    }

    @SuppressLint("CustomX509TrustManager")
    class FakeX509TrustManager
    /**
     * Constructor for FakeX509TrustManager.
     */
        : X509TrustManager {
        /**
         * @see javax.net.ssl.X509TrustManager.checkClientTrusted
         */
        @SuppressLint("TrustAllX509TrustManager")
        @Throws(CertificateException::class)
        override fun checkClientTrusted(certificates: Array<X509Certificate>, authType: String) {
        }

        /**
         * @see javax.net.ssl.X509TrustManager.checkServerTrusted
         */
        @SuppressLint("TrustAllX509TrustManager")
        @Throws(CertificateException::class)
        override fun checkServerTrusted(certificates: Array<X509Certificate>, authType: String) {
        }

        // https://github.com/square/okhttp/issues/4669
        // Called by Android via reflection in X509TrustManagerExtensions.
        @Suppress("unused")
        @Throws(CertificateException::class)
        fun checkServerTrusted(
            chain: Array<X509Certificate?>,
            authType: String?,
            host: String?
        ): List<X509Certificate?> {
            return listOf(*chain)
        }

        /**
         * @see javax.net.ssl.X509TrustManager.getAcceptedIssuers
         */
        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return mAcceptedIssuers
        }

        companion object {
            /**
             * Accepted issuers for fake trust manager
             */
            private val mAcceptedIssuers = arrayOf<X509Certificate>()
        }
    }
}