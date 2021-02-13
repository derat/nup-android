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
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class FileCache implements Runnable {
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
        // No active downloads, but waiting for another one to start before releasing the lock.
        WAITING,
        // No active downloads and the lock is released.
        INACTIVE,
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final Downloader downloader;
    private final NetworkHelper networkHelper;

    // Are we ready to service requests?  This is blocked on |mDb| being initialized.
    private boolean isReady = false;
    private final Lock isReadyLock = new ReentrantLock();
    private final Condition isReadyCond = isReadyLock.newCondition();

    // Directory where we write music files.
    private File musicDir;

    // Used to run tasks on our own thread.
    private Handler handler;

    // Song IDs of entries that are currently being downloaded.
    private HashSet inProgressSongIds = new HashSet();

    // Song IDs of entries that shouldn't be purged from the cache.
    private HashSet pinnedSongIds = new HashSet();

    // Persistent information about cached items.
    private FileCacheDatabase db = null;

    private WifiState wifiState;
    private final WifiLock wifiLock;
    private Runnable updateWifiLockTask = null;

    FileCache(
            Context context,
            Listener listener,
            Downloader downloader,
            NetworkHelper networkHelper) {
        this.context = context;
        this.listener = listener;
        this.downloader = downloader;
        this.networkHelper = networkHelper;

        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);

        this.wifiLock =
                ((WifiManager) this.context.getSystemService(Context.WIFI_SERVICE))
                        .createWifiLock(
                                WifiManager.WIFI_MODE_FULL,
                                this.context.getString(R.string.app_name));
        this.wifiLock.setReferenceCounted(false);
    }

    @Override
    public void run() {
        String state = Environment.getExternalStorageState();
        if (!state.equals(Environment.MEDIA_MOUNTED)) {
            Log.e(TAG, "media has state " + state + "; we need " + Environment.MEDIA_MOUNTED);
        }

        musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);

        db = new FileCacheDatabase(context, musicDir.getPath());
        Looper.prepare();
        handler = new Handler();

        isReadyLock.lock();
        isReady = true;
        isReadyCond.signal();
        isReadyLock.unlock();

        Looper.loop();
    }

    public void quit() {
        synchronized (inProgressSongIds) {
            inProgressSongIds.clear();
        }
        waitUntilReady();
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        Looper.myLooper().quit();
                    }
                });
        db.quit();
    }

    // Update the recorded last time that a particular song was accessed.
    public void updateLastAccessTime(long songId) {
        waitUntilReady();
        db.updateLastAccessTime(songId);
    }

    // Get the entry corresponding to a particular song.
    // Returns null if the URL isn't cached.
    public FileCacheEntry getEntry(long songId) {
        waitUntilReady();
        return db.getEntry(songId);
    }

    public List<FileCacheEntry> getAllFullyCachedEntries() {
        waitUntilReady();
        return db.getAllFullyCachedEntries();
    }

    // Abort a previously-started download.
    public void abortDownload(long songId) {
        synchronized (inProgressSongIds) {
            if (!inProgressSongIds.contains(songId))
                Log.e(TAG, "tried to abort nonexistent download of song " + songId);
            inProgressSongIds.remove(songId);
        }
        updateWifiLock();
        Log.d(TAG, "canceled download of song " + songId);
    }

    // Get the total size of all cached data.
    public long getTotalCachedBytes() {
        waitUntilReady();
        return db.getTotalCachedBytes();
    }

    // Clear all cached data.
    public void clear() {
        waitUntilReady();
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        synchronized (inProgressSongIds) {
                            inProgressSongIds.clear();
                        }
                        updateWifiLock();
                        clearPinnedSongIds();

                        List<Long> songIds = db.getSongIdsByAge();
                        for (long songId : songIds) {
                            FileCacheEntry entry = db.getEntry(songId);
                            db.removeEntry(songId);
                            File file = entry.getLocalFile();
                            file.delete();
                            listener.onCacheEviction(entry);
                        }

                        // Shouldn't be anything there, but whatever.
                        for (File file : musicDir.listFiles()) file.delete();
                    }
                });
    }

    // Download a song to the cache.  Returns the cache entry, or null if the song is
    // already being downloaded.
    public FileCacheEntry downloadSong(Song song) {
        waitUntilReady();
        final long songId = song.id;

        synchronized (inProgressSongIds) {
            if (inProgressSongIds.contains(songId)) return null;
            inProgressSongIds.add(songId);
        }

        FileCacheEntry entry = db.getEntry(songId);
        if (entry == null) {
            entry = db.addEntry(songId);
        } else {
            db.updateLastAccessTime(songId);
        }

        Log.d(
                TAG,
                "posting download of song "
                        + songId
                        + " from "
                        + song.url.toString()
                        + " to "
                        + entry.getLocalFile().getPath());
        handler.post(new DownloadTask(entry, song.url));
        return entry;
    }

    public void pinSongId(long songId) {
        synchronized (pinnedSongIds) {
            pinnedSongIds.add(songId);
        }
    }

    public void clearPinnedSongIds() {
        synchronized (pinnedSongIds) {
            pinnedSongIds.clear();
        }
    }

    private class DownloadTask implements Runnable {
        private static final String TAG = "FileCache.DownloadTask";

        // Maximum number of seconds we'll wait before retrying after failure.  Never give up!
        private static final int MAX_BACKOFF_SEC = 60;

        // Size of buffer used to write data to disk, in bytes.
        private static final int BUFFER_SIZE = 8 * 1024;

        // Cache entry that we're updating.
        private final FileCacheEntry entry;

        // File that we're downloading.
        private final URL url;

        // How long we should wait before retrying after an error, in milliseconds.
        // We start at 0 but back off exponentially after errors where we haven't made any progress.
        private int backoffTimeMs = 0;

        // Reason for the failure.
        private String reason;

        private HttpURLConnection conn;
        private FileOutputStream outputStream = null;

        public DownloadTask(FileCacheEntry entry, URL url) {
            this.entry = entry;
            this.url = url;
        }

        @Override
        public void run() {
            if (!isActive()) return;

            if (!networkHelper.isNetworkAvailable()) {
                reason = context.getString(R.string.network_is_unavailable);
                handleFailure();
                return;
            }

            updateWifiLock();

            while (true) {
                try {
                    if (backoffTimeMs > 0) {
                        Log.d(
                                TAG,
                                "sleeping for "
                                        + backoffTimeMs
                                        + " ms before retrying download "
                                        + entry.songId);
                        SystemClock.sleep(backoffTimeMs);
                    }

                    // If the file is fully downloaded already, report success.
                    if (entry.isFullyCached()) {
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
                            listener.onCacheDownloadError(entry, reason);
                            updateBackoffTime(false);
                            continue;
                        case FATAL_ERROR:
                            handleFailure();
                            return;
                    }

                    if (!isActive()) return;

                    switch (writeFile()) {
                        case SUCCESS:
                            break;
                        case ABORTED:
                            return;
                        case RETRYABLE_ERROR:
                            listener.onCacheDownloadError(entry, reason);
                            updateBackoffTime(true);
                            continue;
                        case FATAL_ERROR:
                            handleFailure();
                            return;
                    }

                    handleSuccess();
                    return;
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                        conn = null;
                    }
                    if (outputStream != null) {
                        try {
                            outputStream.close();
                        } catch (IOException e) {
                            Log.e(TAG, "got IO exception while closing output stream: " + e);
                        }
                        outputStream = null;
                    }
                }
            }
        }

        private DownloadStatus startDownload() {
            try {
                Map<String, String> headers = new HashMap<String, String>();
                if (entry.getCachedBytes() > 0 && entry.getCachedBytes() < entry.getTotalBytes()) {
                    Log.d(TAG, "attempting to resume download at byte " + entry.getCachedBytes());
                    headers.put("Range", String.format("bytes=%d-", entry.getCachedBytes()));
                }

                conn = downloader.download(url, "GET", Downloader.AuthType.STORAGE, headers);
                final int status = conn.getResponseCode();
                Log.d(TAG, "got " + status + " from server");
                if (status != 200 && status != 206) {
                    reason = "Got status code " + status;
                    return DownloadStatus.FATAL_ERROR;
                }

                // Update the cache entry with the total file size.
                if (status == 200) {
                    if (conn.getContentLength() <= 1) {
                        reason = "Got invalid content length " + conn.getContentLength();
                        return DownloadStatus.FATAL_ERROR;
                    }
                    db.setTotalBytes(entry.songId, conn.getContentLength());
                }
            } catch (IOException e) {
                reason = "IO error while starting download";
                return DownloadStatus.RETRYABLE_ERROR;
            }

            return DownloadStatus.SUCCESS;
        }

        private DownloadStatus writeFile() {
            // Make space for whatever we're planning to download.
            if (!makeSpace(conn.getContentLength())) {
                reason = "Unable to make space for " + conn.getContentLength() + "-byte download";
                return DownloadStatus.FATAL_ERROR;
            }

            File file = entry.getLocalFile();
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    reason = "Unable to create local file " + file.getPath();
                    return DownloadStatus.FATAL_ERROR;
                }
            }

            int statusCode;
            try {
                statusCode = conn.getResponseCode();
            } catch (IOException e) {
                reason = "Unable to get status code";
                return DownloadStatus.RETRYABLE_ERROR;
            }

            try {
                // TODO: Also check the Content-Range header.
                boolean append = (statusCode == 206);
                outputStream = new FileOutputStream(file, append);
                if (!append) entry.setCachedBytes(0);
            } catch (FileNotFoundException e) {
                reason = "Unable to create output stream to local file";
                return DownloadStatus.FATAL_ERROR;
            }

            final long maxBytesPerSecond =
                    Long.valueOf(
                                    prefs.getString(
                                            NupPreferences.DOWNLOAD_RATE,
                                            NupPreferences.DOWNLOAD_RATE_DEFAULT))
                            * 1024;

            ProgressReporter reporter = new ProgressReporter(entry);
            Thread reporterThread =
                    new Thread(reporter, "FileCache.ProgressReporter." + entry.songId);
            reporterThread.start();

            try {
                Date startDate = new Date();

                int bytesRead = 0, bytesWritten = 0;
                byte[] buffer = new byte[BUFFER_SIZE];
                InputStream inputStream = conn.getInputStream();
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    if (!isActive()) {
                        return DownloadStatus.ABORTED;
                    }

                    outputStream.write(buffer, 0, bytesRead);
                    bytesWritten += bytesRead;

                    Date now = new Date();
                    long elapsedMs = now.getTime() - startDate.getTime();
                    entry.incrementCachedBytes(bytesRead);
                    reporter.update(bytesWritten, elapsedMs);

                    if (maxBytesPerSecond > 0) {
                        long expectedMs = (long) (bytesWritten / (float) maxBytesPerSecond * 1000);
                        if (elapsedMs < expectedMs) SystemClock.sleep(expectedMs - elapsedMs);
                    }
                }
                Date endDate = new Date();
                Log.d(
                        TAG,
                        "finished download of song "
                                + entry.songId
                                + " ("
                                + bytesWritten
                                + " bytes to "
                                + file.getAbsolutePath()
                                + " in "
                                + (endDate.getTime() - startDate.getTime())
                                + " ms)");

                // I see this happen when I kill the server midway through the download.
                if (bytesWritten != conn.getContentLength()) {
                    reason =
                            "Expected "
                                    + conn.getContentLength()
                                    + " bytes but got "
                                    + bytesWritten;
                    return DownloadStatus.RETRYABLE_ERROR;
                }
            } catch (IOException e) {
                reason = "IO error while reading body";
                return DownloadStatus.RETRYABLE_ERROR;
            } finally {
                reporter.quit();
                try {
                    reporterThread.join();
                } catch (InterruptedException e) {
                    /* !~$#%$#!$#!~$#! */
                }
            }
            return DownloadStatus.SUCCESS;
        }

        private void handleFailure() {
            synchronized (inProgressSongIds) {
                inProgressSongIds.remove(entry.songId);
            }
            updateWifiLock();
            listener.onCacheDownloadFail(entry, reason);
        }

        private void handleSuccess() {
            synchronized (inProgressSongIds) {
                inProgressSongIds.remove(entry.songId);
            }
            updateWifiLock();
            listener.onCacheDownloadComplete(entry);
        }

        private void updateBackoffTime(boolean madeProgress) {
            if (madeProgress) backoffTimeMs = 0;
            else if (backoffTimeMs == 0) backoffTimeMs = 1000;
            else backoffTimeMs = Math.min(backoffTimeMs * 2, MAX_BACKOFF_SEC * 1000);
        }

        // Is this download currently active, or has it been cancelled?
        private boolean isActive() {
            return isDownloadActive(entry.songId);
        }

        private class ProgressReporter implements Runnable {
            private static final String TAG = "FileCache.ProgressReporter";
            private static final long PROGRESS_REPORT_MS = 500;

            private final FileCacheEntry entry;
            private long downloadedBytes = 0;
            private long elapsedMs = 0;
            private Handler handler;
            private Runnable task;
            private Date lastReportDate;
            private boolean shouldQuit = false;

            ProgressReporter(final FileCacheEntry entry) {
                this.entry = entry;
            }

            @Override
            public void run() {
                Looper.prepare();
                synchronized (this) {
                    if (shouldQuit) return;
                    handler = new Handler();
                }
                Looper.loop();
            }

            public void quit() {
                synchronized (this) {
                    // The thread hasn't started looping yet; tell it to exit before starting.
                    if (handler == null) {
                        shouldQuit = true;
                        return;
                    }
                }
                handler.post(
                        new Runnable() {
                            @Override
                            public void run() {
                                Looper.myLooper().quit();
                            }
                        });
            }

            public void update(final long downloadedBytes, final long elapsedMs) {
                synchronized (this) {
                    this.downloadedBytes = downloadedBytes;
                    this.elapsedMs = elapsedMs;

                    if (handler != null && task == null) {
                        task =
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        synchronized (ProgressReporter.this) {
                                            listener.onCacheDownloadProgress(
                                                    entry, downloadedBytes, elapsedMs);
                                            lastReportDate = new Date();
                                            task = null;
                                        }
                                    }
                                };

                        long delayMs = 0;
                        if (lastReportDate != null) {
                            long timeSinceLastReportMs =
                                    new Date().getTime() - lastReportDate.getTime();
                            delayMs = Math.max(PROGRESS_REPORT_MS - timeSinceLastReportMs, 0);
                        }
                        handler.postDelayed(task, delayMs);
                    }
                }
            }
        }
    }

    // Wait until |mIsReady| becomes true.
    private void waitUntilReady() {
        isReadyLock.lock();
        try {
            while (!isReady) isReadyCond.await();
        } catch (InterruptedException e) {
        } finally {
            isReadyLock.unlock();
        }
    }

    private boolean isDownloadActive(long songId) {
        synchronized (inProgressSongIds) {
            return inProgressSongIds.contains(songId);
        }
    }

    // Try to make room for |neededBytes| in the cache.
    // We delete the least-recently-accessed files first, ignoring ones
    // that are currently being downloaded or are pinned.
    private synchronized boolean makeSpace(long neededBytes) {
        long maxBytes =
                Long.valueOf(
                                prefs.getString(
                                        NupPreferences.CACHE_SIZE,
                                        NupPreferences.CACHE_SIZE_DEFAULT))
                        * 1024
                        * 1024;
        long availableBytes = maxBytes - getTotalCachedBytes();

        if (neededBytes <= availableBytes) return true;

        Log.d(
                TAG,
                "need to make space for "
                        + neededBytes
                        + " bytes ("
                        + availableBytes
                        + " available)");
        List<Long> songIds = db.getSongIdsByAge();
        for (long songId : songIds) {
            if (neededBytes <= availableBytes) break;

            if (isDownloadActive(songId)) continue;

            synchronized (pinnedSongIds) {
                if (pinnedSongIds.contains(songId)) continue;
            }

            FileCacheEntry entry = db.getEntry(songId);
            File file = entry.getLocalFile();
            Log.d(
                    TAG,
                    "deleting song "
                            + songId
                            + " ("
                            + file.getPath()
                            + ", "
                            + file.length()
                            + " bytes)");
            availableBytes += file.length();
            file.delete();
            db.removeEntry(songId);
            listener.onCacheEviction(entry);
        }

        return neededBytes <= availableBytes;
    }

    // Acquire or release the wifi lock, depending on our current state.
    private void updateWifiLock() {
        if (updateWifiLockTask != null) {
            handler.removeCallbacks(updateWifiLockTask);
            updateWifiLockTask = null;
        }

        boolean active = false;
        synchronized (inProgressSongIds) {
            active = !inProgressSongIds.isEmpty();
        }

        if (active) {
            Log.d(TAG, "acquiring wifi lock");
            wifiState = WifiState.ACTIVE;
            wifiLock.acquire();
        } else {
            if (wifiState == WifiState.ACTIVE) {
                Log.d(
                        TAG,
                        "waiting "
                                + RELEASE_WIFI_LOCK_DELAY_SEC
                                + " seconds before releasing wifi lock");
                wifiState = WifiState.WAITING;
                updateWifiLockTask =
                        new Runnable() {
                            @Override
                            public void run() {
                                updateWifiLock();
                            }
                        };
                handler.postDelayed(updateWifiLockTask, RELEASE_WIFI_LOCK_DELAY_SEC * 1000);
            } else {
                Log.d(TAG, "releasing wifi lock");
                wifiState = WifiState.INACTIVE;
                wifiLock.release();
            }
        }
    }
}
