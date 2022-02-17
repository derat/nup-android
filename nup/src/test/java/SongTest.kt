/*
 * Copyright 2021 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup.test

import kotlin.random.Random
import org.erat.nup.getSongOrderKey
import org.erat.nup.getSongSection
import org.erat.nup.songNumberSection
import org.erat.nup.songOtherSection
import org.erat.nup.spreadSongs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SongTest {
    @Test fun getSongOrderKeyWorks() {
        assertEquals("def leppard", getSongOrderKey("Def Leppard"))
        assertEquals("alan parsons project", getSongOrderKey(" The Alan Parsons Project"))
        assertEquals("strangely isolated place", getSongOrderKey("A Strangely Isolated Place"))
        assertEquals("black dog", getSongOrderKey(" The Black Dog"))
        assertEquals("i care because you do", getSongOrderKey("...I Care Because You Do"))
        assertEquals("i care because you do", getSongOrderKey("…I Care Because You Do"))
        assertEquals("endtroducing.....", getSongOrderKey("Endtroducing....."))
        assertEquals("003 + ¥024 + 2x = ¥727", getSongOrderKey("¥003 + ¥024 + 2X = ¥727"))
        assertEquals("近藤浩治", getSongOrderKey("近藤浩治"))
        assertEquals("( )", getSongOrderKey("( )"))
        assertEquals("smart quotes”", getSongOrderKey("“Smart quotes”"))
        assertEquals("![no artist]", getSongOrderKey("[no artist]"))
        assertEquals("![unset]", getSongOrderKey("[unset]"))
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
