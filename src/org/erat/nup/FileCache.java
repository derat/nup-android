// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.net.http.AndroidHttpClient;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.Runnable;
import java.net.URI;

class FileCache implements Runnable {
    static private final String TAG = "FileCache";

    static private final String USER_AGENT = "nup";

    static private final int BUFFER_SIZE = 8 * 1024;

    private final Context mContext;

    private final File mMusicDir;

    // Used to run tasks on our own thread.
    private Handler mHandler;

    FileCache(Context context) {
        mContext = context;

        String state = Environment.getExternalStorageState();
        if (!state.equals(Environment.MEDIA_MOUNTED))
            Log.e(TAG, "media has state " + state + "; we need " + Environment.MEDIA_MOUNTED);

        mMusicDir = mContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
    }

    public void run() {
        Looper.prepare();
        mHandler = new Handler();
        Looper.loop();
    }

    public void quit() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Looper.myLooper().quit();
            }
        });
    }

    public void downloadFile(final String url, final String destPath) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                URI uri;
                try {
                    uri = new URI(url);
                } catch (java.net.URISyntaxException e) {
                    Log.e(TAG, "unable to parse URL " + url + ": " + e);
                    return;
                }

                HttpGet request = new HttpGet(uri);
                AndroidHttpClient client = AndroidHttpClient.newInstance(USER_AGENT);
                try {
                    HttpResponse response = client.execute(request);
                    int statusCode = response.getStatusLine().getStatusCode();
                    if (statusCode != HttpStatus.SC_OK) {
                        Log.d(TAG, "got non-200 status code " + statusCode + " while downloading " + url);
                        return;
                    }
                    HttpEntity entity = response.getEntity();
                    Log.d(TAG, "file at " + url + " has length " + entity.getContentLength());
                    InputStream inputStream = entity.getContent();

                    File file = new File(mMusicDir, destPath);
                    file.getParentFile().mkdirs();
                    file.createNewFile();

                    FileOutputStream outputStream = new FileOutputStream(file);
                    int bytesRead = 0, bytesWritten = 0;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        bytesWritten += bytesRead;
                    }
                    Log.d(TAG, "wrote " + bytesWritten + " bytes to " + file.getAbsolutePath());
                } catch (IOException e) {
                    Log.d(TAG, "got IO exception while downloading " + url + ": " + e);
                } finally {
                    client.close();
                }
            }
        });
    }

    public void clear() {
        for (File file : mMusicDir.listFiles())
            file.delete();
    }
}
