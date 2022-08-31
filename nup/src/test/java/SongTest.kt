/*
 * Copyright 2021 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup.test

import kotlin.random.Random
import org.erat.nup.getSongSection
import org.erat.nup.getSongSortKey
import org.erat.nup.songNumberSection
import org.erat.nup.songOtherSection
import org.erat.nup.spreadSongs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SongTest {
    @Test fun getSongSortKeyWorks() {
        assertEquals("def leppard", getSongSortKey("Def Leppard"))
        assertEquals("alan parsons project", getSongSortKey(" The Alan Parsons Project"))
        assertEquals("strangely isolated place", getSongSortKey("A Strangely Isolated Place"))
        assertEquals("black dog", getSongSortKey(" The Black Dog"))
        assertEquals("i care because you do", getSongSortKey("...I Care Because You Do"))
        assertEquals("i care because you do", getSongSortKey("…I Care Because You Do"))
        assertEquals("endtroducing.....", getSongSortKey("Endtroducing....."))
        assertEquals("003 + ¥024 + 2x = ¥727", getSongSortKey("¥003 + ¥024 + 2X = ¥727"))
        assertEquals("近藤浩治", getSongSortKey("近藤浩治"))
        assertEquals("( )", getSongSortKey("( )"))
        assertEquals("smart quotes”", getSongSortKey("“Smart quotes”"))
        assertEquals("![no artist]", getSongSortKey("[no artist]"))
        assertEquals("![unset]", getSongSortKey("[unset]"))
    }

    @Test fun getSongSectionWorks() {
        assertEquals("D", getSongSection("Def Leppard"))
        assertEquals("A", getSongSection(" The Alan Parsons Project"))
        assertEquals(songNumberSection, getSongSection("¥003 + ¥024 + 2X = ¥727"))
        assertEquals(songNumberSection, getSongSection("( )"))
        assertEquals(songNumberSection, getSongSection("[no artist]"))
        assertEquals(songNumberSection, getSongSection("[unset]"))
        assertEquals(songOtherSection, getSongSection("近藤浩治"))
        assertEquals(songOtherSection, getSongSection("Ümlaut"))
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
