// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FilenameFilter;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.HashSet;

class CoverLoader {
    private static final String TAG = "CoverLoader";

    // Size of buffer used to write data to disk, in bytes.
    private static final int BUFFER_SIZE = 8 * 1024;

    // Application context.
    private final Context mContext;

    // Directory where we write cover images.
    private final File mCoverDir;

    // Guards |mFilesBeingLoaded|.
    private final Lock mLock = new ReentrantLock();

    // Signalled when a change has been made to |mFilesBeingLoaded|.
    private final Condition mLoadFinishedCond = mLock.newCondition();

    // Names of cover files that we're currently fetching.
    private HashSet mFilesBeingLoaded = new HashSet();

    public CoverLoader(Context context) {
        mContext = context;

        // FIXME: display a message here
        String state = Environment.getExternalStorageState();
        if (!state.equals(Environment.MEDIA_MOUNTED))
            Log.e(TAG, "media has state " + state + "; we need " + Environment.MEDIA_MOUNTED);

        mCoverDir = new File(mContext.getExternalCacheDir(), "covers");
    }

    // Load the cover for a song's artist and album.  Tries to find it locally
    // first; then goes to the server.  Returns null if unsuccessful.
    public Bitmap loadCover(String artist, String album) {
        // Ensure that the cover dir exists.
        mCoverDir.mkdirs();

        File file = lookForLocalCover(artist, album);
        if (file != null) {
            Log.d(TAG, "found local file " + file.getName());
        } else {
            file = downloadCover(artist, album);
            if (file != null)
                Log.d(TAG, "fetched remote file " + file.getName());
        }

        if (file == null || !file.exists())
            return null;

        return BitmapFactory.decodeFile(file.getPath());
    }

    private File lookForLocalCover(final String artist, final String album) {
        String filename = Util.escapeFilename(artist + "-" + album + ".jpg");
        startLoad(filename);
        try {
            File file = new File(mCoverDir, filename);
            if (file.exists())
                return file;
        } finally {
            finishLoad(filename);
        }

        String[] matchingFilenames = mCoverDir.list(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String filename) {
                if (!filename.endsWith(Util.escapeFilename("-" + album + ".jpg")))
                    return false;

                startLoad(filename);
                try {
                    return new File(dir, filename).exists();
                } finally {
                    finishLoad(filename);
                }
            }
        });
        return (matchingFilenames.length == 1) ?  new File(mCoverDir, matchingFilenames[0]) : null;
    }

    private File downloadCover(String artist, String album) {
        String[] error = new String[1];
        String remoteFilename = Download.downloadString(
            mContext,
            "/find_cover",
            "artist=" + Uri.encode(artist) + "&album=" + Uri.encode(album),
            error);
        // No monkey business.
        if (remoteFilename == null || remoteFilename.isEmpty())
            return null;

        String filename = Util.escapeFilename(remoteFilename);
        startLoad(filename);

        boolean success = false;
        File file = new File(mCoverDir, filename);
        FileOutputStream outputStream = null;
        DownloadRequest request = null;
        DownloadResult result = null;

        try {
            // Check if another thread downloaded it while we were waiting.
            if (file.exists()) {
                success = true;
                return file;
            }

            file.createNewFile();
            outputStream = new FileOutputStream(file);

            request = new DownloadRequest(mContext, DownloadRequest.Method.GET, "/cover/" + Uri.encode(remoteFilename), null);
            result = Download.startDownload(request);
            if (result.getStatusCode() != 200)
                throw new IOException("got status code " + result.getStatusCode());

            byte[] buffer = new byte[BUFFER_SIZE];
            InputStream inputStream = result.getEntity().getContent();
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1)
                outputStream.write(buffer, 0, bytesRead);
            success = true;
        } catch (DownloadRequest.PrefException e) {
            Log.e(TAG, "got pref exception while fetching " + remoteFilename + ": " + e);
        } catch (org.apache.http.HttpException e) {
            Log.e(TAG, "got HTTP error while fetching " + remoteFilename + ": " + e);
        } catch (IOException e) {
            Log.e(TAG, "got IO error while fetching " + remoteFilename + ": " + e);
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    // !@#!@$!@
                }
            }

            if (result != null)
                result.close();
            if (!success && file.exists())
                file.delete();

            finishLoad(filename);
        }

        return success ? file : null;
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