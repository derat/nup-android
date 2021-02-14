// Copyright 2021 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

// Key for song counts. Fields may be empty.
internal class StatsKey(
        @JvmField val artist: String,
        @JvmField val album: String,
        @JvmField val albumId: String) {
    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o == null) return false
        if (javaClass != o.javaClass) return false
        val ok = o as StatsKey
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