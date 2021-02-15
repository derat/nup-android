/*
 * Copyright 2021 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

// Key for song counts. Fields may be empty.
class StatsKey(
    val artist: String,
    val album: String,
    val albumId: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (javaClass != other.javaClass) return false
        val ok = other as StatsKey
        return ok.artist === artist && ok.album === album && ok.albumId === albumId
    }

    override fun hashCode(): Int {
        // https://stackoverflow.com/a/113600/6882947
        var result = 5
        result = 37 * result + artist.hashCode()
        result = 37 * result + album.hashCode()
        result = 37 * result + albumId.hashCode()
        return result
    }
}
