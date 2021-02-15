// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.graphics.Bitmap
import java.io.Serializable
import java.net.URL

class Song(
    val id: Long,
    val artist: String,
    val title: String,
    val album: String,
    val albumId: String,
    url: String,
    coverUrl: String,
    val lengthSec: Int,
    val track: Int,
    val disc: Int,
    val trackGain: Double,
    val albumGain: Double,
    val peakAmp: Double,
    val rating: Double
) : Serializable {
    val url: URL? = if (url.isEmpty()) null else URL(url)
    val coverUrl: URL? = if (coverUrl.isEmpty()) null else URL(coverUrl)

    var coverBitmap: Bitmap? = null

    // Number of bytes available to us (i.e. what we have on disk).
    var availableBytes: Long = 0

    // Total size of the song, as supplied by the server.
    // 0 if the song doesn't have a cache entry.
    var totalBytes: Long = 0

    // Update our available and total bytes from a cache entry.
    fun updateBytes(entry: FileCacheEntry) {
        availableBytes = entry.cachedBytes
        totalBytes = entry.totalBytes
    }
}
