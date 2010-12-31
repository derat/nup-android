package org.erat.nup;

import android.net.http.AndroidHttpClient;
import android.util.Log;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.RequestLine;

class LocalProxy implements Runnable {
    private final HttpHost mHttpHost;
    private final ServerSocket mServerSocket;

    public LocalProxy(String remoteHostname, int remotePort, String remoteScheme) throws IOException {
        mHttpHost = new HttpHost(remoteHostname, remotePort, remoteScheme);
        mServerSocket = new ServerSocket(0);
        Log.i(this.toString(), "listening on port " + mServerSocket.getLocalPort());
    }

    public int getPort() { return mServerSocket.getLocalPort(); }

    private void handleConnection(Socket socket) throws HttpException, IOException {
        DefaultHttpServerConnection conn = new DefaultHttpServerConnection();
        BasicHttpParams params = new BasicHttpParams();
        try {
            conn.bind(socket, params);
        } catch (java.net.UnknownHostException err) {
            Log.wtf(this.toString(), "unable to resolve localhost: " + err.toString());
        }

        HttpRequest request = conn.receiveRequestHeader();
        RequestLine requestLine = request.getRequestLine();
        Log.i(this.toString(), "got request with method " + requestLine.getMethod() + " and uri " + requestLine.getUri());

        final String userAgent = "whatever";
        AndroidHttpClient client = AndroidHttpClient.newInstance(userAgent);
        HttpResponse response = client.execute(mHttpHost, request);
        Log.i(this.toString(), "got response from remote server");
    }

    @Override
    public void run() {
        while (true) {
            try {
                Socket socket = mServerSocket.accept();
                handleConnection(socket);
            } catch (IOException err) {
                Log.e(this.toString(), "got IO error while handling connection: " + err.toString());
            } catch (HttpException err) {
                Log.e(this.toString(), "got HTTP error while handling connection: " + err.toString());
            }
        }
    }
}
