// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.Runnable;
import java.net.URI;
import java.util.Date;
import java.util.HashSet;

class FileCache implements Runnable {
    static private final String TAG = "FileCache";

    static private final int BUFFER_SIZE = 8 * 1024;

    // We download this many initial bytes as quickly as we can.
    static private final int INITIAL_BYTES = 128 * 1024;

    static private final int MAX_BYTES_PER_SECOND = 0;

    static private final int PROGRESS_REPORT_BYTES = 64 * 1024;

    interface DownloadListener {
        void onDownloadFail(int handle);
        void onDownloadComplete(int handle);
        void onDownloadProgress(int handle, long receivedBytes, long totalBytes, long elapsedMs);
    }

    // Application context.
    private final Context mContext;

    // Directory where we write music files.
    private final File mMusicDir;

    // Used to run tasks on our own thread.
    private Handler mHandler;

    private HashSet mHandles = new HashSet();

    private int mNextHandle = 1;

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
        synchronized(mHandles) {
            mHandles.clear();
        }
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Looper.myLooper().quit();
            }
        });
    }

    public File getLocalFile(String urlPath) {
        return new File(mMusicDir, urlPath.replace("/", "_"));
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

    private boolean isDownloadActive(int handle) {
        synchronized(mHandles) {
            return mHandles.contains(handle);
        }
    }

    public int downloadFile(final String urlPath, final DownloadListener listener) {
        final int handle;
        synchronized(mHandles) {
            handle = mNextHandle++;
            mHandles.add(handle);
            Log.d(TAG, "posting download " + handle + " of " + urlPath);
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isDownloadActive(handle))
                    return;

                DownloadRequest request;
                try {
                    request = new DownloadRequest(mContext, DownloadRequest.Method.GET, urlPath, null);
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

                if (!isDownloadActive(handle)) {
                    result.close();
                    return;
                }

                File file = getLocalFile(urlPath);
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
                    Date startDate = new Date();
                    int bytesRead = 0, bytesWritten = 0;
                    int lastReportBytes = 0;
                    byte[] buffer = new byte[BUFFER_SIZE];

                    while ((bytesRead = result.getStream().read(buffer)) != -1) {
                        if (!isDownloadActive(handle)) {
                            result.close();
                            file.delete();
                            return;
                        }

                        outputStream.write(buffer, 0, bytesRead);
                        bytesWritten += bytesRead;

                        Date now = new Date();
                        long elapsedMs = now.getTime() - startDate.getTime();

                        if (bytesWritten >= lastReportBytes + PROGRESS_REPORT_BYTES) {
                            listener.onDownloadProgress(handle, bytesWritten, result.getContentLength(), elapsedMs);
                            lastReportBytes = bytesWritten;
                        }

                        if (MAX_BYTES_PER_SECOND > 0) {
                            long expectedMs = (long) (bytesWritten / (float) MAX_BYTES_PER_SECOND * 1000);
                            if (elapsedMs < expectedMs)
                                SystemClock.sleep(expectedMs - elapsedMs);
                        }
                    }
                    Date endDate = new Date();
                    Log.d(TAG, "finished download " + handle + " (" + bytesWritten + " bytes to " +
                          file.getAbsolutePath() + " in " + (endDate.getTime() - startDate.getTime()) + " ms)");
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

    public long getDataBytes() {
        long size = 0;
        for (File file : mMusicDir.listFiles())
            size += file.length();
        return size;
    }

    public void clear() {
        for (File file : mMusicDir.listFiles())
            file.delete();
    }
}
