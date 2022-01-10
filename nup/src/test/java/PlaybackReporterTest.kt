/*
 * Copyright 2016 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup.test

import java.net.HttpURLConnection
import java.net.URL
import java.util.Arrays
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.erat.nup.Downloader
import org.erat.nup.NetworkHelper
import org.erat.nup.PlaybackReporter
import org.erat.nup.SongDatabase
import org.erat.nup.SongDatabase.PendingPlaybackReport
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackReporterTest {
    val SONG_ID: Long = 1234
    val START_DATE = Date(1000 * 1469974953L)

    val scope = TestCoroutineScope()

    lateinit var openMocks: AutoCloseable
    @Mock lateinit var songDb: SongDatabase
    @Mock lateinit var downloader: Downloader
    @Mock lateinit var networkHelper: NetworkHelper
    @Mock lateinit var successConn: HttpURLConnection
    @Mock lateinit var serverErrorConn: HttpURLConnection

    lateinit var reportUrl: URL

    @Before fun setUp() {
        openMocks = MockitoAnnotations.openMocks(this)
        Mockito.`when`(successConn.responseCode).thenReturn(200)
        Mockito.`when`(serverErrorConn.responseCode).thenReturn(500)
        val reportPath = getReportPath(SONG_ID, START_DATE)
        reportUrl = getReportUrl(reportPath)
        Mockito.`when`(downloader.getServerUrl(reportPath)).thenReturn(reportUrl)
        runBlocking {
            Mockito.`when`(songDb.allPendingPlaybackReports()).thenReturn(ArrayList())
        }
    }

    @After fun tearDown() {
        openMocks.close()
    }

    @Test fun immediateSuccessfulReport() = runBlockingTest {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)

        val reporter = createReporter()
        Mockito.`when`(songDb.allPendingPlaybackReports())
            .thenReturn(Arrays.asList(PendingPlaybackReport(SONG_ID, START_DATE)))
        Mockito.`when`(downloader.download(reportUrl, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(successConn)
        scope.launch { reporter.report(SONG_ID, START_DATE) }

        val inOrder = Mockito.inOrder(songDb, downloader)
        inOrder.verify(songDb).addPendingPlaybackReport(SONG_ID, START_DATE)
        // For reasons that are unclear to me, Mockito permits this download call to happen multiple
        // times. Maybe it's bad interaction with the earlier when() call. Using times(1) doesn't
        // help and I'm also unable to reproduce this with a simpler example. It's a bummer to have
        // a testing library that you can't trust to verify your code.
        inOrder.verify(downloader).download(reportUrl, "POST", Downloader.AuthType.SERVER, null)
        inOrder.verify(songDb).removePendingPlaybackReport(SONG_ID, START_DATE)
    }

    @Test fun deferReportWhenNetworkUnavailable() = runBlockingTest {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(false)
        val reporter = createReporter()
        scope.launch { reporter.report(SONG_ID, START_DATE) }
        Mockito.verify(songDb).addPendingPlaybackReport(SONG_ID, START_DATE)
        Mockito.verifyNoMoreInteractions(downloader)
    }

    @Test fun deferReportOnServerError() = runBlockingTest {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)
        Mockito.`when`(songDb.allPendingPlaybackReports())
            .thenReturn(Arrays.asList(PendingPlaybackReport(SONG_ID, START_DATE)))
        Mockito.`when`(downloader.download(reportUrl, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(serverErrorConn)

        scope.launch { createReporter().report(SONG_ID, START_DATE) }

        val inOrder = Mockito.inOrder(songDb, downloader)
        inOrder.verify(songDb).addPendingPlaybackReport(SONG_ID, START_DATE)
        inOrder.verify(downloader).download(reportUrl, "POST", Downloader.AuthType.SERVER, null)
        inOrder.verify(songDb, Mockito.never()).removePendingPlaybackReport(SONG_ID, START_DATE)
    }

    @Test fun reportPendingAtCreation() = runBlockingTest {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)
        Mockito.`when`(songDb.allPendingPlaybackReports())
            .thenReturn(Arrays.asList(PendingPlaybackReport(SONG_ID, START_DATE)))
        Mockito.`when`(downloader.download(reportUrl, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(successConn)

        var reporter = createReporter()
        scope.launch { reporter.reportPending() }

        val inOrder = Mockito.inOrder(songDb, downloader)
        inOrder.verify(downloader).download(reportUrl, "POST", Downloader.AuthType.SERVER, null)
        inOrder.verify(songDb).removePendingPlaybackReport(SONG_ID, START_DATE)
    }

    @Test fun reportPendingWhenReportRequested() = runBlockingTest {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)

        // Queue up an old report. Let it succeed, but the next report fail.
        val OLD_SONG_ID: Long = 456
        val OLD_START_DATE = Date(1000 * 1470024671L)
        val OLD_REPORT_PATH = getReportPath(OLD_SONG_ID, OLD_START_DATE)
        val OLD_REPORT_URL = getReportUrl(OLD_REPORT_PATH)
        Mockito.`when`(downloader.getServerUrl(OLD_REPORT_PATH)).thenReturn(OLD_REPORT_URL)
        Mockito.`when`(songDb.allPendingPlaybackReports()).thenReturn(
            Arrays.asList(
                PendingPlaybackReport(OLD_SONG_ID, OLD_START_DATE),
                PendingPlaybackReport(SONG_ID, START_DATE)
            )
        )
        Mockito.`when`(
            downloader.download(OLD_REPORT_URL, "POST", Downloader.AuthType.SERVER, null)
        ).thenReturn(successConn)
        Mockito.`when`(downloader.download(reportUrl, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(serverErrorConn)

        val reporter = createReporter()
        scope.launch { reporter.report(SONG_ID, START_DATE) }

        val inOrder = Mockito.inOrder(songDb, downloader)
        inOrder.verify(songDb).addPendingPlaybackReport(SONG_ID, START_DATE)
        inOrder.verify(downloader)
            .download(OLD_REPORT_URL, "POST", Downloader.AuthType.SERVER, null)
        inOrder.verify(songDb).removePendingPlaybackReport(OLD_SONG_ID, OLD_START_DATE)
        inOrder.verify(downloader).download(reportUrl, "POST", Downloader.AuthType.SERVER, null)

        // Now send a new report and let both it and the previous one succeed.
        val NEW_SONG_ID: Long = 789
        val NEW_START_DATE = Date(1000 * 1470024251L)
        val NEW_REPORT_PATH = getReportPath(NEW_SONG_ID, NEW_START_DATE)
        val NEW_REPORT_URL = getReportUrl(NEW_REPORT_PATH)
        Mockito.`when`(downloader.getServerUrl(NEW_REPORT_PATH)).thenReturn(NEW_REPORT_URL)
        Mockito.`when`(songDb.allPendingPlaybackReports())
            .thenReturn(
                Arrays.asList(
                    PendingPlaybackReport(SONG_ID, START_DATE),
                    PendingPlaybackReport(
                        NEW_SONG_ID, NEW_START_DATE
                    )
                )
            )
        Mockito.`when`(downloader.download(reportUrl, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(successConn)
        Mockito.`when`(
            downloader.download(NEW_REPORT_URL, "POST", Downloader.AuthType.SERVER, null)
        ).thenReturn(successConn)

        scope.launch { reporter.report(NEW_SONG_ID, NEW_START_DATE) }

        inOrder.verify(songDb).addPendingPlaybackReport(NEW_SONG_ID, NEW_START_DATE)
        inOrder.verify(downloader).download(reportUrl, "POST", Downloader.AuthType.SERVER, null)
        inOrder.verify(songDb).removePendingPlaybackReport(SONG_ID, START_DATE)
        inOrder.verify(downloader)
            .download(NEW_REPORT_URL, "POST", Downloader.AuthType.SERVER, null)
        inOrder.verify(songDb).removePendingPlaybackReport(NEW_SONG_ID, NEW_START_DATE)
    }

    fun createReporter() = PlaybackReporter(songDb, downloader, networkHelper)

    fun getReportPath(songId: Long, startDate: Date): String {
        val start = String.format("%.3f", startDate.time / 1000.0)
        return "/played?songId=$songId&startTime=$start"
    }

    fun getReportUrl(reportPath: String) = URL("https", "www.example.com", reportPath)
}
