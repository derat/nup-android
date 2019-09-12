// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

public class Downloader {
    private static final String TAG = "Download";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 10000;

    /* Thrown when the request couldn't be constructed because some preferences that we need are
     * either unset or incorrect. */
    public static class PrefException extends Exception {
        public PrefException(String reason) {
            super(reason);
        }
    }

    public enum AuthType {
        SERVER,
        STORAGE
    }

    private final Authenticator mAuthenticator;
    private final SharedPreferences mPrefs;

    public Downloader(Authenticator authenticator, SharedPreferences prefs) {
        mAuthenticator = authenticator;
        mPrefs = prefs;
    }

    /**
     * Starts a download of a given URL.
     *
     * @param url URL to download
     * @param method HTTP method, e.g. "GET" or "POST"
     * @param authType authentication type to use
     * @param headers additional HTTP headers (may be null)
     * @return active HTTP connection; <code>disconnect</code> must be called
     */
    public HttpURLConnection download(URL url, String method, AuthType authType,
                                      Map<String, String> headers)
        throws IOException {
        Log.d(TAG, "starting " + method + " to " + url.toString());

        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod(method);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }

        if (authType == AuthType.SERVER) {
            // Add Authorization header if username and password prefs are set.
            String username = mPrefs.getString(NupPreferences.USERNAME, "");
            String password = mPrefs.getString(NupPreferences.PASSWORD, "");
            if (!username.isEmpty() && !password.isEmpty()) {
                conn.setRequestProperty("Authorization",
                                        "Basic " + Base64.encodeToString(
                                            (username + ":" + password).getBytes(),
                                            Base64.NO_WRAP));
            }
        } else if (authType == AuthType.STORAGE) {
            try {
                String token = mAuthenticator.getAuthToken();
                conn.setRequestProperty("Authorization", "Bearer " + token);
            } catch (Authenticator.AuthException e) {
                Log.e(TAG, "failed to get auth token: " + e);
            }
        }

        try {
            conn.connect();
            Log.d(TAG, "got " + conn.getResponseCode() + " (" + conn.getResponseMessage() +
                  ") for " + url.toString());
            return conn;
        } catch (SocketTimeoutException e) {
            Log.e(TAG, "got timeout for " + url.toString(), e);
            conn.disconnect();
            throw new IOException(e.toString());
        } catch (IOException e) {
            Log.e(TAG, "got IO exception for " + url.toString(), e);
            conn.disconnect();
            throw e;
        }
    }

    public String downloadString(String path, String[] error) {
        HttpURLConnection conn = null;
        try {
            conn = download(getServerUrl(path), "GET", AuthType.SERVER, null);
            final int status = conn.getResponseCode();
            if (status != 200) {
                error[0] = "Got " + status + " from server (" + conn.getResponseMessage() + ")";
                return null;
            }
            final InputStream stream = conn.getInputStream();
            try {
                return Util.getStringFromInputStream(stream);
            } finally {
                // This isn't documented as being necessary, but it seems to be needed to avoid a
                // StrictMode crash caused by a resource leak when syncing songs:
                //
                // StrictMode policy violation: android.os.strictmode.LeakedClosableViolation: A
                // resource was acquired at attached stack trace but never released. See
                // java.io.Closeable for information on avoiding resource leaks.
                //    at android.os.StrictMode$AndroidCloseGuardReporter.report(StrictMode.java:1786)
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
                //    at com.android.okhttp.internal.http.HttpEngine.readResponse(HttpEngine.java:648)
                //    at com.android.okhttp.internal.huc.HttpURLConnectionImpl.execute(HttpURLConnectionImpl.java:471)
                //    at com.android.okhttp.internal.huc.HttpURLConnectionImpl.getResponse(HttpURLConnectionImpl.java:407)
                //    at com.android.okhttp.internal.huc.HttpURLConnectionImpl.getResponseCode(HttpURLConnectionImpl.java:538)
                //    at com.android.okhttp.internal.huc.DelegatingHttpsURLConnection.getResponseCode(DelegatingHttpsURLConnection.java:105)
                //    at com.android.okhttp.internal.huc.HttpsURLConnectionImpl.getResponseCode(HttpsURLConnectionImpl.java:26)
                //    at org.erat.nup.Downloader.download(Downloader.java:94)
                //    at org.erat.nup.Downloader.downloadString(Downloader.java:111)
                //    at org.erat.nup.SongDatabase.queryServer(SongDatabase.java:524)
                //    at org.erat.nup.SongDatabase.syncWithServer(SongDatabase.java:479)
                //    ...
                //
                // Oddly, this only started happening after I upgraded the AppEngine app to use the
                // go111 runtime (?!).
                stream.close();
            }
        } catch (PrefException e) {
            error[0] = e.getMessage();
            return null;
        } catch (IOException e) {
            error[0] = "IO error (" + e + ")";
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public URL getServerUrl(String path) throws PrefException {
        String server = mPrefs.getString(NupPreferences.SERVER_URL, "");
        if (server.isEmpty()) {
            throw new PrefException("Server URL is not configured");
        }

        URL serverUrl;
        try {
            serverUrl = new URL(server);
        } catch (MalformedURLException e) {
            throw new PrefException("Unable to parse server URL \"" + server + "\" (" + e.getMessage() + ")");
        }

        // Check protocol and set port.
        final String protocol = serverUrl.getProtocol();
        int port;
        if (protocol.equals("http")) {
            port = (serverUrl.getPort() > 0) ? serverUrl.getPort() : 80;
        } else if (protocol.equals("https")) {
            port = (serverUrl.getPort() > 0) ? serverUrl.getPort() : 443;
        } else {
            throw new PrefException("Unknown server URL scheme \"" + protocol + "\" (should be \"http\" or \"https\")");
        }

        // Now build the real URL.
        try {
            return new URL(protocol, serverUrl.getHost(), port, path);
        } catch (MalformedURLException e) {
            throw new PrefException("Unable to parse URL: " + e.getMessage());
        }
    }
}
