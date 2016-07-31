// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

class FileCache implements Runnable {
    private static final String TAG = "FileCache";

    // How long should we hold the wifi lock after noticing that there are no current downloads?
    private static final long RELEASE_WIFI_LOCK_DELAY_SEC = 600;

    interface Listener {
        void onCacheDownloadError(FileCacheEntry entry, String reason);
        void onCacheDownloadFail(FileCacheEntry entry, String reason);
        void onCacheDownloadProgress(FileCacheEntry entry, long downloadedBytes, long elapsedMs);
        void onCacheDownloadComplete(FileCacheEntry entry);
        void onCacheEviction(FileCacheEntry entry);
    }

    // Status returned by DownloadTask's startDownload() and writeFile() methods.
    // Up here because only static internal classes can define enums.
    private enum DownloadStatus {
        SUCCESS,
        ABORTED,
        RETRYABLE_ERROR,
        FATAL_ERROR
    }

    // Current state of our use of the wifi connection.
    private enum WifiState {
        // We have an active download.
        ACTIVE,

        // No active downlodads, but we're waiting for another one to start before releasing the lock.
        WAITING,

        // No active downloads and the lock is released.
        INACTIVE,
    }

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Listener mListener;
    private final Downloader mDownloader;
    private final NetworkHelper mNetworkHelper;

    // Are we ready to service requests?  This is blocked on |mDb| being initialized.
    private boolean mIsReady = false;
    private final Lock mIsReadyLock = new ReentrantLock();
    private final Condition mIsReadyCond = mIsReadyLock.newCondition();

    // Directory where we write music files.
    private File mMusicDir;

    // Used to run tasks on our own thread.
    private Handler mHandler;

    // Song IDs of entries that are currently being downloaded.
    private HashSet mInProgressSongIds = new HashSet();

    // Song IDs of entries that shouldn't be purged from the cache.
    private HashSet mPinnedSongIds = new HashSet();

    // Persistent information about cached items.
    private FileCacheDatabase mDb = null;

    private WifiState mWifiState;
    private final WifiLock mWifiLock;
    private Runnable mUpdateWifiLockTask = null;

    FileCache(Context context, Listener listener, Downloader downloader, NetworkHelper networkHelper) {
        mContext = context;
        mListener = listener;
        mDownloader = downloader;
        mNetworkHelper = networkHelper;

        mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);

