// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

class FileCache implements Runnable {
    private static final String TAG = "FileCache";

    interface Listener {
        void onCacheDownloadError(FileCacheEntry entry, String reason);
        void onCacheDownloadFail(FileCacheEntry entry, String reason);
        void onCacheDownloadProgress(FileCacheEntry entry, long diskBytes, long receivedBytes, long elapsedMs);
        void onCacheDownloadComplete(FileCacheEntry entry);
        void onCacheEviction(FileCacheEntry entry);
    }

    // Application context.
    private final Context mContext;

    // Directory where we write music files.
    private final File mMusicDir;

    // Used to run tasks on our own thread.
    private Handler mHandler;

    // IDs of entries that are currently being downloaded.
    private HashSet mInProgressIds = new HashSet();

    // IDs of entries that shouldn't be purged from the cache.
    private HashSet mPinnedIds = new HashSet();

    // Persistent information about cached items.
    private FileCacheDatabase mDb;

    private SharedPreferences mPrefs;

    private final Listener mListener;

    FileCache(Context context, Listener listener) {
        mContext = context;
        mListener = listener;

        String state = Environment.getExternalStorageState();
        if (!state.equals(Environment.MEDIA_MOUNTED))
            Log.e(TAG, "media has state " + state + "; we need " + Environment.MEDIA_MOUNTED);

        mMusicDir = mContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        mDb = new FileCacheDatabase(mContext);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    @Override
    public void run() {
        Looper.prepare();
        mHandler = new Handler();
        Looper.loop();
    }

    public void quit() {
        synchronized(mInProgressIds) {
            mInProgressIds.clear();
        }
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Looper.myLooper().quit();
            }
        });
    }

    // Get the entry corresponding to a particular cached URL.
    // Returns null if the URL isn't cached.
    public FileCacheEntry getEntry(String urlPath) {
        return mDb.getEntryForRemotePath(urlPath);
    }

    // Abort a previously-started download.
    public void abortDownload(int id) {
        synchronized(mInProgressIds) {
            if (!mInProgressIds.contains(id)) {
                Log.e(TAG, "tried to abort nonexistent download " + id);
                return;
            }
            mInProgressIds.remove(id);
            Log.d(TAG, "canceled download " + id);
        }
    }

    // Get the total size of all cached data.
    public long getDataBytes() {
        long size = 0;
        for (File file : mMusicDir.listFiles())
            size += file.length();
        return size;
    }

    // Clear all cached data.
    public void clear() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized(mInProgressIds) {
                    mInProgressIds.clear();
                }
                clearPinnedIds();

                List<Integer> ids = mDb.getIdsByAge();
                for (int id : ids) {
                    FileCacheEntry entry = mDb.getEntryById(id);
                    mDb.removeEntry(id);
                    File file = new File(entry.getLocalPath());
                    file.delete();
                    mListener.onCacheEviction(entry);
                }

                // Shouldn't be anything there, but whatever.
                for (File file : mMusicDir.listFiles())
                    file.delete();
            }
        });
    }

    // Download a URL to the cache.  Returns the cache entry, or null if the URL is
    // already being downloaded.
    public FileCacheEntry downloadFile(String urlPath, String filenameSuggestion) {
        FileCacheEntry entry = mDb.getEntryForRemotePath(urlPath);
        if (entry == null) {
            String localPath = new File(mMusicDir, filenameSuggestion).getAbsolutePath();
            int id = mDb.addEntry(urlPath, localPath);
            entry = mDb.getEntryForRemotePath(urlPath);
        } else {
            mDb.updateLastAccessTime(entry.getId());
        }

        final int id = entry.getId();
        synchronized(mInProgressIds) {
            if (mInProgressIds.contains(id))
                return null;
            mInProgressIds.add(id);
        }

        Log.d(TAG, "posting download " + id + " of " + urlPath + " to " + entry.getLocalPath());
        mHandler.post(new DownloadTask(entry));
        return entry;
    }

    public void pinId(int id) {
        synchronized (mPinnedIds) {
            mPinnedIds.add(id);
        }
    }

    public void clearPinnedIds() {
        synchronized (mPinnedIds) {
            mPinnedIds.clear();
        }
    }

    private class DownloadTask implements Runnable {
        private static final String TAG = "FileCache.DownloadTask";

        // Size of buffer used to write data to disk, in bytes.
        private static final int BUFFER_SIZE = 8 * 1024;

        // Maximum number of bytes that we'll download per second, or 0 to disable throttling.
        private static final int MAX_BYTES_PER_SECOND = 0;

        // Maximum number of times that we'll attempt a download without making any progress before we give up.
        private static final int MAX_DOWNLOAD_ATTEMPTS = 3;

        // Cache entry that we're downloading.
        private FileCacheEntry mEntry;

        // The entry's ID.
        private final int mId;

        private DownloadRequest mRequest;
        private DownloadResult mResult = null;

        private FileOutputStream mOutputStream;

        public DownloadTask(FileCacheEntry entry) {
            mEntry = entry;
            mId = entry.getId();
        }

        // TODO: This needs to be broken up into smaller pieces.
        @Override
        public void run() {
            if (!isDownloadActive(mId))
                return;

            int numAttempts = 0;
            while (numAttempts < MAX_DOWNLOAD_ATTEMPTS) {
                numAttempts++;

                // If the file was already downloaded, report success.
                File file = new File(mEntry.getLocalPath());
                long existingLength = file.exists() ? file.length() : 0;
                if (mEntry.getContentLength() > 0 && existingLength == mEntry.getContentLength()) {
                    handleSuccess();
                    return;
                }

                try {
                    mRequest = new DownloadRequest(mContext, DownloadRequest.Method.GET, mEntry.getRemotePath(), null);
                } catch (DownloadRequest.PrefException e) {
                    handleFailure("Invalid server info settings");
                    return;
                }
                if (existingLength > 0 && existingLength < mEntry.getContentLength()) {
                    Log.d(TAG, "attempting to resume download at byte " + existingLength);
                    mRequest.setHeader("Range", "bytes=" + new Long(existingLength).toString() + "-");
                }

                try {
                    mResult = Download.startDownload(mRequest);
                } catch (org.apache.http.HttpException e) {
                    handleError("HTTP error while connecting");
                    continue;
                } catch (IOException e) {
                    handleError("IO error while connecting");
                    continue;
                }

                Log.d(TAG, "got " + mResult.getStatusCode() + " from server");
                if (mResult.getStatusCode() != 200 && mResult.getStatusCode() != 206) {
                    handleFailure("Got status code " + mResult.getStatusCode());
                    return;
                }

                if (!isDownloadActive(mId)) {
                    mResult.close();
                    return;
                }

                // Update the cache entry with the total content size.
                if (existingLength == 0) {
                    mDb.setContentLength(mId, mResult.getEntity().getContentLength());
                    mEntry = mDb.getEntryForRemotePath(mEntry.getRemotePath());
                }

                // Make space for whatever we're planning to download.
                if (!makeSpace(mResult.getEntity().getContentLength())) {
                    handleFailure("Unable to make space for " + mResult.getEntity().getContentLength() + "-byte download");
                    return;
                }

                if (!file.exists()) {
                    file.getParentFile().mkdirs();
                    try {
                        file.createNewFile();
                    } catch (IOException e) {
                        handleFailure("Unable to create local file");
                        return;
                    }
                }

                try {
                    // TODO: Also check the Content-Range header.
                    boolean append = (mResult.getStatusCode() == 206);
                    mOutputStream = new FileOutputStream(file, append);
                } catch (java.io.FileNotFoundException e) {
                    handleFailure("Unable to create output stream to local file");
                    return;
                }

                ProgressReporter reporter = new ProgressReporter(mEntry);
                Thread reporterThread = new Thread(reporter, "FileCache.ProgressReporter" + mId);
                reporterThread.start();

                try {
                    Date startDate = new Date();

                    int bytesRead = 0, bytesWritten = 0;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    InputStream inputStream = mResult.getEntity().getContent();
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        if (!isDownloadActive(mId)) {
                            mResult.close();
                            return;
                        }

                        mOutputStream.write(buffer, 0, bytesRead);
                        bytesWritten += bytesRead;

                        // Well, we made some progress, at least.  Reset the attempt counter.
                        numAttempts = 0;

                        Date now = new Date();
                        long elapsedMs = now.getTime() - startDate.getTime();
                        reporter.update(existingLength + bytesWritten, bytesWritten, elapsedMs);

                        if (MAX_BYTES_PER_SECOND > 0) {
                            long expectedMs = (long) (bytesWritten / (float) MAX_BYTES_PER_SECOND * 1000);
                            if (elapsedMs < expectedMs)
                                SystemClock.sleep(expectedMs - elapsedMs);
                        }
                    }
                    Date endDate = new Date();
                    Log.d(TAG, "finished download " + mId + " (" + bytesWritten + " bytes to " +
                          file.getAbsolutePath() + " in " + (endDate.getTime() - startDate.getTime()) + " ms)");
                } catch (IOException e) {
                    handleError("IO error while reading body");
                    continue;
                } finally {
                    reporter.quit();
                    try {
                        reporterThread.join();
                    } catch (InterruptedException e) {
                    }
                }

                handleSuccess();
                return;
            }

            handleFailure("Giving up after " + MAX_DOWNLOAD_ATTEMPTS + " attempt" +
                          (MAX_DOWNLOAD_ATTEMPTS == 1 ? "" : "s") + " without progress");
        }

        private void handleError(String reason) {
            if (mResult != null)
                mResult.close();
            mListener.onCacheDownloadError(mEntry, reason);
        }

        private void handleFailure(String reason) {
            if (mResult != null)
                mResult.close();
            synchronized(mInProgressIds) {
                mInProgressIds.remove(mId);
            }
            mListener.onCacheDownloadFail(mEntry, reason);
        }

        private void handleSuccess() {
            if (mResult != null)
                mResult.close();
            synchronized(mInProgressIds) {
                mInProgressIds.remove(mId);
            }
            mListener.onCacheDownloadComplete(mEntry);
        }

        private class ProgressReporter implements Runnable {
            private static final String TAG = "FileCache.ProgressReporter";
            private static final long PROGRESS_REPORT_MS = 250;

            private final FileCacheEntry mEntry;
            private long mDiskBytes = 0;
            private long mDownloadedBytes = 0;
            private long mElapsedMs = 0;
            private Handler mHandler;
            private Runnable mTask;
            private Date mLastReportDate;
            private boolean mShouldQuit = false;

            ProgressReporter(final FileCacheEntry entry) {
                mEntry = entry;
            }

            @Override
            public void run() {
                Looper.prepare();
                synchronized (this) {
                    if (mShouldQuit)
                        return;
                    mHandler = new Handler();
                }
                Looper.loop();
            }

            public void quit() {
                synchronized (this) {
                    // The thread hasn't started looping yet; tell it to exit before starting.
                    if (mHandler == null) {
                        mShouldQuit = true;
                        return;
                    }
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Looper.myLooper().quit();
                    }
                });
            }

            public void update(final long diskBytes, final long downloadedBytes, final long elapsedMs) {
                synchronized (this) {
                    mDiskBytes = diskBytes;
                    mDownloadedBytes = downloadedBytes;
                    mElapsedMs = elapsedMs;

                    if (mHandler != null && mTask == null) {
                        mTask = new Runnable() {
                            @Override
                            public void run() {
                                synchronized (ProgressReporter.this) {
                                    mListener.onCacheDownloadProgress(mEntry, mDiskBytes, mDownloadedBytes, mElapsedMs);
                                    mLastReportDate = new Date();
                                    mTask = null;
                                }
                            }
                        };

                        long delayMs = 0;
                        if (mLastReportDate != null) {
                            long timeSinceLastReportMs = new Date().getTime() - mLastReportDate.getTime();
                            delayMs = Math.max(PROGRESS_REPORT_MS - timeSinceLastReportMs, 0);
                        }
                        mHandler.postDelayed(mTask, delayMs);
                    }
                }
            }
        }
    }

    private boolean isDownloadActive(int id) {
        synchronized(mInProgressIds) {
            return mInProgressIds.contains(id);
        }
    }

    // Try to make room for |neededBytes| in the cache.
    // We delete the least-recently-accessed files first, ignoring ones
    // that are currently being downloaded or are pinned.
    synchronized private boolean makeSpace(long neededBytes) {
        long maxBytes = Long.valueOf(
            mPrefs.getString(NupPreferences.CACHE_SIZE,
                             NupPreferences.CACHE_SIZE_DEFAULT)) * 1024 * 1024;
        long availableBytes = maxBytes - getDataBytes();

        if (neededBytes <= availableBytes)
            return true;

        Log.d(TAG, "need to make space for " + neededBytes + " bytes (" + availableBytes + " available)");
        List<Integer> ids = mDb.getIdsByAge();
        for (int id : ids) {
            if (neededBytes <= availableBytes)
                break;

            if (isDownloadActive(id))
                continue;

            synchronized(mPinnedIds) {
                if (mPinnedIds.contains(id))
                    continue;
            }

            FileCacheEntry entry = mDb.getEntryById(id);
            File file = new File(entry.getLocalPath());
            Log.d(TAG, "deleting " + entry.getLocalPath() + " (" + file.length() + " bytes)");
            availableBytes += file.length();
            file.delete();
            mDb.removeEntry(id);
            mListener.onCacheEviction(entry);
        }

        return neededBytes <= availableBytes;
    }
}
