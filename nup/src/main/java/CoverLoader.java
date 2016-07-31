// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FilenameFilter;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.HashSet;

class CoverLoader {
    private static final String TAG = "CoverLoader";

    // Name of cover subdirectory.
    private static final String DIR_NAME = "covers";

    // Size of buffer used to write data to disk, in bytes.
    private static final int BUFFER_SIZE = 8 * 1024;

    private final Context mContext;
    private final Downloader mDownloader;
    private final NetworkHelper mNetworkHelper;

    // Directory where we write cover images.
    private File mCoverDir;

    // Guards |mFilesBeingLoaded|.
    private final Lock mLock = new ReentrantLock();

    // Signalled when a change has been made to |mFilesBeingLoaded|.
    private final Condition mLoadFinishedCond = mLock.newCondition();

    // Names of cover files that we're currently fetching.
    private HashSet mFilesBeingLoaded = new HashSet();

    // The last cover that we've loaded.  We store it here so that we can reuse the already-loaded
    // bitmap in the common case where we're playing an album and need the same cover over and over.
    private Object mLastCoverLock = new Object();
    private String mLastCoverPath = "";
    private Bitmap mLastCoverBitmap = null;

    public CoverLoader(Context context, Downloader downloader, NetworkHelper networkHelper) {
        mContext = context;
        mDownloader = downloader;
        mNetworkHelper = networkHelper;

        new AsyncTask<Void, Void, Void>() {
            @Override protected Void doInBackground(Void... args) {
                String state = Environment.getExternalStorageState();
                if (!state.equals(Environment.MEDIA_MOUNTED)) {
                    Log.e(TAG, "media has state " + state + "; we need " + Environment.MEDIA_MOUNTED);
                }

                mCoverDir = new File(mContext.getExternalCacheDir(), DIR_NAME);
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // Load the cover for a song's artist and album.  Tries to find it locally
    // first; then goes to the server.  Returns null if unsuccessful.
    public Bitmap loadCover(URL url) {
        Util.assertNotOnMainThread();

        if (mCoverDir == null) {
            Log.e(TAG, "got request for " + url.toString() + " before initialized");
            return null;
        }

        // Ensure that the cover dir exists.
        mCoverDir.mkdirs();

        File file = lookForLocalCover(url);
        if (file != null) {
            Log.d(TAG, "found local file " + file.getName());
        } else {
            file = downloadCover(url);
            if (file != null)
                Log.d(TAG, "fetched remote file " + file.getName());
        }

        if (file == null || !file.exists())
            return null;

        synchronized (mLastCoverLock) {
            if (mLastCoverPath.equals(file.getPath()))
                return mLastCoverBitmap;
            mLastCoverPath = file.getPath();
            mLastCoverBitmap = BitmapFactory.decodeFile(file.getPath());
            return mLastCoverBitmap;
        }
    }

    private File lookForLocalCover(final URL url) {
        String filename = getFilenameForUrl(url);
        startLoad(filename);
        try {
            File file = new File(mCoverDir, filename);
            if (file.exists())
                return file;
        } finally {
            finishLoad(filename);
        }
        return null;
    }

    private File downloadCover(final URL url) {
        if (!mNetworkHelper.isNetworkAvailable())
            return null;

        String localFilename = getFilenameForUrl(url);
        startLoad(localFilename);

        boolean success = false;
        File file = new File(mCoverDir, localFilename);
        FileOutputStream outputStream = null;
        HttpURLConnection conn = null;

        try {
            // Check if another thread downloaded it while we were waiting.
            if (file.exists()) {
                success = true;
                return file;
            }

            file.createNewFile();
            outputStream = new FileOutputStream(file);

            conn = mDownloader.download(url, "GET", Downloader.AuthType.STORAGE, null);
            if (conn.getResponseCode() != 200) {
                throw new IOException("got status code " + conn.getResponseCode());
            }

            byte[] buffer = new byte[BUFFER_SIZE];
            InputStream inputStream = conn.getInputStream();
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            success = true;
        } catch (IOException e) {
            Log.e(TAG, "got IO error while fetching " + url.toString() + ": " + e);
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    // !@#!@$!@
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
            if (!success && file.exists()) {
                file.delete();
            }
            finishLoad(localFilename);
        }

        return success ? file : null;
    }

    private String getFilenameForUrl(URL url) {
        String parts[] = url.getPath().split("/");
        return parts[parts.length-1];
    }

    // Call before checking for the existence of a local cover file and before
    // starting to download a remote file.  Waits until |filename| isn't in
    // |mFilesBeingLoaded| and then adds it.  Must be matched by a call to
    // finishLoad().
    private void startLoad(String filename) {
        mLock.lock();
        try {
            while (mFilesBeingLoaded.contains(filename))
                mLoadFinishedCond.await();
            mFilesBeingLoaded.add(filename);
        } catch (InterruptedException e) {
            // !#!@$#!$#!@
        } finally {
            mLock.unlock();
        }
    }

    // Call after checking for the existence of a local file or after completing
    // a download (either successfully or unsuccessfully -- if unsuccessful, be
    // sure to remove the file first so that other threads don't try to use it).
    private void finishLoad(String filename) {
        mLock.lock();
        try {
            if (!mFilesBeingLoaded.contains(filename))
                throw new RuntimeException("got report of finished load of unknown file " + filename);
            mFilesBeingLoaded.remove(filename);
            mLoadFinishedCond.signal();
        } finally {
            mLock.unlock();
        }
    }
}
