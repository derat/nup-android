// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.graphics.Bitmap
import java.io.Serializable
import java.net.URL

class Song(
        @JvmField val id: Long,
        @JvmField val artist: String,
        @JvmField val title: String,
        @JvmField val album: String,
        @JvmField val albumId: String,
        url: String,
        coverUrl: String,
        @JvmField val lengthSec: Int,
        @JvmField val track: Int,
        @JvmField val disc: Int,
        @JvmField val trackGain: Double,
        @JvmField val albumGain: Double,
        @JvmField val peakAmp: Double,
        @JvmField val rating: Double) : Serializable {
    @JvmField val url: URL? = if (url.isEmpty()) null else URL(url)
    @JvmField val coverUrl: URL? = if (coverUrl.isEmpty()) null else URL(coverUrl)

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