package org.erat.nup.test

import org.erat.nup.Downloader
import org.erat.nup.NetworkHelper
import org.erat.nup.PlaybackReporter
import org.erat.nup.SongDatabase
import org.erat.nup.SongDatabase.PendingPlaybackReport
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class PlaybackReporterTest {
    private val SONG_ID: Long = 1234
    private val START_DATE = Date(1000 * 1469974953)

    @Mock
    private val mSongDb: SongDatabase? = null

    @Mock
    private val mDownloader: Downloader? = null

    @Mock
    private val mNetworkHelper: NetworkHelper? = null

    @Mock
    private val mSuccessConn: HttpURLConnection? = null

    @Mock
    private val mServerErrorConn: HttpURLConnection? = null
    private var mTaskRunner: FakeTaskRunner? = null
    private var mReportUrl: URL? = null
    @Before
    @Throws(Exception::class)
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mTaskRunner = FakeTaskRunner()
        Mockito.`when`(mSuccessConn!!.responseCode).thenReturn(200)
        Mockito.`when`(mServerErrorConn!!.responseCode).thenReturn(500)
        val reportPath = getReportPath(SONG_ID, START_DATE)
        mReportUrl = getReportURL(reportPath)
        Mockito.`when`(mDownloader!!.getServerUrl(reportPath)).thenReturn(mReportUrl)
        Mockito.`when`(mSongDb!!.allPendingPlaybackReports)
                .thenReturn(ArrayList())
    }

    @Test
    @Throws(Exception::class)
    fun immediateSuccessfulReport() {
        Mockito.`when`(mNetworkHelper!!.isNetworkAvailable).thenReturn(true)
        val reporter = createReporter()
        Mockito.`when`(mSongDb!!.allPendingPlaybackReports)
                .thenReturn(
                        Arrays.asList(PendingPlaybackReport(SONG_ID, START_DATE)))
        Mockito.`when`(mDownloader!!.download(mReportUrl!!, "POST", Downloader.AuthType.SERVER, null))
                .thenReturn(mSuccessConn)
        reporter.report(SONG_ID, START_DATE)
        val inOrder = Mockito.inOrder(mSongDb, mDownloader)
        inOrder.verify(mSongDb).addPendingPlaybackReport(SONG_ID, START_DATE)
        // For reasons that are unclear to me, Mockito permits this download call to happen multiple
        // times. Maybe it's bad interaction with the earlier when() call. Using times(1) doesn't
        // help and I'm also unable to reproduce this with a simpler example. It's a bummer to have
        // a testing library that you can't trust to verify your code.
        inOrder.verify(mDownloader).download(mReportUrl!!, "POST", Downloader.AuthType.SERVER, null)
        inOrder.verify(mSongDb).removePendingPlaybackReport(SONG_ID, START_DATE)
    }

    @Test
    @Throws(Exception::class)
    fun deferReportWhenNetworkUnavailable() {
        Mockito.`when`(mNetworkHelper!!.isNetworkAvailable).thenReturn(false)
        val reporter = createReporter()
        Mockito.verify(mSongDb, Mockito.never()).allPendingPlaybackReports
        reporter.report(SONG_ID, START_DATE)
        Mockito.verify(mSongDb).addPendingPlaybackReport(SONG_ID, START_DATE)
        Mockito.verifyNoMoreInteractions(mDownloader)
    }

    @Test
    @Throws(Exception::class)
    fun deferReportOnServerError() {
        Mockito.`when`(mNetworkHelper!!.isNetworkAvailable).thenReturn(true)
        val reporter = createReporter()
        Mockito.`when`(mSongDb!!.allPendingPlaybackReports)
                .thenReturn(
                        Arrays.asList(PendingPlaybackReport(SONG_ID, START_DATE)))
        Mockito.`when`(mDownloader!!.download(mReportUrl!!, "POST", Downloader.AuthType.SERVER, null))
                .thenReturn(mServerErrorConn)
        reporter.report(SONG_ID, START_DATE)
        val inOrder = Mockito.inOrder(mSongDb, mDownloader)
        inOrder.verify(mSongDb).addPendingPlaybackReport(SONG_ID, START_DATE)
        inOrder.verify(mDownloader).download(mReportUrl!!, "POST", Downloader.AuthType.SERVER, null)
        inOrder.verify(mSongDb, Mockito.never()).removePendingPlaybackReport(SONG_ID, START_DATE)
    }

    @Test
    @Throws(Exception::class)
    fun reportPendingAtCreation() {
        Mockito.`when`(mNetworkHelper!!.isNetworkAvailable).thenReturn(true)
        Mockito.`when`(mSongDb!!.allPendingPlaybackReports)
                .thenReturn(
                        Arrays.asList(PendingPlaybackReport(SONG_ID, START_DATE)))
        Mockito.`when`(mDownloader!!.download(mReportUrl!!, "POST", Downloader.AuthType.SERVER, null))
                .thenReturn(mSuccessConn)
        val reporter = createReporter()
        val inOrder = Mockito.inOrder(mSongDb, mDownloader)
        inOrder.verify(mDownloader).download(mReportUrl!!, "POST", Downloader.AuthType.SERVER, null)
        inOrder.verify(mSongDb).removePendingPlaybackReport(SONG_ID, START_DATE)
    }

    @Test
    @Throws(Exception::class)
    fun reportPendingWhenReportRequested() {
        Mockito.`when`(mNetworkHelper!!.isNetworkAvailable).thenReturn(true)
        val reporter = createReporter()
        val OLD_SONG_ID: Long = 456
        val OLD_START_DATE = Date(1000 * 1470024671)
        val OLD_REPORT_PATH = getReportPath(OLD_SONG_ID, OLD_START_DATE)
        val OLD_REPORT_URL = getReportURL(OLD_REPORT_PATH)
        Mockito.`when`(mDownloader!!.getServerUrl(OLD_REPORT_PATH)).thenReturn(OLD_REPORT_URL)

        // Queue up an old report. Let it succeed, but the next report fail.
        Mockito.`when`(mSongDb!!.allPendingPlaybackReports)
                .thenReturn(
                        Arrays.asList(
                                PendingPlaybackReport(OLD_SONG_ID, OLD_START_DATE),
                                PendingPlaybackReport(SONG_ID, START_DATE)))
        Mockito.`when`(mDownloader.download(OLD_REPORT_URL, "POST", Downloader.AuthType.SERVER, null))
                .thenReturn(mSuccessConn)
        Mockito.`when`(mDownloader.download(mReportUrl!!, "POST", Downloader.AuthType.SERVER, null))
                .thenReturn(mServerErrorConn)
        reporter.report(SONG_ID, START_DATE)
        val inOrder = Mockito.inOrder(mSongDb, mDownloader)
        inOrder.verify(mSongDb).addPendingPlaybackReport(SONG_ID, START_DATE)
        inOrder.verify(mDownloader)
                .download(OLD_REPORT_URL, "POST", Downloader.AuthType.SERVER, null)
        inOrder.verify(mSongDb).removePendingPlaybackReport(OLD_SONG_ID, OLD_START_DATE)
        inOrder.verify(mDownloader).download(mReportUrl!!, "POST", Downloader.AuthType.SERVER, null)

        // Now send a new report and let both it and the previous one succeed.
        val NEW_SONG_ID: Long = 789
        val NEW_START_DATE = Date(1000 * 1470024251)
        val NEW_REPORT_PATH = getReportPath(NEW_SONG_ID, NEW_START_DATE)
        val NEW_REPORT_URL = getReportURL(NEW_REPORT_PATH)
        Mockito.`when`(mDownloader.getServerUrl(NEW_REPORT_PATH)).thenReturn(NEW_REPORT_URL)
        Mockito.`when`(mSongDb.allPendingPlaybackReports)
                .thenReturn(
                        Arrays.asList(
                                PendingPlaybackReport(SONG_ID, START_DATE),
                                PendingPlaybackReport(
                                        NEW_SONG_ID, NEW_START_DATE)))
        Mockito.`when`(mDownloader.download(mReportUrl!!, "POST", Downloader.AuthType.SERVER, null))
                .thenReturn(mSuccessConn)
        Mockito.`when`(mDownloader.download(NEW_REPORT_URL, "POST", Downloader.AuthType.SERVER, null))
                .thenReturn(mSuccessConn)
        reporter.report(NEW_SONG_ID, NEW_START_DATE)
        inOrder.verify(mSongDb).addPendingPlaybackReport(NEW_SONG_ID, NEW_START_DATE)
        inOrder.verify(mDownloader).download(mReportUrl!!, "POST", Downloader.AuthType.SERVER, null)
        inOrder.verify(mSongDb).removePendingPlaybackReport(SONG_ID, START_DATE)
        inOrder.verify(mDownloader)
                .download(NEW_REPORT_URL, "POST", Downloader.AuthType.SERVER, null)
        inOrder.verify(mSongDb).removePendingPlaybackReport(NEW_SONG_ID, NEW_START_DATE)
    }

    private fun createReporter(): PlaybackReporter {
        return PlaybackReporter(mSongDb!!, mDownloader!!, mTaskRunner!!, mNetworkHelper!!)
    }

    private fun getReportPath(songId: Long, startDate: Date): String {
        return String.format(
                "/report_played?songId=%d&startTime=%f", songId, startDate.time / 1000.0)
    }

    @Throws(Exception::class)
    private fun getReportURL(reportPath: String): URL {
        return URL("https", "www.example.com", reportPath)
    }
}