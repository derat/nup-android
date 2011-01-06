// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.Runnable;
import java.net.URI;

class FileCache implements Runnable {
    static private final String TAG = "FileCache";

    static private final int BUFFER_SIZE = 8 * 1024;

    interface DownloadListener {
        void onDownloadAbort(String destPath);
        void onDownloadComplete(String destPath);
        void onDownloadProgress(String destPath, long numReceivedBytes);
    }

    // Application context.
    private final Context mContext;

    // Directory where we write music files.
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

    public void downloadFile(final String urlPath, final String destPath, final DownloadListener listener) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                DownloadRequest request;
                try {
                    request = new DownloadRequest(mContext, urlPath, null);
                } catch (DownloadRequest.PrefException e) {
                    listener.onDownloadAbort(destPath);
                    return;
                }

                DownloadResult result;
                try {
                    result = Download.startDownload(request);
                } catch (org.apache.http.HttpException e) {
                    listener.onDownloadAbort(destPath);
                    return;
                } catch (IOException e) {
                    listener.onDownloadAbort(destPath);
                    return;
                }

                File file = new File(mMusicDir, destPath);
                file.getParentFile().mkdirs();
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    listener.onDownloadAbort(destPath);
                    return;
                }

                FileOutputStream outputStream;
                try {
                    outputStream = new FileOutputStream(file);
                } catch (java.io.FileNotFoundException e) {
                    listener.onDownloadAbort(destPath);
                    return;
                }

                int bytesRead = 0, bytesWritten = 0;
                byte[] buffer = new byte[BUFFER_SIZE];
                try {
                    while ((bytesRead = result.getStream().read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        bytesWritten += bytesRead;
                    }
                } catch (IOException e) {
                    listener.onDownloadAbort(destPath);
                    return;
                }
                Log.d(TAG, "wrote " + bytesWritten + " bytes to " + file.getAbsolutePath());
            }
        });
    }

    public void clear() {
        for (File file : mMusicDir.listFiles())
            file.delete();
    }
}
