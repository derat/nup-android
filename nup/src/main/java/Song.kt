/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

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
    val rating: Double,
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

/** Ways to order [Song]s. */
enum class SongOrder { ARTIST, TITLE, ALBUM, UNSORTED }

/**
 * Get a key for ordering [str] according to [order].
 *
 * [str] is converted to lowercase and common leading articles are removed.
 * If this method is changed, SongDatabase.updateStatsTables() must be called on the next run,
 * as sorting keys are cached in the database.
 */
fun getSongOrderKey(str: String, @Suppress("UNUSED_PARAMETER") order: SongOrder): String {
    // TODO: Consider dropping the order parameter since it isn't actually used now.
    // Note that the enum is still needed for sortStatsRows(), though.
    var key = str.toLowerCase()

    // Preserve bracketed strings used by MusicBrainz and/or Picard.
    if (keyTags.contains(key)) return "!$key"

    // Preserve some specific weird album names.
    if (key.startsWith("( )")) return key

    // Strip off leading punctuation, common articles, and other junk.
    var start = 0
    loop@ while (start < key.length) {
        for (pre in keyPrefixes) {
            if (key.startsWith(pre, start)) {
                start += pre.length
                continue@loop
            }
        }
        break
    }
    if (start > 0) key = key.substring(start)
    return key
}

private val keyTags =
    setOf("[dialogue]", "[no artist]", "[unknown]", "[non-album tracks]", "[unset]")
private val keyPrefixes =
    arrayOf(" ", "\"", "'", "’", "(", "[", "<", "...", "a ", "an ", "the ")
