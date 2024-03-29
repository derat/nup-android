/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.net.TrafficStats
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Downloads HTTP resources. */
// TODO: Make this non-open if possible after switching to Robolectric.
open class Downloader() {
    /* Thrown when the request couldn't be constructed because some preferences that we need are
     * either unset or incorrect. */
    class PrefException(reason: String?) : Exception(reason)

    /** Authentication methods that can be used when downloading resources. */
    enum class AuthType {
        /** HTTP basic auth with username and password from prefs. */
        SERVER,
        /** No authentication. */
        NONE,
    }

    // Configuration needed to perform downloads.
    var username = ""
    var password = ""
    var server = ""
        set(value) {
            field = value
            if (!field.isEmpty() && !field.contains("//")) field = "https://" + field
        }

    /**
     * Start a download of [url].
     *
     * @param url URL to download
     * @param method HTTP method, e.g. "GET" or "POST"
     * @param authType authentication type to use
     * @param headers additional HTTP headers (may be null)
     * @param connectTimeoutMs millisecond timeout for connecting to server
     * @param readTimeoutMs millisecond timeout for reading response
     * @return active HTTP connection; [disconnect] must be called
     */
    @Throws(IOException::class)
    fun download(
        url: URL,
        method: String,
        authType: AuthType,
        headers: Map<String, String>?,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): HttpURLConnection {
        Log.d(TAG, "$method $url")

        // Prevent "StrictMode: StrictMode policy violation:
        // android.os.strictmode.UntaggedSocketViolation: Untagged socket detected; use
        // TrafficStats.setTrafficStatsTag() to track all network usage".
        // It seems like the framework ought to just do this by default.
        TrafficStats.setThreadStatsTag(Thread.currentThread().getId().toInt())

        val conn = url.openConnection() as HttpsURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.requestMethod = method

        if (headers != null) {
            for ((key, value) in headers) {
                conn.setRequestProperty(key, value)
            }
        }

        when (authType) {
            AuthType.SERVER -> {
                // Add Authorization header if username and password prefs are set.
                if (!username.isEmpty() && !password.isEmpty()) {
                    conn.setRequestProperty(
                        "Authorization",
                        "Basic " + Base64.encodeToString(
                            "$username:$password".toByteArray(), Base64.NO_WRAP
                        )
                    )
                }
            }
            AuthType.NONE -> {}
        }

        return try {
            conn.connect()
            Log.d(TAG, "${conn.responseCode} (${conn.responseMessage}) for $url")
            conn
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Timeout for $url", e)
            conn.disconnect()
            throw IOException(e.toString())
        } catch (e: IOException) {
            Log.e(TAG, "IO exception for $url", e)
            conn.disconnect()
            throw e
        }
    }

    /** Result from [downloadString] containing either data or an error. */
    data class DownloadStringResult(val data: String?, val error: String?)

    /** Download text data from the server. */
    fun downloadString(
        path: String,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): DownloadStringResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = download(
                getServerUrl(path), "GET", AuthType.SERVER, null,
                connectTimeoutMs = connectTimeoutMs, readTimeoutMs = readTimeoutMs
            )
            val status = conn.responseCode
            if (status != 200) {
                return DownloadStringResult(
                    null,
                    "Got $status from server (${conn.responseMessage})"
                )
            }
            return try {
                DownloadStringResult(
                    conn.inputStream.bufferedReader().use(BufferedReader::readText),
                    null
                )
            } finally {
                // This isn't documented as being necessary, but it seems to be needed to avoid
                // android.os.strictmode.LeakedClosableViolation when syncing songs. Oddly, this
                // only started happening after I upgraded the AppEngine app to use the go111
                // runtime (?!).
                conn.inputStream.close()
            }
        } catch (e: PrefException) {
            DownloadStringResult(null, e.message ?: "Pref error ($e)")
        } catch (e: IOException) {
            DownloadStringResult(null, "IO error ($e)")
        } finally {
            conn?.disconnect()
        }
    }

    @Throws(PrefException::class)
    fun getServerUrl(path: String): URL {
        if (server.isEmpty()) throw PrefException("Server URL not configured")

        val serverUrl: URL = try {
            URL(server)
        } catch (e: MalformedURLException) {
            throw PrefException("Unable to parse server URL \"$server\" (${e.message})")
        }

        // Check protocol and set port.
        val protocol = serverUrl.protocol
        val port = when (protocol) {
            "http" -> if (serverUrl.port > 0) serverUrl.port else 80
            "https" -> if (serverUrl.port > 0) serverUrl.port else 443
            else -> throw PrefException("Unsupported server URL scheme \"$protocol\"")
        }

        // Now build the real URL.
        return try {
            URL(protocol, serverUrl.host, port, path)
        } catch (e: MalformedURLException) {
            throw PrefException("Unable to parse URL: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Download"
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 10000
    }
}
