/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.preference.PreferenceManager
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Holds locally-cached songs. */
class FileCache constructor(
    private val context: Context,
    private val listener: Listener,
    private val downloader: Downloader,
    private val networkHelper: NetworkHelper
) : Runnable {
    interface Listener {
        fun onCacheDownloadError(entry: FileCacheEntry, reason: String)
        fun onCacheDownloadFail(entry: FileCacheEntry, reason: String)
        fun onCacheDownloadProgress(entry: FileCacheEntry, downloadedBytes: Long, elapsedMs: Long)
        fun onCacheDownloadComplete(entry: FileCacheEntry)
        fun onCacheEviction(entry: FileCacheEntry)
    }

    // Status returned by [DownloadTask]'s startDownload() and writeFile() methods.
    private enum class DownloadStatus { SUCCESS, ABORTED, RETRYABLE_ERROR, FATAL_ERROR }

    // Current state of our use of the wifi connection.
    private enum class WifiState {
        ACTIVE, // We have an active download.
        WAITING, // No active downloads; waiting for another one to start before releasing the lock.
        INACTIVE, // No active downloads and the lock is released.
    }

    private val prefs: SharedPreferences

    // Are we ready to service requests?  This is blocked on [db] being initialized.
    private var ready = false
    private val readyLock: Lock = ReentrantLock()
    private val readyCond = readyLock.newCondition()

    // Directory where we write music files.
    private lateinit var musicDir: File

    // Used to run tasks on our own thread.
    private lateinit var handler: Handler

    private val inProgressSongIds = HashSet<Long>() // songs currently being downloaded
    private val pinnedSongIds = HashSet<Long>() // songs that shouldn't be purged

    private lateinit var db: FileCacheDatabase

    private var wifiState = WifiState.INACTIVE
    private val wifiLock: WifiLock
    private var updateWifiLockTask: Runnable? = null

    override fun run() {
        val state = Environment.getExternalStorageState()
        if (state != Environment.MEDIA_MOUNTED) {
            Log.e(TAG, "Media has state $state; we need ${Environment.MEDIA_MOUNTED}")
        }

        musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!
        db = FileCacheDatabase(context, musicDir.path)

        Looper.prepare()
        handler = Handler()
        readyLock.lock()
        ready = true
        readyCond.signal()
        readyLock.unlock()
        Looper.loop()
    }

    fun quit() {
        synchronized(inProgressSongIds) { inProgressSongIds.clear() }
        waitUntilReady()
        handler.post(Runnable { Looper.myLooper()!!.quit() })
        db.quit()
    }

    /** Update the recorded last time that [songId] was accessed. */
    fun updateLastAccessTime(songId: Long) {
        waitUntilReady()
        db.updateLastAccessTime(songId)
    }

    /** Get the entry corresponding to [songId] or null if not cached. */
    fun getEntry(songId: Long): FileCacheEntry? {
        waitUntilReady()
        return db.getEntry(songId)
    }

    /** Get entries corresponding to all fully-cached songs. */
    val allFullyCachedEntries: List<FileCacheEntry>
        get() {
            waitUntilReady()
            return db.allFullyCachedEntries
        }

    /** Abort a previously-started download of [songId]. */
    fun abortDownload(songId: Long) {
        synchronized(inProgressSongIds) {
            if (!inProgressSongIds.contains(songId)) {
                Log.e(TAG, "Tried to abort nonexistent download of song $songId")
            }
            inProgressSongIds.remove(songId)
        }
        updateWifiLock()
        Log.d(TAG, "Canceled download of song $songId")
    }

    /** Get the total size of all cached data. */
    val totalCachedBytes: Long
        get() {
            waitUntilReady()
            return db.totalCachedBytes
        }

    /** Clear all cached data. */
    fun clear() {
        waitUntilReady()
        handler.post {
            synchronized(inProgressSongIds) { inProgressSongIds.clear() }
            updateWifiLock()
            clearPinnedSongIds()
            for (songId: Long in db.songIdsByAge) {
                val entry = db.getEntry(songId) ?: continue
                db.removeEntry(songId)
                entry.localFile.delete()
                listener.onCacheEviction(entry)
            }

            // Shouldn't be anything there, but whatever.
            val files = musicDir.listFiles()
            if (files != null) for (file: File in files) file.delete()
        }
    }

    /**
     * Download [song] to the cache.
     *
     * @return cache entry, or null if [song] is already being downloaded
     */
    fun downloadSong(song: Song): FileCacheEntry? {
        waitUntilReady()
        synchronized(inProgressSongIds) {
            if (inProgressSongIds.contains(song.id)) return null
            inProgressSongIds.add(song.id)
        }

        if (song.url == null) {
            Log.e(TAG, "Song ${song.id} has missing URL")
            return null
        }

        var entry = db.getEntry(song.id)
        if (entry == null) entry = db.addEntry(song.id)
        else db.updateLastAccessTime(song.id)

        Log.d(TAG, "Posting download of ${song.id} from ${song.url} to ${entry.localFile.path}")
        handler.post(DownloadTask(entry, song.url))
        return entry
    }

    /** Prevents [songId] from being evicted to make space for other songs. */
    fun pinSongId(songId: Long) {
        synchronized(pinnedSongIds) { pinnedSongIds.add(songId) }
    }

    /** Allows all songs to be evicted to make space. */
    fun clearPinnedSongIds() {
        synchronized(pinnedSongIds) { pinnedSongIds.clear() }
    }

    private inner class DownloadTask(
        private val entry: FileCacheEntry,
        private val url: URL
    ) : Runnable {
        private val tag = "FileCache.DownloadTask"

        // Maximum number of seconds we'll wait before retrying after failure.  Never give up!
        private val maxBackoffSec = 60

        // Size of buffer used to write data to disk, in bytes.
        private val bufferSize = 8 * 1024

        // How long we should wait before retrying after an error, in milliseconds.
        // We start at 0 but back off exponentially after errors where we haven't made any progress.
        private var backoffTimeMs = 0

        // Reason for the failure.
        private lateinit var reason: String

        private var conn: HttpURLConnection? = null
        private var outputStream: FileOutputStream? = null

        override fun run() {
            if (!isActive) return

            if (!networkHelper.isNetworkAvailable) {
                reason = context.getString(R.string.network_is_unavailable)
                handleFailure()
                return
            }

            updateWifiLock()

            while (true) {
                try {
                    if (backoffTimeMs > 0) {
                        Log.d(tag, "Sleeping $backoffTimeMs ms before retrying ${entry.songId}")
                        SystemClock.sleep(backoffTimeMs.toLong())
                    }

                    // If the file is fully downloaded already, report success.
                    if (entry.isFullyCached) {
                        handleSuccess()
                        return
                    }
                    when (startDownload()) {
                        DownloadStatus.SUCCESS -> {}
                        DownloadStatus.ABORTED -> return
                        DownloadStatus.RETRYABLE_ERROR -> {
                            listener.onCacheDownloadError(entry, reason)
                            updateBackoffTime(false)
                            continue
                        }
                        DownloadStatus.FATAL_ERROR -> {
                            handleFailure()
                            return
                        }
                    }
                    if (!isActive) return
                    when (writeFile()) {
                        DownloadStatus.SUCCESS -> {}
                        DownloadStatus.ABORTED -> return
                        DownloadStatus.RETRYABLE_ERROR -> {
                            listener.onCacheDownloadError(entry, reason)
                            updateBackoffTime(true)
                            continue
                        }
                        DownloadStatus.FATAL_ERROR -> {
                            handleFailure()
                            return
                        }
                    }
                    handleSuccess()
                    return
                } finally {
                    conn?.disconnect()
                    outputStream?.close()
                }
            }
        }

        private fun startDownload(): DownloadStatus {
            try {
                val headers: MutableMap<String, String> = HashMap()
                if (entry.cachedBytes > 0 && entry.cachedBytes < entry.totalBytes) {
                    Log.d(Companion.TAG, "Resuming download at byte ${entry.cachedBytes}")
                    headers["Range"] = String.format("bytes=%d-", entry.cachedBytes)
                }
                conn = downloader.download(url, "GET", Downloader.AuthType.STORAGE, headers)
                val status = conn!!.getResponseCode()
                Log.d(Companion.TAG, "Got $status from server")
                if (status != 200 && status != 206) {
                    reason = "Got status code $status"
                    return DownloadStatus.FATAL_ERROR
                }

                // Update the cache entry with the total file size.
                if (status == 200) {
                    val len = conn!!.getContentLengthLong()
                    if (len <= 1) {
                        reason = "Got invalid content length $len"
                        return DownloadStatus.FATAL_ERROR
                    }
                    db.setTotalBytes(entry.songId, len)
                }
            } catch (e: IOException) {
                reason = "IO error while starting download"
                return DownloadStatus.RETRYABLE_ERROR
            }
            return DownloadStatus.SUCCESS
        }

        private fun writeFile(): DownloadStatus {
            // Make space for whatever we're planning to download.
            val len = conn!!.contentLength
            if (!makeSpace(len.toLong())) {
                reason = "Unable to make space for $len-byte download"
                return DownloadStatus.FATAL_ERROR
            }

            val file = entry.localFile
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                try {
                    file.createNewFile()
                } catch (e: IOException) {
                    reason = "Unable to create local file ${file.path}"
                    return DownloadStatus.FATAL_ERROR
                }
            }

            val statusCode: Int
            try {
                statusCode = conn!!.responseCode
            } catch (e: IOException) {
                reason = "Unable to get status code"
                return DownloadStatus.RETRYABLE_ERROR
            }

            try {
                // TODO: Also check the Content-Range header.
                val append = (statusCode == 206)
                outputStream = FileOutputStream(file, append)
                if (!append) entry.cachedBytes = 0
            } catch (e: FileNotFoundException) {
                reason = "Unable to create output stream to local file"
                return DownloadStatus.FATAL_ERROR
            }

            val maxBytesPerSecond =
                java.lang.Long.valueOf(
                    prefs.getString(
                        NupPreferences.DOWNLOAD_RATE,
                        NupPreferences.DOWNLOAD_RATE_DEFAULT
                    )!!
                ) * 1024

            val reporter = ProgressReporter(entry)
            val reporterThread = Thread(reporter, "FileCache.ProgressReporter.${entry.songId}")
            reporterThread.start()

            try {
                val startDate = Date()
                var bytesRead: Int
                var bytesWritten = 0
                val buffer = ByteArray(bufferSize)
                while ((conn!!.inputStream.read(buffer).also { bytesRead = it }) != -1) {
                    if (!isActive) return DownloadStatus.ABORTED
                    outputStream!!.write(buffer, 0, bytesRead)
                    bytesWritten += bytesRead

                    val now = Date()
                    val elapsedMs = now.time - startDate.time

                    entry.incrementCachedBytes(bytesRead.toLong())
                    reporter.update(bytesWritten.toLong(), elapsedMs)

                    if (maxBytesPerSecond > 0) {
                        val expectedMs =
                            (bytesWritten / maxBytesPerSecond.toFloat() * 1000).toLong()
                        if (elapsedMs < expectedMs) SystemClock.sleep(expectedMs - elapsedMs)
                    }
                }
                val endDate = Date()

                Log.d(
                    Companion.TAG,
                    "Finished download of song ${entry.songId} ($bytesWritten bytes to " +
                        "${file.absolutePath} in ${endDate.time - startDate.time} ms)"
                )

                // I see this happen when I kill the server midway through the download.
                if (bytesWritten != conn!!.contentLength) {
                    reason = "Expected ${conn!!.contentLength} bytes but got $bytesWritten"
                    return DownloadStatus.RETRYABLE_ERROR
                }
            } catch (e: IOException) {
                reason = "IO error while reading body"
                return DownloadStatus.RETRYABLE_ERROR
            } finally {
                reporter.quit()
                reporterThread.join()
            }
            return DownloadStatus.SUCCESS
        }

        private fun handleFailure() {
            synchronized(inProgressSongIds) { inProgressSongIds.remove(entry.songId) }
            updateWifiLock()
            listener.onCacheDownloadFail(entry, reason)
        }

        private fun handleSuccess() {
            synchronized(inProgressSongIds) { inProgressSongIds.remove(entry.songId) }
            updateWifiLock()
            listener.onCacheDownloadComplete(entry)
        }

        private fun updateBackoffTime(madeProgress: Boolean) {
            backoffTimeMs = when {
                madeProgress -> 0
                backoffTimeMs == 0 -> 1000
                else -> Math.min(backoffTimeMs * 2, maxBackoffSec * 1000)
            }
        }

        // Is this download currently active, or has it been cancelled?
        private val isActive: Boolean
            get() = isDownloadActive(entry.songId)

        private inner class ProgressReporter internal constructor(
            private val entry: FileCacheEntry
        ) : Runnable {
            private var downloadedBytes: Long = 0
            private var elapsedMs: Long = 0

            private var handler: Handler? = null
            private var task: Runnable? = null

            private var lastReportDate: Date? = null
            private var shouldQuit = false
            private var progressReportMs: Long = 500

            override fun run() {
                Looper.prepare()
                synchronized(this) {
                    if (shouldQuit) return
                    handler = Handler()
                }
                Looper.loop()
            }

            fun quit() {
                synchronized(this) {
                    // The thread hasn't started looping yet; tell it to exit before starting.
                    if (handler != null) {
                        shouldQuit = true
                        return
                    }
                }
                handler!!.post(
                    object : Runnable {
                        override fun run() { Looper.myLooper()!!.quit() }
                    })
            }

            fun update(downloadedBytes: Long, elapsedMs: Long) {
                synchronized(this) {
                    this.downloadedBytes = downloadedBytes
                    this.elapsedMs = elapsedMs
                    if (handler != null && task == null) {
                        task = Runnable {
                            synchronized(this@ProgressReporter) {
                                listener.onCacheDownloadProgress(entry, downloadedBytes, elapsedMs)
                                lastReportDate = Date()
                                task = null
                            }
                        }
                        var delayMs: Long = 0
                        if (lastReportDate != null) {
                            val timeSinceLastReportMs: Long =
                                Date().getTime() - lastReportDate!!.getTime()
                            delayMs = Math.max(progressReportMs - timeSinceLastReportMs, 0)
                        }
                        handler!!.postDelayed(task!!, delayMs)
                    }
                }
            }
        }
    }

    /** Wait until [ready] becomes true. */
    private fun waitUntilReady() {
        readyLock.withLock { while (!ready) readyCond.await() }
    }

    /** Check if [songId] is currently being downloaded. */
    private fun isDownloadActive(songId: Long): Boolean {
        synchronized(inProgressSongIds) { return inProgressSongIds.contains(songId) }
    }

    /**
     * Try to make room for [neededBytes] in the cache.
     *
     * We delete the least-recently-accessed files first, ignoring ones
     * that are currently being downloaded or are pinned.
     */
    @Synchronized
    private fun makeSpace(neededBytes: Long): Boolean {
        val maxBytes = java.lang.Long.valueOf(
            prefs.getString(
                NupPreferences.CACHE_SIZE,
                NupPreferences.CACHE_SIZE_DEFAULT
            )!!
        ) * 1024 * 1024
        var availableBytes = maxBytes - totalCachedBytes
        if (neededBytes <= availableBytes) return true

        Log.d(TAG, "Making space for $neededBytes bytes ($availableBytes available)")

        val songIds = db.songIdsByAge
        for (songId: Long in songIds) {
            if (neededBytes <= availableBytes) break
            if (isDownloadActive(songId)) continue

            var pinned = false
            synchronized(pinnedSongIds) { if (pinnedSongIds.contains(songId)) pinned = true }
            if (pinned) continue

            val entry = db.getEntry(songId)
            if (entry == null) {
                Log.e(TAG, "Missing cache entry for song $songId")
                continue
            }

            val file = entry.localFile
            Log.d(TAG, "Deleting song $songId (${file.path}, ${file.length()} bytes)")
            availableBytes += file.length()
            file.delete()
            db.removeEntry(songId)
            listener.onCacheEviction(entry)
        }

        return neededBytes <= availableBytes
    }

    /** Acquire or release the wifi lock, depending on our current state. */
    private fun updateWifiLock() {
        if (updateWifiLockTask != null) {
            handler.removeCallbacks(updateWifiLockTask!!)
            updateWifiLockTask = null
        }

        val active = synchronized(inProgressSongIds) { !inProgressSongIds.isEmpty() }
        if (active) {
            Log.d(TAG, "Acquiring wifi lock")
            wifiState = WifiState.ACTIVE
            wifiLock.acquire()
        } else {
            if (wifiState == WifiState.ACTIVE) {
                Log.d(TAG, "Waiting $RELEASE_WIFI_LOCK_DELAY_SEC sec before releasing wifi lock")
                wifiState = WifiState.WAITING
                updateWifiLockTask = Runnable { updateWifiLock() }
                handler.postDelayed(updateWifiLockTask!!, RELEASE_WIFI_LOCK_DELAY_SEC * 1000)
            } else {
                Log.d(TAG, "Releasing wifi lock")
                wifiState = WifiState.INACTIVE
                wifiLock.release()
            }
        }
    }

    companion object {
        private const val TAG = "FileCache"

        // How long should we hold the wifi lock after noticing that there are no current downloads?
        private const val RELEASE_WIFI_LOCK_DELAY_SEC: Long = 600
    }

    init {
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        wifiLock = (context.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL, context.getString(R.string.app_name))
        wifiLock.setReferenceCounted(false)
    }
}

/** Information about a song that has been cached by [FileCache]. */
class FileCacheEntry(
    private val musicDir: String,
    val songId: Long,
    var totalBytes: Long,
    var lastAccessTime: Int
) {
    var cachedBytes: Long = 0
    val localFile: File
        get() = File(musicDir, "$songId.mp3")

    fun incrementCachedBytes(bytes: Long) {
        cachedBytes += bytes
    }

    val isFullyCached: Boolean
        get() = totalBytes > 0 && cachedBytes == totalBytes
}
