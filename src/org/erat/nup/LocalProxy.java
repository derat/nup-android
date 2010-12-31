package org.erat.nup;

import android.net.SSLCertificateSocketFactory;
import android.util.Log;
import java.io.IOException;
import java.lang.Thread;
import java.net.ServerSocket;
import java.net.Socket;
import javax.net.ssl.SSLSocketFactory;
//import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.impl.DefaultHttpClientConnection;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.RequestLine;
import org.apache.http.StatusLine;

class LocalProxy implements Runnable {
    private final String mRemoteHostname;
    private final int mRemotePort;
    private final boolean mUseSsl;
    private final ServerSocket mServerSocket;

    public LocalProxy(String remoteHostname, int remotePort, boolean useSsl) throws IOException {
        mRemoteHostname = remoteHostname;
        mRemotePort = remotePort;
        mUseSsl = useSsl;

        mServerSocket = new ServerSocket(0);
        Log.i(this.toString(), "listening on port " + mServerSocket.getLocalPort());
    }

    public int getPort() { return mServerSocket.getLocalPort(); }

    @Override
    public void run() {
        while (true) {
            try {
                Socket socket = mServerSocket.accept();
                Thread thread = new ProxyThread(socket);
                thread.setDaemon(true);
                thread.start();
            } catch (IOException err) {
                Log.e(this.toString(), "got IO error while handling connection: " + err);
            }
        }
    }

    class ProxyThread extends Thread {
        private final HttpParams mParams;
        private Socket mServerSocket;

        public ProxyThread(Socket serverSocket) {
            mServerSocket = serverSocket;

            // From Apache's ElementalReverseProxy.java example -- dunno how important any of these are.
            mParams = new BasicHttpParams();
            mParams
                .setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 5000)
                .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024)
                .setBooleanParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK, false)
                .setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true);
        }

        @Override
        public void run() {
            try {
                DefaultHttpServerConnection serverConn = new DefaultHttpServerConnection();
                serverConn.bind(mServerSocket, mParams);

                HttpRequest request = serverConn.receiveRequestHeader();
                RequestLine requestLine = request.getRequestLine();
                Log.i(this.toString(), "got " + requestLine.getMethod() + " request for " + requestLine.getUri());

                DefaultHttpClientConnection clientConn = new DefaultHttpClientConnection();
                Socket clientSocket = new Socket(mRemoteHostname, mRemotePort);
                if (mUseSsl) {
                    //SSLSocketFactory factory = SSLCertificateSocketFactory.getHttpSocketFactory(5000, null);
                    SSLSocketFactory factory = SSLCertificateSocketFactory.getInsecure(5000, null);
                    clientSocket = factory.createSocket(clientSocket, mRemoteHostname, mRemotePort, true);
                }
                clientConn.bind(clientSocket, mParams);

                clientConn.sendRequestHeader(request);
                clientConn.flush();
                HttpResponse response = clientConn.receiveResponseHeader();
                StatusLine statusLine = response.getStatusLine();
                Log.i(this.toString(), "got " + statusLine.getStatusCode() + " response header from remote server: " + statusLine.getReasonPhrase());
                serverConn.sendResponseHeader(response);

                clientConn.receiveResponseEntity(response);
                Log.i(this.toString(), "got response entity with content-length " + response.getEntity().getContentLength());
                serverConn.sendResponseEntity(response);
                serverConn.close();
                clientConn.close();
            } catch (IOException err) {
                Log.e(this.toString(), "got IO exception while proxying request: " + err);
            } catch (HttpException err) {
                Log.e(this.toString(), "got HTTP exception while proxying request: " + err);
            }
        }
    }
}
