/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.graphics.Bitmap
import java.io.Serializable
import kotlin.random.Random

data class Song(
    val id: Long,
    val artist: String,
    val title: String,
    val album: String,
    val albumId: String,
    val filename: String,
    val coverFilename: String,
    val lengthSec: Double,
    val track: Int,
    val disc: Int,
    val trackGain: Double,
    val albumGain: Double,
    val peakAmp: Double,
    val rating: Double,
) : Serializable {
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

/** Return true if [songs] are all from the same album. */
fun sameAlbum(songs: List<Song>) = (songs.size <= 1) ||
    (songs.all { it.album == songs[0].album } && songs.all { it.albumId == songs[0].albumId })

private val keyTags =
    setOf("[dialogue]", "[no artist]", "[unknown]", "[non-album tracks]", "[unset]")
private val keyPrefixes =
    arrayOf(" ", "\"", "'", "’", "(", "[", "<", "...", "a ", "an ", "the ")

/**
 * Reorder [songs] in-place to make it unlikely that songs by the same artist will appear close to
 * each other or that an album will be repeated for a given artist.
 *
 * [songs] should have already been randomly shuffled.
 *
 * This is a reimplementation of the server's spreadSongs function.
 */
fun spreadSongs(songs: MutableList<Song>, rand: Random = Random) {
    val maxSkew = 0.25 // maximum offset to skew songs' positions

    lateinit var shuf: (
        songs: MutableList<Song>,
        outerKeyFunc: ((s: Song) -> String),
        innerKeyFunc: ((s: Song) -> String)?
    ) -> Unit
    shuf = { songs, outerKeyFunc, innerKeyFunc ->
        val groups = mutableMapOf<String, MutableList<Song>>()
        songs.forEach { song ->
            // Group songs using the key function.
            val key = outerKeyFunc(song)
            groups.getOrPut(key, { mutableListOf() }).add(song)
        }

        // Spread out each group across the entire range.
        val dists = mutableMapOf<Song, Double>()
        for (group in groups.values) {
            // Recursively spread out the songs within the group first if needed.
            if (innerKeyFunc != null) shuf(group, innerKeyFunc, null)

            // Apply a random offset at the beginning and then further skew each song's position.
            val off = (1.0 - maxSkew) * rand.nextDouble()
            for ((idx, song) in group.withIndex()) {
                dists[song] = (off + idx + maxSkew * rand.nextDouble()) / group.size
            }
        }
        songs.sortBy { dists[it] }
    }

    shuf(songs, { normalizeForSearch(it.artist) }, { normalizeForSearch(it.album) })
}
