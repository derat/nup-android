/*
 * Copyright 2021 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup.test

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.erat.nup.getSongOrderKey
import org.erat.nup.spreadSongs
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SongTest {
    @Test fun getSongOrderKeyWorks() {
        val artists = arrayListOf<String>(
            "def leppard",
            "Daft Punk",
            " The Black Dog",
            "The Alan Parsons Project",
            "[no artist]",
            "[unset]",
        )
        artists.sortBy { getSongOrderKey(it) }
        assertThat(artists).containsExactly(
            "[no artist]",
            "[unset]",
            "The Alan Parsons Project",
            " The Black Dog",
            "Daft Punk",
            "def leppard",
        ).inOrder()
    }

    @Test fun spreadSongsWorks() {
        val numArtists = 10
        val numSongsPerArtist = 10
        val numAlbums = 5

        val songs = IntArray(numArtists * numSongsPerArtist) { it }.map {
            val artist = "ar${(it / numSongsPerArtist) + 1}"
            val album = "al${(it % numAlbums) + 1}"
            makeSong(artist, "${it + 1}", album, it + 1)
        }.toMutableList()

        // Do a regular Fisher-Yates shuffle and then spread out the songs.
        val rand = Random(0xbeefface)
        for (i in 0..(songs.size - 2)) {
            val j = i + rand.nextInt(songs.size - i)
            songs[i] = songs[j].also { songs[j] = songs[i] }
        }
        spreadSongs(songs, rand)

        // Check that the same artist doesn't appear back-to-back and that we don't play the same
        // album twice in a row for a given artist.
        val lastArtistAlbum = mutableMapOf<String, String>()
        for ((idx, song) in songs.withIndex()) {
            if (idx < songs.size - 1) assertNotEquals(songs[idx + 1].artist, song.artist)
            assertNotEquals(lastArtistAlbum.get(song.artist), song.album)
            lastArtistAlbum[song.artist] = song.album
        }
    }
}
