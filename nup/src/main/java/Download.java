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

class Download {
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

    /**
     * Starts a download of a given URL.
     *
     * @param context context
     * @param url URL to download
     * @param method HTTP method, e.g. "GET" or "POST"
     * @param authType authentication type to use
     * @param headers additional HTTP headers (may be null)
     * @return active HTTP connection; <code>disconnect</code> must be called
     */
    public static HttpURLConnection download(Context context, URL url, String method,
                                             AuthType authType, Map<String, String> headers)
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
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String username = prefs.getString(NupPreferences.USERNAME, "");
            String password = prefs.getString(NupPreferences.PASSWORD, "");
            if (!username.isEmpty() && !password.isEmpty()) {
                conn.setRequestProperty("Authorization",
                                        "Basic " + Base64.encodeToString(
                                            (username + ":" + password).getBytes(),
                                            Base64.NO_WRAP));
            }
        } else if (authType == AuthType.STORAGE) {
            try {
                String token = Auth.getAuthToken(context);
                conn.setRequestProperty("Authorization", "Bearer " + token);
            } catch (Auth.AuthException e) {
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
            throw new IOException(e.toString());
        } catch (IOException e) {
            Log.e(TAG, "got IO exception for " + url.toString(), e);
            throw e;
        }
    }

    public static String downloadString(Context context, String path, String[] error) {
        HttpURLConnection conn = null;
        try {
            conn = download(context, getServerUrl(context, path), "GET", AuthType.SERVER, null);
            final int status = conn.getResponseCode();
            if (status != 200) {
                error[0] = "Got " + status + " from server (" + conn.getResponseMessage() + ")";
                return null;
            }
            final String output = Util.getStringFromInputStream(conn.getInputStream());
            return output;
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

    public static URL getServerUrl(Context context, String path) throws PrefException {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String server = prefs.getString(NupPreferences.SERVER_URL, "");
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
