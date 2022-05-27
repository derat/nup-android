/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Loads and caches album art. */
open class CoverLoader(
    private val context: Context,
    private val scope: CoroutineScope,
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

    private var _totalBytes = 0L // total size of files in [coverDir]
    val totalBytes get() = totalBytesLock.withLock { _totalBytes }
    private val totalBytesLock = ReentrantLock() // guards [_totalBytes]

    // The last cover that we've loaded. We store it here so that we can reuse the already-decoded
    // bitmap in the common case where we're playing an album and need the same cover over and over.
    private var lastFile: File? = null
    private var lastBitmap: Bitmap? = null
    private val lastLock = ReentrantLock()

    /**
     * Synchronously load and decodes the cover at [filename] (corresponding to
     * [Song.coverFilename]).
     *
     * Tries to find the cover locally first; then goes to the server.
     *
     * @return cover image or null if unavailable
     */
    suspend fun getBitmap(filename: String): Bitmap? {
        val file = getFile(filename) ?: return null
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

    /**
     * Synchronously load the cover at [filename] (corresponding to [Song.coverFilename]).
     *
     * Tries to find the cover locally first; then goes to the server.
     *
     * @return cover image or null if unavailable
     */
    suspend fun getFile(filename: String): File? {
        waitUntilReady() // wait for [coverDir]

        lookForLocalCover(filename)?.let {
            Log.d(TAG, "Using local file ${it.name}")
            return it
        }
        downloadCover(filename)?.let {
            Log.d(TAG, "Fetched remote file ${it.name}")
            return it
        }
        return null
    }

    private suspend fun lookForLocalCover(filename: String): File? {
        startLoad(filename)
        try {
            val file = File(coverDir, filename)
            if (file.exists()) return file
        } finally {
            finishLoad(filename)
        }
        return null
    }

    private suspend fun downloadCover(filename: String): File? {
        if (!networkHelper.isNetworkAvailable) return null

        startLoad(filename)

        var success = false
        val file = File(coverDir, filename)
        if (!file.toPath().startsWith(coverDir.toPath())) {
            Log.e(TAG, "Cover $filename escapes cover dir")
            return null
        }
        val enc = URLEncoder.encode(filename, "UTF-8")
        val url = downloader.getServerUrl("/cover?filename=$enc&size=$COVER_SIZE&webp=1")
        var conn: HttpURLConnection? = null
        try {
            // Check if another thread downloaded it while we were waiting.
            if (file.exists()) {
                success = true
                return file
            }

            conn = downloader.download(url, "GET", Downloader.AuthType.SERVER, null)
            if (conn.responseCode != 200) throw IOException("Status code " + conn.responseCode)
            file.parentFile?.mkdirs()
            file.createNewFile()
            file.outputStream().use { conn.inputStream.copyTo(it) }
            success = true
        } catch (e: IOException) {
            Log.e(TAG, "IO error while fetching $url: $e")
        } finally {
            conn?.disconnect()
            if (success) totalBytesLock.withLock { _totalBytes += file.length() }
            else if (file.exists()) file.delete()
            finishLoad(filename)
        }

        return if (success) file else null
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

    /** Delete all downloaded covers. */
    suspend fun clear() {
        var bytes = 0L
        try {
            loadLock.withLock {
                coverDir.walkTopDown().forEach loop@{ file ->
                    val fn = coverDir.toPath().relativize(file.toPath()).toString()
                    if (!file.isFile || filesBeingLoaded.contains(fn)) return@loop
                    val len = file.length()
                    file.delete()
                    bytes += len
                }
            }
        } finally {
            totalBytesLock.withLock { _totalBytes = Math.max(_totalBytes - bytes, 0L) }
        }
    }

    companion object {
        private const val TAG = "CoverLoader"
        private const val DIR_NAME = "covers"
        private const val COVER_SIZE = 512 // size hardcoded in server's /cover handler
    }

    init {
        CoverProvider.loader = this

        // [externalCacheDir] hits the disk, so do it in the background.
        scope.launch(Dispatchers.IO) {
            coverDir = File(context.externalCacheDir, DIR_NAME)
            coverDir.mkdirs()
            totalBytesLock.withLock {
                _totalBytes = coverDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            }
            readyLock.withLock {
                ready = true
                readyCond.signal()
            }
        }
    }
}

/** [FileProvider] implementation for accessing album art. */
class CoverProvider() : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val filename = uri.path ?: return null
        val file = runBlocking { loader?.getFile(filename) } ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    // TODO: Determine this based on the filename. This seems like it'll be super-painful
    // since we don't actually know whether the server will give us WebP or JPEG. Maybe it
    // doesn't matter, or this doesn't even get called by the MediaBrowserService stuff?
    // JPEG images still seem to get displayed in Android Auto when this returns image/webp.
    override fun getType(uri: Uri): String? = "image/webp"

    // All of the normal content provider methods are unneeded.
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        return null
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    companion object {
        private const val TAG = "CoverProvider"
        const val AUTHORITY = "org.erat.nup.covers"

        var loader: CoverLoader? = null
    }
}
