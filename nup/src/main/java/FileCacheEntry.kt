/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import java.io.File

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
