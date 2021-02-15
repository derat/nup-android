/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

// TODO: Make this non-open if possible after switching to Robolectric.
open class Downloader(
    private val authenticator: Authenticator,
    private val prefs: SharedPreferences
) {
    /* Thrown when the request couldn't be constructed because some preferences that we need are
     * either unset or incorrect. */
    class PrefException(reason: String?) : Exception(reason)
    enum class AuthType {
        SERVER, STORAGE
    }

    /**
     * Starts a download of a given URL.
     *
     * @param url URL to download
     * @param method HTTP method, e.g. "GET" or "POST"
     * @param authType authentication type to use
     * @param headers additional HTTP headers (may be null)
     * @return active HTTP connection; `disconnect` must be called
     */
    @Throws(IOException::class)
    fun download(
        url: URL,
        method: String,
        authType: AuthType,
        headers: Map<String, String>?
    ): HttpURLConnection {
        Log.d(TAG, "starting $method to $url")
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
            val username = prefs.getString(NupPreferences.USERNAME, "")
            val password = prefs.getString(NupPreferences.PASSWORD, "")
            if (!username!!.isEmpty() && !password!!.isEmpty()) {
                conn.setRequestProperty(
                    "Authorization",
                    "Basic " +
                        Base64.encodeToString(
                            "$username:$password".toByteArray(), Base64.NO_WRAP
                        )
                )
            }
        } else if (authType == AuthType.STORAGE) {
            try {
                val token = authenticator.authToken
                conn.setRequestProperty("Authorization", "Bearer $token")
            } catch (e: Authenticator.AuthException) {
                Log.e(TAG, "failed to get auth token: $e")
            }
        }
        return try {
            conn.connect()
            Log.d(TAG, "got ${conn.responseCode} (${conn.responseMessage}) for $url")
            conn
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "got timeout for $url", e)
            conn.disconnect()
            throw IOException(e.toString())
        } catch (e: IOException) {
            Log.e(TAG, "got IO exception for $url", e)
            conn.disconnect()
            throw e
        }
    }

    fun downloadString(path: String, error: Array<String>): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = download(getServerUrl(path), "GET", AuthType.SERVER, null)
            val status = conn.responseCode
            if (status != 200) {
                error[0] = "Got $status from server (${conn.responseMessage})"
                return null
            }
            val stream = conn.inputStream
            try {
                Util.getStringFromInputStream(stream)
            } finally {
                // This isn't documented as being necessary, but it seems to be needed to avoid a
                // StrictMode crash caused by a resource leak when syncing songs:
                //
                // StrictMode policy violation: android.os.strictmode.LeakedClosableViolation: A
                // resource was acquired at attached stack trace but never released. See
                // java.io.Closeable for information on avoiding resource leaks.
                //    at
                // android.os.StrictMode$AndroidCloseGuardReporter.report(StrictMode.java:1786)
                //    at dalvik.system.CloseGuard.warnIfOpen(CloseGuard.java:264)
                //    at java.util.zip.Inflater.finalize(Inflater.java:398)
                //    at java.lang.Daemons$FinalizerDaemon.doFinalize(Daemons.java:250)
                //    at java.lang.Daemons$FinalizerDaemon.runInternal(Daemons.java:237)
                //    at java.lang.Daemons$Daemon.run(Daemons.java:103)
                //    at java.lang.Thread.run(Thread.java:764)
                // Caused by: java.lang.Throwable: Explicit termination method 'end' not called
                //    at dalvik.system.CloseGuard.open(CloseGuard.java:221)
                //    at java.util.zip.Inflater.<init>(Inflater.java:114)
                //    at com.android.okhttp.okio.GzipSource.<init>(GzipSource.java:62)
                //    at com.android.okhttp.internal.http.HttpEngine.unzip(HttpEngine.java:473)
                //    at
                // com.android.okhttp.internal.http.HttpEngine.readResponse(HttpEngine.java:648)
                //    at
                // com.android.okhttp.internal.huc.HttpURLConnectionImpl.execute(HttpURLConnectionImpl.java:471)
                //    at
                // com.android.okhttp.internal.huc.HttpURLConnectionImpl.getResponse(HttpURLConnectionImpl.java:407)
                //    at
                // com.android.okhttp.internal.huc.HttpURLConnectionImpl.getResponseCode(HttpURLConnectionImpl.java:538)
                //    at
                // com.android.okhttp.internal.huc.DelegatingHttpsURLConnection.getResponseCode(DelegatingHttpsURLConnection.java:105)
                //    at
                // com.android.okhttp.internal.huc.HttpsURLConnectionImpl.getResponseCode(HttpsURLConnectionImpl.java:26)
                //    at org.erat.nup.Downloader.download(Downloader.java:94)
                //    at org.erat.nup.Downloader.downloadString(Downloader.java:111)
                //    at org.erat.nup.SongDatabase.queryServer(SongDatabase.java:524)
                //    at org.erat.nup.SongDatabase.syncWithServer(SongDatabase.java:479)
                //    ...
                //
                // Oddly, this only started happening after I upgraded the AppEngine app to use the
                // go111 runtime (?!).
                stream.close()
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
        val server = prefs.getString(NupPreferences.SERVER_URL, "")
        if (server!!.isEmpty()) {
            throw PrefException("Server URL is not configured")
        }
        val serverUrl: URL = try {
            URL(server)
        } catch (e: MalformedURLException) {
            throw PrefException("Unable to parse server URL $server (${e.message})")
        }

        // Check protocol and set port.
        val protocol = serverUrl.protocol
        val port: Int
        port = if (protocol == "http") {
            if (serverUrl.port > 0) serverUrl.port else 80
        } else if (protocol == "https") {
            if (serverUrl.port > 0) serverUrl.port else 443
        } else {
            throw PrefException(
                "Unknown server URL scheme \"$protocol\" (should be \"http\" or \"https\")"
            )
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
