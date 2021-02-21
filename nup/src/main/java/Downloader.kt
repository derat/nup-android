/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

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
open class Downloader(private val authenticator: Authenticator) {
    /* Thrown when the request couldn't be constructed because some preferences that we need are
     * either unset or incorrect. */
    class PrefException(reason: String?) : Exception(reason)

    /** Authentication methods that can be used when downloading resources. */
    enum class AuthType {
        /** HTTP basic auth with username and password from prefs. */
        SERVER,
        /** Google account fetched using [Authenticator]. */
        STORAGE,
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
     * @return active HTTP connection; [disconnect] must be called
     */
    @Throws(IOException::class)
    fun download(
        url: URL,
        method: String,
        authType: AuthType,
        headers: Map<String, String>?,
    ): HttpURLConnection {
        Log.d(TAG, "$method $url")
        val conn = url.openConnection() as HttpsURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = method

        if (headers != null) {
            for ((key, value) in headers) {
                conn.setRequestProperty(key, value)
            }
        }

        if (authType == AuthType.SERVER) {
            // Add Authorization header if username and password prefs are set.
            if (!username.isEmpty() && !password.isEmpty()) {
                conn.setRequestProperty(
                    "Authorization",
                    "Basic " + Base64.encodeToString(
                        "$username:$password".toByteArray(), Base64.NO_WRAP
                    )
                )
            }
        } else if (authType == AuthType.STORAGE) {
            try {
                val token = authenticator.authToken
                conn.setRequestProperty("Authorization", "Bearer $token")
            } catch (e: Authenticator.AuthException) {
                Log.e(TAG, "Failed to get auth token: $e")
            }
        }

        return try {
            conn.connect()
            Log.d(TAG, "Got ${conn.responseCode} (${conn.responseMessage}) for $url")
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

    /**
     * Download text data from the server.
     *
     * @return data, or null if download failed
     */
    fun downloadString(path: String, error: Array<String>): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = download(getServerUrl(path), "GET", AuthType.SERVER, null)
            val status = conn.responseCode
            if (status != 200) {
                error[0] = "Got $status from server (${conn.responseMessage})"
                return null
            }
            return try {
                conn.inputStream.bufferedReader().use(BufferedReader::readText)
            } finally {
                // This isn't documented as being necessary, but it seems to be needed to avoid
                // android.os.strictmode.LeakedClosableViolation when syncing songs. Oddly, this
                // only started happening after I upgraded the AppEngine app to use the go111
                // runtime (?!).
                conn.inputStream.close()
            }
        } catch (e: PrefException) {
            error[0] = e.message ?: "Pref error ($e)"
            null
        } catch (e: IOException) {
            error[0] = "IO error ($e)"
            null
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
