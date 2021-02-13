// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.graphics.Bitmap;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CoverLoader {
    private static final String TAG = "CoverLoader";

    // Name of cover subdirectory.
    private static final String DIR_NAME = "covers";

    // Size of buffer used to write data to disk, in bytes.
    private static final int BUFFER_SIZE = 8 * 1024;

    // Application-specific cache dir (not including DIR_NAME).
    private final File cacheDir;

    private final Downloader downloader;
    private final BitmapDecoder bitmapDecoder;
    private final NetworkHelper networkHelper;

    // Directory where we write cover images.
    private File coverDir;

    // Guards |mFilesBeingLoaded|.
    private final Lock lock = new ReentrantLock();

    // Signalled when a change has been made to |mFilesBeingLoaded|.
    private final Condition loadFinishedCond = lock.newCondition();

    // Names of cover files that we're currently fetching.
    private HashSet filesBeingLoaded = new HashSet();

    // The last cover that we've loaded.  We store it here so that we can reuse the already-loaded
    // bitmap in the common case where we're playing an album and need the same cover over and over.
    private Object lastCoverLock = new Object();
    private File lastCoverPath;
    private Bitmap lastCoverBitmap = null;

    public CoverLoader(
            File cacheDir,
            Downloader downloader,
            TaskRunner taskRunner,
            BitmapDecoder bitmapDecoder,
            NetworkHelper networkHelper) {
        this.cacheDir = cacheDir;
        this.downloader = downloader;
        this.bitmapDecoder = bitmapDecoder;
        this.networkHelper = networkHelper;

        taskRunner.runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        String state = Environment.getExternalStorageState();
                        // Null in unit tests. :-/
                        if (state != null && !state.equals(Environment.MEDIA_MOUNTED)) {
                            Log.e(
                                    TAG,
                                    "media has state "
                                            + state
                                            + "; we need "
                                            + Environment.MEDIA_MOUNTED);
                        }
                        coverDir = new File(CoverLoader.this.cacheDir, DIR_NAME);
                    }
                });
    }

    // Load the cover for a song's artist and album.  Tries to find it locally
    // first; then goes to the server.  Returns null if unsuccessful.
    public Bitmap loadCover(URL url) {
        // TODO: Util.assertNotOnMainThread() ought to be called here, but this gets called on the
        // main thread in unit tests.

        if (coverDir == null) {
            Log.e(TAG, "got request for " + url.toString() + " before initialized");
            return null;
        }

        // Ensure that the cover dir exists.
        coverDir.mkdirs();

        File file = lookForLocalCover(url);
        if (file != null) {
            Log.d(TAG, "found local file " + file.getName());
        } else {
            file = downloadCover(url);
            if (file != null) {
                Log.d(TAG, "fetched remote file " + file.getName());
            }
        }

        if (file == null || !file.exists()) {
            return null;
        }

        synchronized (lastCoverLock) {
            if (lastCoverPath != null && lastCoverPath.equals(file)) {
                return lastCoverBitmap;
            }
            lastCoverPath = file;
            lastCoverBitmap = bitmapDecoder.decodeFile(file);
            return lastCoverBitmap;
        }
    }

    private File lookForLocalCover(final URL url) {
        String filename = getFilenameForUrl(url);
        startLoad(filename);
        try {
            File file = new File(coverDir, filename);
            if (file.exists()) return file;
        } finally {
            finishLoad(filename);
        }
        return null;
    }

    private File downloadCover(final URL url) {
        if (!networkHelper.isNetworkAvailable()) return null;

        String localFilename = getFilenameForUrl(url);
        startLoad(localFilename);

        boolean success = false;
        File file = new File(coverDir, localFilename);
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

            conn = downloader.download(url, "GET", Downloader.AuthType.STORAGE, null);
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
        return parts[parts.length - 1];
    }

    // Call before checking for the existence of a local cover file and before
    // starting to download a remote file.  Waits until |filename| isn't in
    // |mFilesBeingLoaded| and then adds it.  Must be matched by a call to
    // finishLoad().
    private void startLoad(String filename) {
        lock.lock();
        try {
            while (filesBeingLoaded.contains(filename)) loadFinishedCond.await();
            filesBeingLoaded.add(filename);
        } catch (InterruptedException e) {
            // !#!@$#!$#!@
        } finally {
            lock.unlock();
        }
    }

    // Call after checking for the existence of a local file or after completing
    // a download (either successfully or unsuccessfully -- if unsuccessful, be
    // sure to remove the file first so that other threads don't try to use it).
    private void finishLoad(String filename) {
        lock.lock();
        try {
            if (!filesBeingLoaded.contains(filename))
                throw new RuntimeException(
                        "got report of finished load of unknown file " + filename);
            filesBeingLoaded.remove(filename);
            loadFinishedCond.signal();
        } finally {
            lock.unlock();
        }
    }
}
