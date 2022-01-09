/*
 * Copyright 2022 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup.test

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.MoreExecutors
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import org.erat.nup.Downloader
import org.erat.nup.FileCache
import org.erat.nup.NetworkHelper
import org.erat.nup.Song
import org.erat.nup.SongDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = intArrayOf(Build.VERSION_CODES.S), // 31
    packageName = "org.erat.nup"
)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SongDatabaseTest {
    val scope = TestCoroutineScope()
    lateinit var db: SongDatabase

    lateinit var openMocks: AutoCloseable
    @Mock lateinit var cache: FileCache
    @Mock lateinit var downloader: Downloader
    @Mock lateinit var networkHelper: NetworkHelper

    // Listens for updates from [db].
    val listener = object : SongDatabase.Listener {
        override fun onSyncChange(state: SongDatabase.SyncState, updatedSongs: Int) {
            when (state) {
                SongDatabase.SyncState.UPDATING_SONGS -> lastSyncUpdatedSongs = updatedSongs
                SongDatabase.SyncState.DELETING_SONGS -> lastSyncDeletedSongs = updatedSongs
                else -> {}
            }
        }
        override fun onSyncDone(success: Boolean, message: String) { lastSyncSuccess = success }
        override fun onAggregateDataUpdate() { numAggregateDataUpdates++ }
    }
    var lastSyncUpdatedSongs = 0
    var lastSyncDeletedSongs = 0
    var lastSyncSuccess = false
    var numAggregateDataUpdates = 0

    // Server info returned by [downloader].
    var serverNowNs = 0L
    data class SongInfo(var song: Song, var lastMod: Long = 0L)
    val serverSongs = mutableListOf<SongInfo>()
    val serverDelSongs = mutableListOf<SongInfo>()

    // Matches HTTP request paths from [SongDatabase.syncSongUpdates].
    val exportRegex = Regex(
        "^/export\\?type=song&omit=plays,sha1&minLastModifiedNsec=(\\d+)&max=\\d+(&deleted=1)?$"
    )

    @Before fun setUp() {
        ShadowLog.stream = System.out // preserve log calls

        openMocks = MockitoAnnotations.openMocks(this)
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)
        Mockito.`when`(downloader.downloadString(Mockito.anyString())).thenAnswer(
            object : Answer<Downloader.DownloadStringResult> {
                override fun answer(inv: InvocationOnMock): Downloader.DownloadStringResult {
                    val path = inv.getArguments()[0] as String
                    return when {
                        (path == "/now") ->
                            Downloader.DownloadStringResult(serverNowNs.toString(), null)
                        (path == "/presets") -> Downloader.DownloadStringResult("[]", null)
                        exportRegex.matches(path) -> {
                            val match = exportRegex.find(path)!!
                            val lastMod = match.groupValues[1].toLong()
                            val del = !match.groupValues[2].isEmpty()
                            val body = (if (del) serverDelSongs else serverSongs)
                                .filter { it.lastMod >= lastMod }
                                .map { songToJson(it.song) }
                                .joinToString("\n")
                            return Downloader.DownloadStringResult(body, null)
                        }
                        else -> throw RuntimeException("Unexpected request for $path")
                    }
                }
            }
        )

        // Reset [listener] data.
        lastSyncUpdatedSongs = 0
        lastSyncDeletedSongs = 0
        lastSyncSuccess = false
        numAggregateDataUpdates = 0

        // Reset server data.
        serverNowNs = 0L
        serverSongs.clear()
        serverDelSongs.clear()

        // TODO: Wait for aggregate data to be loaded? Maybe inject a Dispatcher into SongDatabase
        // to use instead of Dispatchers.IO: https://stackoverflow.com/a/59637704
        db = SongDatabase(
            ApplicationProvider.getApplicationContext(),
            scope,
            listener,
            MoreExecutors.directExecutor(),
            cache,
            downloader,
            networkHelper,
            updateExecutor = MoreExecutors.newDirectExecutorService(),
        )
    }

    @After fun tearDown() {
        db.quit()
        openMocks.close()
    }

    @Test fun basicSyncAndQuery() {
        val s1 = makeSong("Artist A", "Track 1", "Album 1", 1, rating = 0.75)
        val s2 = makeSong("Artist A", "Track 2", "Album 1", 2, rating = 0.25)
        val s3 = makeSong("Artist B feat. C", "Track 3", "Album 1", 3, rating = 1.0)
        val s4 = makeSong("Artist B", "Track 1", "Album 2", 1, rating = -1.0)
        serverSongs.addAll(listOf(SongInfo(s1), SongInfo(s2), SongInfo(s3), SongInfo(s4)))

        runBlocking { db.syncWithServer() }
        Assert.assertEquals(SongDatabase.SyncState.IDLE, db.syncState)
        Assert.assertTrue(lastSyncSuccess)
        Assert.assertEquals(4, lastSyncUpdatedSongs)
        Assert.assertTrue(db.aggregateDataLoaded)
        Assert.assertEquals(4, db.numSongs)

        Assert.assertEquals(listOf(s1, s2, s3, s4), runBlocking { db.query() })
        Assert.assertEquals(listOf(s1, s2), runBlocking { db.query(artist = "Artist A") })
        Assert.assertEquals(listOf(s4), runBlocking { db.query(artist = "Artist B") })
        Assert.assertEquals(listOf(s3, s4), runBlocking { db.query(artistPrefix = "Artist B") })
        Assert.assertEquals(listOf(s1, s4), runBlocking { db.query(title = "Track 1") })
        Assert.assertEquals(listOf(s1, s2, s3), runBlocking { db.query(album = "Album 1") })
        Assert.assertEquals(listOf(s2), runBlocking { db.query(songId = s2.id) })
        Assert.assertEquals(
            listOf(s2, s3),
            runBlocking { db.query(songIds = listOf(s2.id, s3.id)) }
        )
        Assert.assertEquals(listOf(s1, s3), runBlocking { db.query(minRating = 0.75) })
        Assert.assertEquals(listOf(s3), runBlocking { db.query(minRating = 1.0) })
        Assert.assertEquals(
            listOf(s3, s4),
            runBlocking { db.query(artist = "B", substring = true) }
        )
        Assert.assertEquals(listOf<Song>(), runBlocking { db.query(artist = "Somebody Else") })
    }

    @Test fun syncUpdates() {
        // Add some songs at time 0 and sync at time 1.
        val s1 = makeSong("Artist A", "Track 1", "Album 1", 1, rating = 0.75)
        val s2 = makeSong("Artist A", "Track 2", "Album 1", 2, rating = 0.25)
        serverSongs.addAll(listOf(SongInfo(s1), SongInfo(s2)))
        serverNowNs++
        runBlocking { db.syncWithServer() }
        Assert.assertTrue(lastSyncSuccess)
        Assert.assertEquals(2, lastSyncUpdatedSongs)
        Assert.assertEquals(0, lastSyncDeletedSongs)
        Assert.assertEquals(2, db.numSongs)
        Assert.assertEquals(listOf(s1, s2), runBlocking { db.query() })

        // At time 1, update the first song, delete the second song, and add a third song.
        serverNowNs++
        val s1u = makeSong("Artist A", "Track 1", "Album 1", 1, rating = 0.5)
        serverSongs[0] = SongInfo(s1u, serverNowNs)
        serverDelSongs.add(SongInfo(s2, serverNowNs))
        val s3 = makeSong("Artist A", "Track 3", "Album 1", 3, rating = 1.0)
        serverSongs.add(SongInfo(s3, serverNowNs))
        runBlocking { db.syncWithServer() }
        Assert.assertTrue(lastSyncSuccess)
        Assert.assertEquals(2, lastSyncUpdatedSongs)
        Assert.assertEquals(1, lastSyncDeletedSongs)
        Assert.assertEquals(2, db.numSongs)
        Assert.assertEquals(listOf(s1u, s3), runBlocking { db.query() })
    }

    // TODO: Add test for aggregate data.
    // TODO: Add test for presets.

    @Test fun playbackReports() {
        val r1 = SongDatabase.PendingPlaybackReport(1, Date(1))
        val r2 = SongDatabase.PendingPlaybackReport(2, Date(2))
        db.addPendingPlaybackReport(r1.songId, r1.startDate)
        db.addPendingPlaybackReport(r2.songId, r2.startDate)
        Assert.assertEquals(listOf(r1, r2), db.allPendingPlaybackReports())

        db.removePendingPlaybackReport(r1.songId, r1.startDate)
        Assert.assertEquals(listOf(r2), db.allPendingPlaybackReports())

        db.removePendingPlaybackReport(r2.songId, r2.startDate)
        Assert.assertTrue(db.allPendingPlaybackReports().isEmpty())
    }
}

/** Construct a [Song] based on the supplied information. */
private fun makeSong(
    artist: String,
    title: String,
    album: String,
    track: Int,
    disc: Int = 1,
    rating: Double? = null,
): Song {
    val albumId = UUID.nameUUIDFromBytes(album.toByteArray()).toString()
    val size = artist.length + title.length + album.length
    return Song(
        // [SongDatabase.query] expects this to be positive.
        id = "$artist/$title/$album/$track".hashCode().toLong() + Int.MAX_VALUE,
        artist = artist,
        title = title,
        album = album,
        albumId = albumId,
        filename = "$album/$artist-$title.mp3",
        coverFilename = "$albumId.jpg",
        lengthSec = 4 * size,
        track = track,
        disc = disc,
        trackGain = 6.5,
        albumGain = 7.5,
        peakAmp = 1.0,
        rating = if (rating != null) rating else (size % 5) / 4.0,
    )
}

/** Convert a [Song] into a JSON string as expected by [SongDatabase.syncSongUpdates]. */
private fun songToJson(s: Song): String {
    val o = JSONObject()
    o.put("songId", s.id)
    o.put("filename", s.filename)
    o.put("coverFilename", s.coverFilename)
    o.put("artist", s.artist)
    o.put("title", s.title)
    o.put("album", s.album)
    o.put("albumId", s.albumId)
    o.put("track", s.track)
    o.put("disc", s.disc)
    o.put("length", s.lengthSec.toDouble())
    o.put("trackGain", s.trackGain)
    o.put("albumGain", s.albumGain)
    o.put("peakAmp", s.peakAmp)
    o.put("rating", s.rating)
    return o.toString()
}