        mWifiLock = ((WifiManager) mContext.getSystemService(Context.WIFI_SERVICE)).createWifiLock(
            WifiManager.WIFI_MODE_FULL, mContext.getString(R.string.app_name));
        mWifiLock.setReferenceCounted(false);
    }

    @Override
    public void run() {
        String state = Environment.getExternalStorageState();
        if (!state.equals(Environment.MEDIA_MOUNTED)) {
            Log.e(TAG, "media has state " + state + "; we need " + Environment.MEDIA_MOUNTED);
        }

        mMusicDir = mContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC);

        mDb = new FileCacheDatabase(mContext, mMusicDir.getPath());
        Looper.prepare();
        mHandler = new Handler();

        mIsReadyLock.lock();
        mIsReady = true;
        mIsReadyCond.signal();
        mIsReadyLock.unlock();

        Looper.loop();
    }

    public void quit() {
        synchronized(mInProgressSongIds) {
            mInProgressSongIds.clear();
        }
        waitUntilReady();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Looper.myLooper().quit();
            }
        });
        mDb.quit();
    }

    // Update the recorded last time that a particular song was accessed.
    public void updateLastAccessTime(long songId) {
        waitUntilReady();
        mDb.updateLastAccessTime(songId);
    }

    // Get the entry corresponding to a particular song.
    // Returns null if the URL isn't cached.
    public FileCacheEntry getEntry(long songId) {
        waitUntilReady();
        return mDb.getEntry(songId);
    }

    public List<FileCacheEntry> getAllFullyCachedEntries() {
        waitUntilReady();
        return mDb.getAllFullyCachedEntries();
    }

    // Abort a previously-started download.
    public void abortDownload(long songId) {
        synchronized(mInProgressSongIds) {
            if (!mInProgressSongIds.contains(songId))
                Log.e(TAG, "tried to abort nonexistent download of song " + songId);
            mInProgressSongIds.remove(songId);
        }
        updateWifiLock();
        Log.d(TAG, "canceled download of song " + songId);
    }

    // Get the total size of all cached data.
    public long getTotalCachedBytes() {
        waitUntilReady();
        return mDb.getTotalCachedBytes();
    }

    // Clear all cached data.
    public void clear() {
        waitUntilReady();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized(mInProgressSongIds) {
                    mInProgressSongIds.clear();
                }
                updateWifiLock();
                clearPinnedSongIds();

                List<Long> songIds = mDb.getSongIdsByAge();
                for (long songId : songIds) {
                    FileCacheEntry entry = mDb.getEntry(songId);
                    mDb.removeEntry(songId);
                    File file = entry.getLocalFile();
                    file.delete();
                    mListener.onCacheEviction(entry);
                }

                // Shouldn't be anything there, but whatever.
                for (File file : mMusicDir.listFiles())
                    file.delete();
            }
        });
    }

    // Download a song to the cache.  Returns the cache entry, or null if the song is
    // already being downloaded.
    public FileCacheEntry downloadSong(Song song) {
        waitUntilReady();
        final long songId = song.getSongId();

        synchronized(mInProgressSongIds) {
            if (mInProgressSongIds.contains(songId))
                return null;
            mInProgressSongIds.add(songId);
        }

        FileCacheEntry entry = mDb.getEntry(songId);
        if (entry == null) {
            entry = mDb.addEntry(songId);
        } else {
            mDb.updateLastAccessTime(songId);
        }

        Log.d(TAG, "posting download of song " + songId + " from " +
              song.getUrl().toString() + " to " + entry.getLocalFile().getPath());
        mHandler.post(new DownloadTask(entry, song.getUrl()));
        return entry;
    }

    public void pinSongId(long songId) {
        synchronized (mPinnedSongIds) {
            mPinnedSongIds.add(songId);
        }
    }

    public void clearPinnedSongIds() {
        synchronized (mPinnedSongIds) {
            mPinnedSongIds.clear();
        }
    }

    private class DownloadTask implements Runnable {
        private static final String TAG = "FileCache.DownloadTask";

        // Maximum number of seconds we'll wait before retrying after failure.  Never give up!
        private static final int MAX_BACKOFF_SEC = 60;

        // Size of buffer used to write data to disk, in bytes.
        private static final int BUFFER_SIZE = 8 * 1024;

        // Cache entry that we're updating.
        private final FileCacheEntry mEntry;

        // File that we're downloading.
        private final URL mUrl;

        // How long we should wait before retrying after an error, in milliseconds.
        // We start at 0 but back off exponentially after errors where we haven't made any progress.
        private int mBackoffTimeMs = 0;

        // Reason for the failure.
        private String mReason;

        private HttpURLConnection mConn;
        private FileOutputStream mOutputStream = null;

        public DownloadTask(FileCacheEntry entry, URL url) {
            mEntry = entry;
            mUrl = url;
        }

        @Override
        public void run() {
            if (!isActive())
                return;

            if (!mNetworkHelper.isNetworkAvailable()) {
                mReason = mContext.getString(R.string.network_is_unavailable);
                handleFailure();
                return;
            }

            updateWifiLock();

            while (true) {
                try {
                    if (mBackoffTimeMs > 0) {
                        Log.d(TAG, "sleeping for " + mBackoffTimeMs + " ms before retrying download " + mEntry.getSongId());
                        SystemClock.sleep(mBackoffTimeMs);
                    }

                    // If the file is fully downloaded already, report success.
                    if (mEntry.isFullyCached()) {
                        handleSuccess();
                        return;
                    }

                    // Start the download.
                    switch (startDownload()) {
                        case SUCCESS:
                            break;
                        case ABORTED:
                            return;
                        case RETRYABLE_ERROR:
                            mListener.onCacheDownloadError(mEntry, mReason);
                            updateBackoffTime(false);
                            continue;
                        case FATAL_ERROR:
                            handleFailure();
                            return;
                    }

                    if (!isActive())
                        return;

                    switch (writeFile()) {
                        case SUCCESS:
                            break;
                        case ABORTED:
                            return;
                        case RETRYABLE_ERROR:
                            mListener.onCacheDownloadError(mEntry, mReason);
                            updateBackoffTime(true);
                            continue;
                        case FATAL_ERROR:
                            handleFailure();
                            return;
                    }

                    handleSuccess();
                    return;
                } finally {
                    if (mConn != null) {
                        mConn.disconnect();
                        mConn = null;
                    }
                    if (mOutputStream != null) {
                        try {
                            mOutputStream.close();
                        } catch (IOException e) {
                            Log.e(TAG, "got IO exception while closing output stream: " + e);
                        }
                        mOutputStream = null;
                    }
                }
            }
        }

        private DownloadStatus startDownload() {
            try {
                Map<String, String> headers = new HashMap<String, String>();
                if (mEntry.getCachedBytes() > 0 && mEntry.getCachedBytes() < mEntry.getTotalBytes()) {
                    Log.d(TAG, "attempting to resume download at byte " + mEntry.getCachedBytes());
                    headers.put("Range", String.format("bytes=%d-", mEntry.getCachedBytes()));
                }

                mConn = mDownloader.download(mUrl, "GET", Downloader.AuthType.STORAGE, headers);
                final int status = mConn.getResponseCode();
                Log.d(TAG, "got " + status + " from server");
                if (status != 200 && status != 206) {
                    mReason = "Got status code " + status;
                    return DownloadStatus.FATAL_ERROR;
                }

                // Update the cache entry with the total file size.
                if (status == 200) {
                    if (mConn.getContentLength() <= 1) {
                        mReason = "Got invalid content length " + mConn.getContentLength();
                        return DownloadStatus.FATAL_ERROR;
                    }
                    mDb.setTotalBytes(mEntry.getSongId(), mConn.getContentLength());
                }
            } catch (IOException e) {
                mReason = "IO error while starting download";
                return DownloadStatus.RETRYABLE_ERROR;
            }

            return DownloadStatus.SUCCESS;
        }

        private DownloadStatus writeFile() {
            // Make space for whatever we're planning to download.
            if (!makeSpace(mConn.getContentLength())) {
                mReason = "Unable to make space for " + mConn.getContentLength() + "-byte download";
                return DownloadStatus.FATAL_ERROR;
            }

            File file = mEntry.getLocalFile();
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    mReason = "Unable to create local file " + file.getPath();
                    return DownloadStatus.FATAL_ERROR;
                }
            }

            int statusCode;
            try {
                statusCode = mConn.getResponseCode();
            } catch (IOException e) {
                mReason = "Unable to get status code";
                return DownloadStatus.RETRYABLE_ERROR;
            }

            try {
                // TODO: Also check the Content-Range header.
                boolean append = (statusCode == 206);
                mOutputStream = new FileOutputStream(file, append);
                if (!append) {
                    mEntry.setCachedBytes(0);
                }
            } catch (FileNotFoundException e) {
                mReason = "Unable to create output stream to local file";
                return DownloadStatus.FATAL_ERROR;
            }

            final long maxBytesPerSecond = Long.valueOf(
                mPrefs.getString(NupPreferences.DOWNLOAD_RATE,
                                 NupPreferences.DOWNLOAD_RATE_DEFAULT)) * 1024;

            ProgressReporter reporter = new ProgressReporter(mEntry);
            Thread reporterThread = new Thread(reporter, "FileCache.ProgressReporter." + mEntry.getSongId());
            reporterThread.start();

            try {
                Date startDate = new Date();

                int bytesRead = 0, bytesWritten = 0;
                byte[] buffer = new byte[BUFFER_SIZE];
                InputStream inputStream = mConn.getInputStream();
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    if (!isActive()) {
                        return DownloadStatus.ABORTED;
                    }

                    mOutputStream.write(buffer, 0, bytesRead);
                    bytesWritten += bytesRead;

                    Date now = new Date();
                    long elapsedMs = now.getTime() - startDate.getTime();
                    mEntry.incrementCachedBytes(bytesRead);
                    reporter.update(bytesWritten, elapsedMs);

                    if (maxBytesPerSecond > 0) {
                        long expectedMs = (long) (bytesWritten / (float) maxBytesPerSecond * 1000);
                        if (elapsedMs < expectedMs)
                            SystemClock.sleep(expectedMs - elapsedMs);
                    }
                }
                Date endDate = new Date();
                Log.d(TAG, "finished download of song " + mEntry.getSongId() + " (" + bytesWritten + " bytes to " +
                      file.getAbsolutePath() + " in " + (endDate.getTime() - startDate.getTime()) + " ms)");

                // I see this happen when I kill the server midway through the download.
                if (bytesWritten != mConn.getContentLength()) {
                    mReason = "Expected " + mConn.getContentLength() + " bytes but got " + bytesWritten;
                    return DownloadStatus.RETRYABLE_ERROR;
                }
            } catch (IOException e) {
                mReason = "IO error while reading body";
                return DownloadStatus.RETRYABLE_ERROR;
            } finally {
                reporter.quit();
                try { reporterThread.join(); } catch (InterruptedException e) { /* !~$#%$#!$#!~$#! */ }
            }
            return DownloadStatus.SUCCESS;
        }

        private void handleFailure() {
            synchronized(mInProgressSongIds) {
                mInProgressSongIds.remove(mEntry.getSongId());
            }
            updateWifiLock();
            mListener.onCacheDownloadFail(mEntry, mReason);
        }

        private void handleSuccess() {
            synchronized(mInProgressSongIds) {
                mInProgressSongIds.remove(mEntry.getSongId());
            }
            updateWifiLock();
            mListener.onCacheDownloadComplete(mEntry);
        }

        private void updateBackoffTime(boolean madeProgress) {
            if (madeProgress) {
                mBackoffTimeMs = 0;
            } else if (mBackoffTimeMs == 0) {
                mBackoffTimeMs = 1000;
            } else {
                mBackoffTimeMs = Math.min(mBackoffTimeMs * 2, MAX_BACKOFF_SEC * 1000);
            }
        }

        // Is this download currently active, or has it been cancelled?
        private boolean isActive() {
            return isDownloadActive(mEntry.getSongId());
        }

        private class ProgressReporter implements Runnable {
            private static final String TAG = "FileCache.ProgressReporter";
            private static final long PROGRESS_REPORT_MS = 500;

            private final FileCacheEntry mEntry;
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

            public void update(final long downloadedBytes, final long elapsedMs) {
                synchronized (this) {
                    mDownloadedBytes = downloadedBytes;
                    mElapsedMs = elapsedMs;

                    if (mHandler != null && mTask == null) {
                        mTask = new Runnable() {
                            @Override
                            public void run() {
                                synchronized (ProgressReporter.this) {
                                    mListener.onCacheDownloadProgress(mEntry, mDownloadedBytes, mElapsedMs);
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

    // Wait until |mIsReady| becomes true.
    private void waitUntilReady() {
        mIsReadyLock.lock();
        try {
            while (!mIsReady)
                mIsReadyCond.await();
        } catch (InterruptedException e) {
        } finally {
            mIsReadyLock.unlock();
        }
    }

    private boolean isDownloadActive(long songId) {
        synchronized(mInProgressSongIds) {
            return mInProgressSongIds.contains(songId);
        }
    }

    // Try to make room for |neededBytes| in the cache.
    // We delete the least-recently-accessed files first, ignoring ones
    // that are currently being downloaded or are pinned.
    synchronized private boolean makeSpace(long neededBytes) {
        long maxBytes = Long.valueOf(
            mPrefs.getString(NupPreferences.CACHE_SIZE,
                             NupPreferences.CACHE_SIZE_DEFAULT)) * 1024 * 1024;
        long availableBytes = maxBytes - getTotalCachedBytes();

        if (neededBytes <= availableBytes)
            return true;

        Log.d(TAG, "need to make space for " + neededBytes + " bytes (" + availableBytes + " available)");
        List<Long> songIds = mDb.getSongIdsByAge();
        for (long songId : songIds) {
            if (neededBytes <= availableBytes)
                break;

            if (isDownloadActive(songId))
                continue;

            synchronized(mPinnedSongIds) {
                if (mPinnedSongIds.contains(songId))
                    continue;
            }

            FileCacheEntry entry = mDb.getEntry(songId);
            File file = entry.getLocalFile();
            Log.d(TAG, "deleting song " + songId + " (" + file.getPath() + ", " + file.length() + " bytes)");
            availableBytes += file.length();
            file.delete();
            mDb.removeEntry(songId);
            mListener.onCacheEviction(entry);
        }

        return neededBytes <= availableBytes;
    }

    // Acquire or release the wifi lock, depending on our current state.
    private void updateWifiLock() {
        if (mUpdateWifiLockTask != null) {
            mHandler.removeCallbacks(mUpdateWifiLockTask);
            mUpdateWifiLockTask = null;
        }

        boolean active = false;
        synchronized(mInProgressSongIds) {
            active = !mInProgressSongIds.isEmpty();
        }

        if (active) {
            Log.d(TAG, "acquiring wifi lock");
            mWifiState = WifiState.ACTIVE;
            mWifiLock.acquire();
        } else {
            if (mWifiState == WifiState.ACTIVE) {
                Log.d(TAG, "waiting " + RELEASE_WIFI_LOCK_DELAY_SEC + " seconds before releasing wifi lock");
                mWifiState = WifiState.WAITING;
                mUpdateWifiLockTask = new Runnable() {
                    @Override
                    public void run() {
                        updateWifiLock();
                    }
                };
                mHandler.postDelayed(mUpdateWifiLockTask, RELEASE_WIFI_LOCK_DELAY_SEC * 1000);
            } else {
                Log.d(TAG, "releasing wifi lock");
                mWifiState = WifiState.INACTIVE;
                mWifiLock.release();
            }
        }
    }
}
