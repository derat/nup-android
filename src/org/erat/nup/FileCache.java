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
import java.util.HashSet;
import java.util.Set;

class FileCache implements Runnable {
    static private final String TAG = "FileCache";

    static private final int BUFFER_SIZE = 8 * 1024;

    interface DownloadListener {
        void onDownloadFail(int handle);
        void onDownloadComplete(int handle);
        void onDownloadProgress(int handle, long numReceivedBytes);
    }

    // Application context.
    private final Context mContext;

    // Directory where we write music files.
    private final File mMusicDir;

    // Used to run tasks on our own thread.
    private Handler mHandler;

    private Set mHandles = new HashSet();

    private int mNextHandle = 1;

    FileCache(Context context) {
        mContext = context;

        String state = Environment.getExternalStorageState();
        if (!state.equals(Environment.MEDIA_MOUNTED))
            Log.e(TAG, "media has state " + state + "; we need " + Environment.MEDIA_MOUNTED);

        mMusicDir = mContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC);

        // FIXME: Remove this once done with testing.
        clear();
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

    public void abortDownload(int handle) {
        synchronized(mHandles) {
            if (!mHandles.contains(handle)) {
                Log.e(TAG, "tried to abort nonexistent download " + handle);
                return;
            }
            mHandles.remove(handle);
            Log.d(TAG, "canceled download " + handle);
        }
    }

    private boolean isDownloadCanceled(int handle) {
        synchronized(mHandles) {
            return !mHandles.contains(handle);
        }
    }

    public int downloadFile(final String urlPath, final String destPath, final DownloadListener listener) {
        final int handle;
        synchronized(mHandles) {
            handle = mNextHandle++;
            mHandles.add(handle);
            Log.d(TAG, "posting download " + handle + " (" + urlPath + " -> " + destPath + ")");
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isDownloadCanceled(handle))
                    return;

                DownloadRequest request;
                try {
                    request = new DownloadRequest(mContext, urlPath, null);
                } catch (DownloadRequest.PrefException e) {
                    listener.onDownloadFail(handle);
                    return;
                }

                DownloadResult result;
                try {
                    result = Download.startDownload(request);
                } catch (org.apache.http.HttpException e) {
                    listener.onDownloadFail(handle);
                    return;
                } catch (IOException e) {
                    listener.onDownloadFail(handle);
                    return;
                }

                if (isDownloadCanceled(handle)) {
                    result.close();
                    return;
                }

                File file = new File(mMusicDir, destPath);
                file.getParentFile().mkdirs();
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    result.close();
                    listener.onDownloadFail(handle);
                    return;
                }

                FileOutputStream outputStream;
                try {
                    outputStream = new FileOutputStream(file);
                } catch (java.io.FileNotFoundException e) {
                    result.close();
                    file.delete();
                    listener.onDownloadFail(handle);
                    return;
                }

                try {
                    int bytesRead = 0, bytesWritten = 0;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    while ((bytesRead = result.getStream().read(buffer)) != -1) {
                        if (isDownloadCanceled(handle)) {
                            result.close();
                            file.delete();
                            return;
                        }
                        outputStream.write(buffer, 0, bytesRead);
                        bytesWritten += bytesRead;
                    }
                    Log.d(TAG, "finished download " + handle + " (" + bytesWritten + " bytes to " + file.getAbsolutePath() + ")");
                } catch (IOException e) {
                    result.close();
                    file.delete();
                    listener.onDownloadFail(handle);
                    return;
                }

                result.close();
                listener.onDownloadComplete(handle);
            }
        });

        return handle;
    }

    public void clear() {
        for (File file : mMusicDir.listFiles())
            file.delete();
    }
}
