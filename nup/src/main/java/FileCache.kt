/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Downloads and caches songs. */
class FileCache constructor(
    private val context: Context,
    private val listener: Listener,
    private val listenerExecutor: Executor,
    private val downloader: Downloader,
    private val networkHelper: NetworkHelper,
) {
    interface Listener {
        /** Called when a retryable error occurs while downloading [entry]. */
        fun onCacheDownloadError(entry: FileCacheEntry, reason: String)
        /** Called when the download of [entry] has failed. */
        fun onCacheDownloadFail(entry: FileCacheEntry, reason: String)
        /** Called periodically to describe [entry]'s progress. */
        fun onCacheDownloadProgress(entry: FileCacheEntry, downloadedBytes: Long, elapsedMs: Long)
        /** Called when the download of [entry] is complete. */
        fun onCacheDownloadComplete(entry: FileCacheEntry)
        /** Called when [entry] has been removed from the cache. */
        fun onCacheEviction(entry: FileCacheEntry)
    }

    // Status returned by [DownloadTask.startDownload] and [DownloadTask.writeFile].
    private enum class DownloadStatus { SUCCESS, ABORTED, RETRYABLE_ERROR, FATAL_ERROR }

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val threadChecker = ThreadChecker(executor)

    var maxRate = 0L // maximum download rate in bytes/sec; unlimited if <= 0
    var maxBytes = 0L // max cache size; unlimited if <= 0

    private val inProgressSongIds = HashSet<Long>() // songs currently being downloaded
    private val pinnedSongIds = HashSet<Long>() // songs that shouldn't be purged

    private lateinit var musicDir: File // directory where music files are written
    private lateinit var db: FileCacheDatabase
    private var ready = false // true after [db] has been initialized
    private val readyLock: Lock = ReentrantLock()
    private val readyCond = readyLock.newCondition()

    /** Shut down the cache. */
    fun quit() {
        synchronized(inProgressSongIds) { inProgressSongIds.clear() }
        waitUntilReady()
        executor.shutdown()
        executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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
        waitUntilReady()
        synchronized(inProgressSongIds) {
            if (!inProgressSongIds.contains(songId)) {
                Log.e(TAG, "Tried to abort nonexistent download of song $songId")
            }
            inProgressSongIds.remove(songId)
        }
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
        executor.execute {
            synchronized(inProgressSongIds) { inProgressSongIds.clear() }
            clearPinnedSongIds()
            for (songId: Long in db.songIdsByAge) {
                val entry = db.getEntry(songId) ?: continue
                db.removeEntry(songId)
                entry.file.delete()
                listenerExecutor.execute { listener.onCacheEviction(entry) }
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

        Log.d(TAG, "Posting download of ${song.id} from ${song.url} to ${entry.file.path}")
        executor.execute(DownloadTask(entry, song.url))
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

    /** Downloads a single file to the cache. */
    private inner class DownloadTask(
        private val entry: FileCacheEntry,
        private val url: URL
    ) : Runnable {
        private val TAG = "FileCache.DownloadTask"

        // Maximum number of seconds to wait before retrying after failure. Never give up!
        private val MAX_BACKOFF_SEC = 60L

        // Size of buffer used to write data to disk, in bytes.
        private val BUFFER_SIZE = 8 * 1024

        // Frequency at which download progress is reported to [Listener].
        private val PROGRESS_REPORT_MS = 500

        // How long to wait before retrying after an error.
        // Start at 0 but back off exponentially after errors where we haven't made any progress.
        private var backoffTimeMs = 0L

        private lateinit var reason: String // failure reason
        private var conn: HttpURLConnection? = null
        private var outputStream: FileOutputStream? = null

        /** Synchronously perform the download. */
        override fun run() {
            threadChecker.assertThread()

            if (canceled) return
            if (!networkHelper.isNetworkAvailable) {
                reason = context.getString(R.string.network_is_unavailable)
                handleFailure()
                return
            }

            while (true) {
                try {
                    if (backoffTimeMs > 0) {
                        Log.d(TAG, "Sleeping $backoffTimeMs ms before retrying ${entry.songId}")
                        SystemClock.sleep(backoffTimeMs)
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
                            listenerExecutor.execute {
                                listener.onCacheDownloadError(entry, reason)
                            }
                            updateBackoffTime(false)
                            continue
                        }
                        DownloadStatus.FATAL_ERROR -> {
                            handleFailure()
                            return
                        }
                    }

                    if (canceled) return

                    when (writeFile()) {
                        DownloadStatus.SUCCESS -> {}
                        DownloadStatus.ABORTED -> return
                        DownloadStatus.RETRYABLE_ERROR -> {
                            listenerExecutor.execute {
                                listener.onCacheDownloadError(entry, reason)
                            }
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

        /** Start the download and initialize [conn]. */
        private fun startDownload(): DownloadStatus {
            threadChecker.assertThread()

            try {
                val headers: MutableMap<String, String> = HashMap()
                if (entry.cachedBytes > 0 && entry.cachedBytes < entry.totalBytes) {
                    Log.d(TAG, "Resuming download at byte ${entry.cachedBytes}")
                    headers["Range"] = String.format("bytes=%d-", entry.cachedBytes)
                }
                conn = downloader.download(url, "GET", Downloader.AuthType.STORAGE, headers)
                val status = conn!!.getResponseCode()
                Log.d(TAG, "Got $status from server")
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

        /** Copy [conn]'s data to [entry.file]. */
        private fun writeFile(): DownloadStatus {
            threadChecker.assertThread()

            // Make space for whatever we're planning to download.
            val len = conn!!.contentLength
            if (!makeSpace(len.toLong())) {
                reason = "Unable to make space for $len-byte download"
                return DownloadStatus.FATAL_ERROR
            }

            if (!entry.file.exists()) {
                entry.file.parentFile?.mkdirs()
                try {
                    entry.file.createNewFile()
                } catch (e: IOException) {
                    reason = "Unable to create local file ${entry.file.path}"
                    return DownloadStatus.FATAL_ERROR
                }
            }

            val statusCode = conn!!.responseCode
            try {
                // TODO: Also check the Content-Range header.
                val append = (statusCode == 206)
                outputStream = FileOutputStream(entry.file, append)
                if (!append) entry.cachedBytes = 0
            } catch (e: FileNotFoundException) {
                reason = "Unable to create output stream to local file"
                return DownloadStatus.FATAL_ERROR
            }

            val reporter = ProgressReporter(entry)

            try {
                val startDate = Date()
                var bytesRead: Int
                var bytesWritten = 0
                val buffer = ByteArray(BUFFER_SIZE)
                while ((conn!!.inputStream.read(buffer).also { bytesRead = it }) != -1) {
                    if (canceled) return DownloadStatus.ABORTED
                    outputStream!!.write(buffer, 0, bytesRead)
                    bytesWritten += bytesRead

                    val now = Date()
                    val elapsedMs = now.time - startDate.time

                    entry.incrementCachedBytes(bytesRead.toLong())
                    reporter.update(bytesWritten.toLong(), elapsedMs)

                    if (maxRate > 0) {
                        val expectedMs = (bytesWritten / maxRate.toFloat() * 1000).toLong()
                        if (elapsedMs < expectedMs) SystemClock.sleep(expectedMs - elapsedMs)
                    }
                }
                val endDate = Date()

                Log.d(
                    TAG,
                    "Finished download of song ${entry.songId} ($bytesWritten bytes to " +
                        "${entry.file} in ${endDate.time - startDate.time} ms)"
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
            }
            return DownloadStatus.SUCCESS
        }

        private fun handleFailure() {
            threadChecker.assertThread()
            synchronized(inProgressSongIds) { inProgressSongIds.remove(entry.songId) }
            listenerExecutor.execute { listener.onCacheDownloadFail(entry, reason) }
        }

        private fun handleSuccess() {
            threadChecker.assertThread()
            synchronized(inProgressSongIds) { inProgressSongIds.remove(entry.songId) }
            listenerExecutor.execute { listener.onCacheDownloadComplete(entry) }
        }

        private fun updateBackoffTime(madeProgress: Boolean) {
            threadChecker.assertThread()
            backoffTimeMs = when {
                madeProgress -> 0
                backoffTimeMs == 0L -> 1000
                else -> Math.min(backoffTimeMs * 2, MAX_BACKOFF_SEC * 1000)
            }
        }

        private val canceled: Boolean
            get() = !isDownloadActive(entry.songId)

        /** Periodically notifies [listener] about download progress. */
        private inner class ProgressReporter internal constructor(
            private val entry: FileCacheEntry
        ) {
            private val executor = Executors.newSingleThreadScheduledExecutor()
            private var task: ScheduledFuture<*>? = null

            private var downloadedBytes = 0L
            private var elapsedMs = 0L
            private var lastReportDate: Date? = null

            private var PROGRESS_REPORT_MS = 500L

            /** Notify [listener] about progress. */
            private val notifyTask = {
                listenerExecutor.execute {
                    listener.onCacheDownloadProgress(entry, downloadedBytes, elapsedMs)
                }
                lastReportDate = Date()
                task = null
            }

            /** Stop reporting. */
            fun quit() {
                executor.shutdownNow()
                executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }

            /** Update the current state of the download. */
            fun update(downloadedBytes: Long, elapsedMs: Long) {
                synchronized(this) {
                    this.downloadedBytes = downloadedBytes
                    this.elapsedMs = elapsedMs

                    if (task == null) {
                        var delayMs: Long = 0
                        if (lastReportDate != null) {
                            val lastReportMs = Date().getTime() - lastReportDate!!.getTime()
                            delayMs = Math.max(PROGRESS_REPORT_MS - lastReportMs, 0)
                        }
                        task = executor.schedule(notifyTask, delayMs, TimeUnit.MILLISECONDS)
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
     *
     * @return true if the space is now available
     */
    @Synchronized private fun makeSpace(neededBytes: Long): Boolean {
        threadChecker.assertThread()

        if (maxBytes <= 0) return true
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

            Log.d(TAG, "Deleting song $songId (${entry.file.path}, ${entry.file.length()} bytes)")
            availableBytes += entry.file.length()
            entry.file.delete()
            db.removeEntry(songId)
            listenerExecutor.execute { listener.onCacheEviction(entry) }
        }

        return neededBytes <= availableBytes
    }

    companion object {
        private const val TAG = "FileCache"
        private const val SHUTDOWN_TIMEOUT_MS = 1000L
    }

    init {
        // Avoid hitting the disk on the main thread.
        // (Note that [waitUntilReady] will still hold everything up. :-/)
        executor.execute {
            val state = Environment.getExternalStorageState()
            if (state != Environment.MEDIA_MOUNTED) {
                Log.e(TAG, "Media has state $state; we need ${Environment.MEDIA_MOUNTED}")
            }
            musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!
            db = FileCacheDatabase(context, musicDir.path)

            readyLock.withLock {
                ready = true
                readyCond.signal()
            }
        }
    }
}

/** Information about a song that has been cached by [FileCache]. */
class FileCacheEntry(
    musicDir: String,
    val songId: Long,
    var totalBytes: Long,
    var lastAccessTime: Int,
    cachedBytes: Long = -1, // if negative, get actual size from disk
) {
    val file = File(musicDir, "$songId.mp3").absoluteFile
    var cachedBytes =
        if (cachedBytes >= 0) cachedBytes
        else if (file.exists()) file.length() else 0

    val isFullyCached: Boolean
        get() = totalBytes > 0 && cachedBytes == totalBytes

    fun incrementCachedBytes(bytes: Long) {
        cachedBytes += bytes
    }
}
