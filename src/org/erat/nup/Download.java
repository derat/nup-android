// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.SSLCertificateSocketFactory;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.impl.DefaultHttpClientConnection;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.RequestLine;
import org.apache.http.StatusLine;

import java.io.InputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;

// Encapsulates a request to download a particular URL.
// Also does a lot of preparation like looking up server URL and authorization preferences
// and constructing the HTTP request.
class DownloadRequest {
    // Thrown when the request couldn't be constructed because some preferences that we need
    // are either unset or incorrect.
    public static class PrefException extends Exception {
        public PrefException(String reason) {
            super(reason);
        }
    }

    private HttpRequest mHttpRequest;
    private URI mUri;

    DownloadRequest(Context context, String path, String query) throws PrefException {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // Build a URI based on the server pref.
        mUri = parseServerUrlIntoUri(prefs.getString(NupPreferences.SERVER_URL, ""), path, query);

        // When mUri is used to construct the request, we send the full URI in the request, like "GET http://...".
        // This seems to make the server sad in some cases -- it fails to parse the query parameters.
        // Just passing the path and query as a string avoids this; we'll just send "GET /path?query" instead.
        mHttpRequest = new HttpGet(path + (query != null ? "?" + query : ""));
        mHttpRequest.addHeader("Host", mUri.getHost() + ":" + mUri.getPort());
        // TODO: Set User-Agent to something reasonable.

        // Add Authorization header if username and password prefs are set.
        String username = prefs.getString(NupPreferences.USERNAME, "");
        String password = prefs.getString(NupPreferences.PASSWORD, "");
        if (!username.isEmpty() && !password.isEmpty())
            mHttpRequest.addHeader("Authorization", "Basic " + Base64.encodeToString((username + ":" + password).getBytes(), Base64.NO_WRAP));
    }

    public static URI parseServerUrlIntoUri(String server, String path, String query) throws PrefException {
        if (server.isEmpty())
            throw new PrefException("Server URL is not configured");

        URI serverUri;
        try {
            serverUri = new URI(server);
        } catch (URISyntaxException e) {
            throw new PrefException("Unable to parse server URL \"" + server + "\" (" + e.getMessage() + ")");
        }

        // Check scheme and set port.
        String scheme = serverUri.getScheme();
        int port;
        if (scheme == null || scheme.equals("http")) {
            port = (serverUri.getPort() > 0) ? serverUri.getPort() : 80;
        } else if (scheme.equals("https")) {
            port = (serverUri.getPort() > 0) ? serverUri.getPort() : 443;
        } else {
            throw new PrefException("Unknown server URL scheme \"" + scheme + "\" (should be \"http\" or \"https\")");
        }

        // Now build the real URI.
        URI uri;
        try {
            uri = new URI(scheme, null, serverUri.getHost(), port, path, query, null);
        } catch (URISyntaxException e) {
            throw new PrefException("Unable to parse URL: " + e.getMessage());
        }

        return uri;
    }

    public final HttpRequest getHttpRequest() { return mHttpRequest; }
    public final URI getUri() { return mUri; }
}

// Encapsulates the response to a download attempt.
class DownloadResult {
    private static final String TAG = "DownloadResult";

    // HTTP status code returned by the server.
    private final int mStatusCode;

    // Reason accompanying the status code.
    private final String mReason;

    // Length of the resource, per the Content-Length header.
    private final long mContentLength;

    // Stream for reading the body of the response.
    private final InputStream mStream;

    private final DefaultHttpClientConnection mConn;

    DownloadResult(int statusCode, String reason, long contentLength, InputStream stream, DefaultHttpClientConnection conn) {
        mStatusCode = statusCode;
        mReason = reason;
        mContentLength = contentLength;
        mStream = stream;
        mConn = conn;
    }

    public int getStatusCode() { return mStatusCode; }
    public String getReason() { return mReason; }
    public long getContentLength() { return mContentLength; }
    public InputStream getStream() { return mStream; }

    // Must be called once the result has been read to close the connection to the server.
    public void close() {
        try {
            mConn.close();
        } catch (IOException e) {
            Log.e(TAG, "got IO exception while closing connection");
        }
    }
}

class Download {
    private static final String TAG = "Download";
    private static final int SSL_TIMEOUT_MS = 5000;

    public static DownloadResult startDownload(DownloadRequest req) throws HttpException, IOException {
        // TODO: from Apache's ElementalReverseProxy.java example -- dunno how important any of these are for us.
        HttpParams params = new BasicHttpParams();
        params
            .setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 5000)
            .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024)
            .setBooleanParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK, false)
            .setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true);

        final URI uri = req.getUri();
        DefaultHttpClientConnection conn = new DefaultHttpClientConnection();
        Socket socket = new Socket(uri.getHost(), uri.getPort());
        if (uri.getScheme().equals("https")) {
            // Wrap an SSL socket around the non-SSL one.
            SSLSocketFactory factory = SSLCertificateSocketFactory.getHttpSocketFactory(SSL_TIMEOUT_MS, null);
            try {
                socket = factory.createSocket(socket, uri.getHost(), uri.getPort(), true);
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        }

        try {
            conn.bind(socket, params);
        } catch (IOException e) {
            socket.close();
            throw e;
        }

        // Now send the request and read the response.
        // We pass through exceptions while trying not to leak the socket.
        try {
            conn.sendRequestHeader(req.getHttpRequest());
            conn.flush();

            HttpResponse response = conn.receiveResponseHeader();
            conn.receiveResponseEntity(response);
            StatusLine statusLine = response.getStatusLine();
            BasicHttpEntity entity = (BasicHttpEntity) response.getEntity();
            return new DownloadResult(statusLine.getStatusCode(),
                                      statusLine.getReasonPhrase(),
                                      entity.getContentLength(),
                                      entity.getContent(),
                                      conn);
        } catch (HttpException e) {
            socket.close();
            throw e;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    public static String downloadString(Context context, String path, String query, String[] error) {
        try {
            DownloadRequest request = new DownloadRequest(context, path, query);
            DownloadResult result = startDownload(request);
            if (result.getStatusCode() != 200) {
                error[0] = "Got " + result.getStatusCode() + " status code from server (" + result.getReason() + ")";
                return null;
            }
            String output = Util.getStringFromInputStream(result.getStream());
            result.close();
            return output;
        } catch (DownloadRequest.PrefException e) {
            error[0] = e.getMessage();
            return null;
        } catch (HttpException e) {
            error[0] = "HTTP error (" + e + ")";
            return null;
        } catch (IOException e) {
            error[0] = "IO error (" + e + ")";
            return null;
        }
    }
}
