/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CoverLoader(
    private val coverDir: File,
    private val downloader: Downloader,
    private val bitmapDecoder: BitmapDecoder,
    private val networkHelper: NetworkHelper,
) {
    // Guards [filesBeingLoaded].
    private val lock = ReentrantLock()
    // Signaled when a change has been made to [filesBeingLoaded].
    private val loadFinishedCond = lock.newCondition()

    // Names of cover files that we're currently fetching.
    private val filesBeingLoaded = HashSet<String>()

    // The last cover that we've loaded. We store it here so that we can reuse the already-loaded
    // bitmap in the common case where we're playing an album and need the same cover over and over.
    private val lastCoverLock = ReentrantLock()
    private var lastCoverPath: File? = null
    private var lastCoverBitmap: Bitmap? = null

    /**
     * Load the cover at [url].
     *
     * Tries to find the cover locally first; then goes to the server.
     *
     * @return cover image or null if unavailable
     */
    // TODO: Add 'suspend' after callers have been updated to not use AsyncTask.
    fun loadCover(url: URL): Bitmap? {
        // Ensure that the cover dir exists.
        coverDir.mkdirs()
        var file = lookForLocalCover(url)
        if (file != null) {
            Log.d(TAG, "Found local file ${file.name}")
        } else {
            file = downloadCover(url)
            if (file != null) Log.d(TAG, "Fetched remote file ${file.name}")
        }
        if (file == null || !file.exists()) return null

        return lastCoverLock.withLock {
            if (lastCoverPath != null && lastCoverPath == file) return lastCoverBitmap
            lastCoverPath = file
            lastCoverBitmap = bitmapDecoder.decodeFile(file)
            return lastCoverBitmap
        }
    }

    private fun lookForLocalCover(url: URL): File? {
        val filename = getFilenameForUrl(url)
        startLoad(filename)
        try {
            val file = File(coverDir, filename)
            if (file.exists()) return file
        } finally {
            finishLoad(filename)
        }
        return null
    }

    private fun downloadCover(url: URL): File? {
        if (!networkHelper.isNetworkAvailable) return null

        val localFilename = getFilenameForUrl(url)
        startLoad(localFilename)

        var success = false
        val file = File(coverDir, localFilename)
        var outputStream: FileOutputStream? = null
        var conn: HttpURLConnection? = null
        try {
            // Check if another thread downloaded it while we were waiting.
            if (file.exists()) {
                success = true
                return file
            }
            file.createNewFile()
            outputStream = FileOutputStream(file)
            conn = downloader.download(url, "GET", Downloader.AuthType.STORAGE, null)
            if (conn.responseCode != 200) throw IOException("Status code " + conn.responseCode)

            // TODO: Simplify this.
            val buffer = ByteArray(BUFFER_SIZE)
            val inputStream = conn.inputStream
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            success = true
        } catch (e: IOException) {
            Log.e(TAG, "IO error while fetching $url: $e")
        } finally {
            outputStream?.close()
            conn?.disconnect()
            if (!success && file.exists()) file.delete()
            finishLoad(localFilename)
        }

        return if (success) file else null
    }

    private fun getFilenameForUrl(url: URL): String {
        val parts = url.path.split("/").toTypedArray()
        return parts[parts.size - 1]
    }

    // Call before checking for the existence of a local cover file and before
    // starting to download a remote file.  Waits until |filename| isn't in
    // |mFilesBeingLoaded| and then adds it.  Must be matched by a call to
    // finishLoad().
    private fun startLoad(filename: String) {
        lock.withLock {
            while (filesBeingLoaded.contains(filename)) loadFinishedCond.await()
            filesBeingLoaded.add(filename)
        }
    }

    // Call after checking for the existence of a local file or after completing
    // a download (either successfully or unsuccessfully -- if unsuccessful, be
    // sure to remove the file first so that other threads don't try to use it).
    private fun finishLoad(filename: String) {
        lock.withLock {
            if (!filesBeingLoaded.contains(filename)) {
                throw RuntimeException("Got report of finished load of unknown file $filename")
            }
            filesBeingLoaded.remove(filename)
            loadFinishedCond.signal()
        }
    }

    companion object {
        private const val TAG = "CoverLoader"
        private const val BUFFER_SIZE = 8 * 1024
    }
}
