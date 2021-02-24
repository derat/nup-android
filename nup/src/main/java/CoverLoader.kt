/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/** CoverLoader loads and caches album art. */
open class CoverLoader(
    context: Context,
    private val downloader: Downloader,
    private val networkHelper: NetworkHelper,
) {
    private lateinit var coverDir: File // dir where files are cached
    private var ready = false // true after [coverDir] has been initialized
    private val readyLock: Lock = ReentrantLock() // guards [ready]
    private val readyCond = readyLock.newCondition() // signaled when [ready] becomes true

    private val filesBeingLoaded = mutableSetOf<String>() // names of files being fetched
    private val loadLock = ReentrantLock() // guards [filesBeingLoaded]
    private val loadFinishedCond = loadLock.newCondition() // signaled for [filesBeingLoaded]

    // The last cover that we've loaded. We store it here so that we can reuse the already-loaded
    // bitmap in the common case where we're playing an album and need the same cover over and over.
    private var lastFile: File? = null
    private var lastBitmap: Bitmap? = null
    private val lastLock = ReentrantLock()

    /**
     * Synchronously load the cover at [path] (corresponding to [Song.coverFilename]).
     *
     * Tries to find the cover locally first; then goes to the server.
     *
     * @return cover image or null if unavailable
     */
    suspend fun loadCover(path: String): Bitmap? {
        waitUntilReady() // wait for [coverDir]

        var file = lookForLocalCover(path)
        if (file != null) {
            Log.d(TAG, "Using local file ${file.name}")
        } else {
            file = downloadCover(path) ?: return null
            Log.d(TAG, "Fetched remote file ${file.name}")
        }

        if (!file.exists()) {
            Log.e(TAG, "${file.name} disappeared")
            return null
        }

        lastLock.withLock {
            if (lastFile != null && lastFile == file) return lastBitmap
            lastFile = file
            lastBitmap = decodeFile(file.path)
            return lastBitmap
        }
    }

    private suspend fun lookForLocalCover(path: String): File? {
        val filename = getFilenameForPath(path)
        startLoad(filename)
        try {
            val file = File(coverDir, filename)
            if (file.exists()) return file
        } finally {
            finishLoad(filename)
        }
        return null
    }

    private suspend fun downloadCover(path: String): File? {
        if (!networkHelper.isNetworkAvailable) return null

        val filename = getFilenameForPath(path)
        startLoad(filename)

        var success = false
        val file = File(coverDir, filename)
        val enc = URLEncoder.encode(path, "UTF-8")
        val url = downloader.getServerUrl("/cover?filename=$enc&size=$COVER_SIZE")
        var conn: HttpURLConnection? = null
        try {
            // Check if another thread downloaded it while we were waiting.
            if (file.exists()) {
                success = true
                return file
            }

            conn = downloader.download(url, "GET", Downloader.AuthType.SERVER, null)
            if (conn.responseCode != 200) throw IOException("Status code " + conn.responseCode)
            file.createNewFile()
            file.outputStream().use { conn.inputStream.copyTo(it) }
            success = true
        } catch (e: IOException) {
            Log.e(TAG, "IO error while fetching $url: $e")
        } finally {
            conn?.disconnect()
            if (!success && file.exists()) file.delete()
            finishLoad(filename)
        }

        return if (success) file else null
    }

    private fun getFilenameForPath(path: String): String {
        val parts = path.split("/").toTypedArray()
        return parts[parts.size - 1]
    }

    // Call before checking for the existence of a local cover file and before starting to download
    // a remote file. Waits until [filename] isn't in [filesBeingLoaded] and then adds it. Must be
    // matched by a call to [finishLoad].
    private fun startLoad(filename: String) {
        loadLock.withLock {
            while (filesBeingLoaded.contains(filename)) loadFinishedCond.await()
            filesBeingLoaded.add(filename)
        }
    }

    // Call after checking for the existence of a local file or after completing a download (either
    // successfully or unsuccessfully -- if unsuccessful, be sure to remove the file first so that
    // other threads don't try to use it).
    private fun finishLoad(filename: String) {
        loadLock.withLock {
            if (!filesBeingLoaded.contains(filename)) {
                throw RuntimeException("Got report of finished load of unknown file $filename")
            }
            filesBeingLoaded.remove(filename)
            loadFinishedCond.signal()
        }
    }

    /** Wait until [ready] becomes true. */
    private fun waitUntilReady() {
        readyLock.withLock { while (!ready) readyCond.await() }
    }

    /** Decode the bitmap at the given path. */
    open fun decodeFile(path: String): Bitmap? = BitmapFactory.decodeFile(path)

    companion object {
        private const val TAG = "CoverLoader"
        private const val DIR_NAME = "covers"
        private const val COVER_SIZE = 256
    }

    init {
        // [externalCacheDir] hits the disk, so do it in the background.
        GlobalScope.launch(Dispatchers.IO) {
            coverDir = File(context.externalCacheDir, DIR_NAME)
            coverDir.mkdirs()
            readyLock.withLock {
                ready = true
                readyCond.signal()
            }
        }
    }
}
