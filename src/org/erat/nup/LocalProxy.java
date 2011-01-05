// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.net.SSLCertificateSocketFactory;
import android.util.Base64;
import android.util.Log;

import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.impl.DefaultHttpClientConnection;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.RequestLine;
import org.apache.http.StatusLine;

import java.io.IOException;
import java.lang.Runnable;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

class LocalProxy implements Runnable {
    private static final String TAG = "LocalProxy";
    private static final int SSL_TIMEOUT_MS = 5000;
    private final String mRemoteHostname, mUsername, mPassword;
    private final int mRemotePort;
    private final boolean mUseSsl;
    private final ServerSocket mServerSocket;
    private final ThreadPoolExecutor mThreadPool;

    public LocalProxy(String remoteHostname, int remotePort, boolean useSsl, String username, String password) throws IOException {
        mRemoteHostname = remoteHostname;
        mRemotePort = remotePort;
        mUseSsl = useSsl;
        mUsername = username;
        mPassword = password;

        mThreadPool = (ThreadPoolExecutor) Executors.newCachedThreadPool();
        mServerSocket = new ServerSocket(0);
        Log.i(TAG, "listening on port " + mServerSocket.getLocalPort());
    }

    public int getPort() { return mServerSocket.getLocalPort(); }

    @Override
    public void run() {
        while (true) {
            try {
                Socket socket = mServerSocket.accept();
                ProxyTask task = new ProxyTask(socket);
                mThreadPool.submit(task);
            } catch (IOException e) {
                Log.e(TAG, "got IO error while handling connection: " + e);
            }
        }
    }

    class ProxyTask implements Runnable {
        private final HttpParams mParams;
        private Socket mServerSocket;

        public ProxyTask(Socket serverSocket) {
            mServerSocket = serverSocket;

            // From Apache's ElementalReverseProxy.java example -- dunno how important any of these are.
            mParams = new BasicHttpParams();
            mParams
                .setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 5000)
                .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024)
                .setBooleanParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK, false)
                .setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true);
        }

        private void reportError(DefaultHttpServerConnection conn, HttpRequest request, String message) {
            BasicHttpResponse response = new BasicHttpResponse(request.getProtocolVersion(), 500, message);
            try {
                conn.sendResponseHeader(response);
            } catch (HttpException e) {
                // Just trying to report an error; nothing to do if the report fails.
            } catch (IOException e) {
            } finally {
                try {
                    conn.close();
                } catch (IOException e) {
                    // Really?
                }
            }
        }

        @Override
        public void run() {
            DefaultHttpServerConnection serverConn;
            HttpRequest request;
            try {
                serverConn = new DefaultHttpServerConnection();
                serverConn.bind(mServerSocket, mParams);
                request = serverConn.receiveRequestHeader();
                RequestLine requestLine = request.getRequestLine();
                Log.d(TAG, "got " + requestLine.getMethod() + " request for " + requestLine.getUri());
            } catch (HttpException e) {
                Log.e(TAG, "got HTTP exception while setting up server connection: " + e);
                return;
            } catch (IOException e) {
                Log.e(TAG, "got IO exception while setting up server connection: " + e);
                return;
            }

            DefaultHttpClientConnection clientConn;
            Socket clientSocket;
            HttpResponse response;
            try {
                clientConn = new DefaultHttpClientConnection();
                clientSocket = new Socket(mRemoteHostname, mRemotePort);
                if (mUseSsl) {
                    SSLSocketFactory factory = SSLCertificateSocketFactory.getHttpSocketFactory(SSL_TIMEOUT_MS, null);
                    clientSocket = factory.createSocket(clientSocket, mRemoteHostname, mRemotePort, true);
                }
                clientConn.bind(clientSocket, mParams);

                if (mUsername != null && !mUsername.isEmpty() && mPassword != null && !mPassword.isEmpty()) {
                    request.addHeader("Authorization", "Basic " + Base64.encodeToString((mUsername + ":" + mPassword).getBytes(), Base64.NO_WRAP));
                }
                clientConn.sendRequestHeader(request);
                clientConn.flush();
                response = clientConn.receiveResponseHeader();
                StatusLine statusLine = response.getStatusLine();
                Log.d(TAG, "got " + statusLine.getStatusCode() + " response header from remote server: " + statusLine.getReasonPhrase());
            } catch (HttpException e) {
                reportError(serverConn, request, "Got HTTP error while connecting to server: " + e.getMessage());
                return;
            } catch (IOException e) {
                reportError(serverConn, request, "Got IO error while connecting to server: " + e.getMessage());
                return;
            }

            try {
                serverConn.sendResponseHeader(response);
                clientConn.receiveResponseEntity(response);
                Log.d(TAG, "got response entity with content-length " + response.getEntity().getContentLength());
                serverConn.sendResponseEntity(response);
            } catch (IOException e) {
                Log.e(TAG, "got IO exception while proxying request: " + e);
            } catch (HttpException e) {
                Log.e(TAG, "got HTTP exception while proxying request: " + e);
            }

            try {
                serverConn.close();
            } catch (IOException e) {
                Log.e(TAG, "got IO exception while closing server connection: " + e);
            }
            try {
                clientConn.close();
            } catch (IOException e) {
                Log.e(TAG, "got IO exception while closing client connection: " + e);
            }
        }
    }
}
