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

class CoverLoader {
    private static final String TAG = "CoverLoader";

    // Size of buffer used to write data to disk, in bytes.
    private static final int BUFFER_SIZE = 8 * 1024;

    // Application context.
    private final Context mContext;

    // Directory where we write cover images.
    private final File mCoverDir;

    public CoverLoader(Context context) {
        mContext = context;

        // FIXME: display a message here
        String state = Environment.getExternalStorageState();
        if (!state.equals(Environment.MEDIA_MOUNTED))
            Log.e(TAG, "media has state " + state + "; we need " + Environment.MEDIA_MOUNTED);

        mCoverDir = new File(mContext.getExternalCacheDir(), "covers");
    }

    public Bitmap loadCover(String artist, String album) {
        // Ensure that the cover dir exists.
        mCoverDir.mkdirs();

        File coverFile = lookForLocalCover(artist, album);
        if (coverFile == null)
            coverFile = downloadCover(artist, album);

        if (coverFile == null || !coverFile.exists())
            return null;

        return BitmapFactory.decodeFile(coverFile.getPath());
    }

    private File lookForLocalCover(final String artist, final String album) {
        File file = new File(mCoverDir, (artist + "-" + album + ".jpg").replace('/', '%'));
        if (!file.exists()) {
            String[] matchingFilenames = mCoverDir.list(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String filename) {
                    return filename.matches("^.+-" + album.replace('/', '%') + "\\.jpg$");
                }
            });
            file = (matchingFilenames.length == 1) ?  new File(mCoverDir, matchingFilenames[0]) : null;
        }

        if (file != null)
            Log.d(TAG, "found local cover " + file.getName());
        return file;
    }

    private File downloadCover(String artist, String album) {
        String[] error = new String[1];
        String filename = Download.downloadString(
            mContext,
            "/find_cover",
            "artist=" + Uri.encode(artist) + "&album=" + Uri.encode(album),
            error);
        if (filename == null || filename.isEmpty())
            return null;

        // No monkey business.
        filename = filename.replace("/", "%");

        File file = new File(mCoverDir, filename);
        try {
            file.createNewFile();
        } catch (IOException e) {
            Log.e(TAG, "unable to create file " + file.getPath() + ": " + e);
            return null;
        }

        FileOutputStream outputStream;
        try {
            outputStream = new FileOutputStream(file);
        } catch (java.io.FileNotFoundException e) {
            Log.e(TAG, "unable to create output stream " + file.getPath() + ": " + e);
            return null;
        }

        Log.d(TAG, "fetching remote cover " + filename);
        DownloadRequest request = null;
        DownloadResult result = null;
        try {
            request = new DownloadRequest(mContext, DownloadRequest.Method.GET, "/cover/" + Uri.encode(filename), null);
            result = Download.startDownload(request);
            if (result.getStatusCode() != 200)
                throw new IOException("got status code " + result.getStatusCode() + " while fetching " + filename);

            byte[] buffer = new byte[BUFFER_SIZE];
            InputStream inputStream = result.getEntity().getContent();
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1)
                outputStream.write(buffer, 0, bytesRead);
        } catch (DownloadRequest.PrefException e) {
            Log.e(TAG, "got pref exception while downloading " + filename + ": " + e);
            return null;
        } catch (org.apache.http.HttpException e) {
            Log.e(TAG, "got http error while downloading " + filename + ": " + e);
            return null;
        } catch (IOException e) {
            Log.e(TAG, "got io error while downloading " + filename + ": " + e);
            return null;
        } finally {
            try {
                outputStream.close();
            } catch (IOException e) {
                // !@#!@$!@
            }
            if (result != null)
                result.close();
        }

        return file;
    }
}
