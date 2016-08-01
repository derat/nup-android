package org.erat.nup.test;

import org.erat.nup.Downloader;
import org.erat.nup.NetworkHelper;
import org.erat.nup.PlaybackReporter;
import org.erat.nup.SongDatabase;

import org.junit.Before;
import org.junit.Test;

import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PlaybackReporterTest {
    private final long SONG_ID = 1234;
    private final Date START_DATE = new Date(1000 * 1469974953);

    @Mock private SongDatabase mSongDb;
    @Mock private Downloader mDownloader;
    @Mock private NetworkHelper mNetworkHelper;
    @Mock private HttpURLConnection mSuccessConn;
    @Mock private HttpURLConnection mServerErrorConn;

    private FakeTaskRunner mTaskRunner;

    private URL mReportUrl;

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTaskRunner = new FakeTaskRunner();

        when(mSuccessConn.getResponseCode()).thenReturn(200);
        when(mServerErrorConn.getResponseCode()).thenReturn(500);

        String reportPath = getReportPath(SONG_ID, START_DATE);
        mReportUrl = getReportURL(reportPath);
        when(mDownloader.getServerUrl(reportPath)).thenReturn(mReportUrl);

        when(mSongDb.getAllPendingPlaybackReports()).thenReturn(
            new ArrayList<SongDatabase.PendingPlaybackReport>());
    }

    @Test public void immediateSuccessfulReport() throws Exception {
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(true);
        PlaybackReporter reporter = createReporter();

        when(mSongDb.getAllPendingPlaybackReports()).thenReturn(
            Arrays.asList(new SongDatabase.PendingPlaybackReport(SONG_ID, START_DATE)));
        when(mDownloader.download(mReportUrl, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(mSuccessConn);
        reporter.report(SONG_ID, START_DATE);

        InOrder inOrder = inOrder(mSongDb, mDownloader);
        inOrder.verify(mSongDb).addPendingPlaybackReport(SONG_ID, START_DATE);
        // For reasons that are unclear to me, Mockito permits this download call to happen multiple times. Maybe it's
        // bad interaction with the earlier when() call. Using times(1) doesn't help and I'm also unable to reproduce
        // this with a simpler example. It's a bummer to have a testing library that you can't trust to verify your
        // code.
        inOrder.verify(mDownloader).download(mReportUrl, "POST", Downloader.AuthType.SERVER, null);
        inOrder.verify(mSongDb).removePendingPlaybackReport(SONG_ID, START_DATE);
    }

    @Test public void deferReportWhenNetworkUnavailable() throws Exception {
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(false);
        PlaybackReporter reporter = createReporter();
        verify(mSongDb, never()).getAllPendingPlaybackReports();

        reporter.report(SONG_ID, START_DATE);
        verify(mSongDb).addPendingPlaybackReport(SONG_ID, START_DATE);
        verifyNoMoreInteractions(mDownloader);
    }

    @Test public void deferReportOnServerError() throws Exception {
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(true);
        PlaybackReporter reporter = createReporter();

        when(mSongDb.getAllPendingPlaybackReports()).thenReturn(
            Arrays.asList(new SongDatabase.PendingPlaybackReport(SONG_ID, START_DATE)));
        when(mDownloader.download(mReportUrl, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(mServerErrorConn);
        reporter.report(SONG_ID, START_DATE);

        InOrder inOrder = inOrder(mSongDb, mDownloader);
        inOrder.verify(mSongDb).addPendingPlaybackReport(SONG_ID, START_DATE);
        inOrder.verify(mDownloader).download(mReportUrl, "POST", Downloader.AuthType.SERVER, null);
        inOrder.verify(mSongDb, never()).removePendingPlaybackReport(SONG_ID, START_DATE);
    }

    @Test public void reportPendingAtCreation() throws Exception {
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(true);
        when(mSongDb.getAllPendingPlaybackReports()).thenReturn(
            Arrays.asList(new SongDatabase.PendingPlaybackReport(SONG_ID, START_DATE)));
        when(mDownloader.download(mReportUrl, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(mSuccessConn);

        PlaybackReporter reporter = createReporter();

        InOrder inOrder = inOrder(mSongDb, mDownloader);
        inOrder.verify(mDownloader).download(mReportUrl, "POST", Downloader.AuthType.SERVER, null);
        inOrder.verify(mSongDb).removePendingPlaybackReport(SONG_ID, START_DATE);
    }

    @Test public void reportPendingWhenReportRequested() throws Exception {
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(true);
        PlaybackReporter reporter = createReporter();

        final long OLD_SONG_ID = 456;
        final Date OLD_START_DATE = new Date(1000 * 1470024671);
        final String OLD_REPORT_PATH = getReportPath(OLD_SONG_ID, OLD_START_DATE);
        final URL OLD_REPORT_URL = getReportURL(OLD_REPORT_PATH);
        when(mDownloader.getServerUrl(OLD_REPORT_PATH)).thenReturn(OLD_REPORT_URL);

        // Queue up an old report. Let it succeed, but the next report fail.
        when(mSongDb.getAllPendingPlaybackReports()).thenReturn(
            Arrays.asList(
                new SongDatabase.PendingPlaybackReport(OLD_SONG_ID, OLD_START_DATE),
                new SongDatabase.PendingPlaybackReport(SONG_ID, START_DATE)));
        when(mDownloader.download(OLD_REPORT_URL, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(mSuccessConn);
        when(mDownloader.download(mReportUrl, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(mServerErrorConn);

        reporter.report(SONG_ID, START_DATE);

        InOrder inOrder = inOrder(mSongDb, mDownloader);
        inOrder.verify(mSongDb).addPendingPlaybackReport(SONG_ID, START_DATE);
        inOrder.verify(mDownloader).download(OLD_REPORT_URL, "POST", Downloader.AuthType.SERVER, null);
        inOrder.verify(mSongDb).removePendingPlaybackReport(OLD_SONG_ID, OLD_START_DATE);
        inOrder.verify(mDownloader).download(mReportUrl, "POST", Downloader.AuthType.SERVER, null);

        // Now send a new report and let both it and the previous one succeed.
        final long NEW_SONG_ID = 789;
        final Date NEW_START_DATE = new Date(1000 * 1470024251);
        final String NEW_REPORT_PATH = getReportPath(NEW_SONG_ID, NEW_START_DATE);
        final URL NEW_REPORT_URL = getReportURL(NEW_REPORT_PATH);
        when(mDownloader.getServerUrl(NEW_REPORT_PATH)).thenReturn(NEW_REPORT_URL);

        when(mSongDb.getAllPendingPlaybackReports()).thenReturn(
            Arrays.asList(
                new SongDatabase.PendingPlaybackReport(SONG_ID, START_DATE),
                new SongDatabase.PendingPlaybackReport(NEW_SONG_ID, NEW_START_DATE)));
        when(mDownloader.download(mReportUrl, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(mSuccessConn);
        when(mDownloader.download(NEW_REPORT_URL, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(mSuccessConn);

        reporter.report(NEW_SONG_ID, NEW_START_DATE);

        inOrder.verify(mSongDb).addPendingPlaybackReport(NEW_SONG_ID, NEW_START_DATE);
        inOrder.verify(mDownloader).download(mReportUrl, "POST", Downloader.AuthType.SERVER, null);
        inOrder.verify(mSongDb).removePendingPlaybackReport(SONG_ID, START_DATE);
        inOrder.verify(mDownloader).download(NEW_REPORT_URL, "POST", Downloader.AuthType.SERVER, null);
        inOrder.verify(mSongDb).removePendingPlaybackReport(NEW_SONG_ID, NEW_START_DATE);
    }

    private PlaybackReporter createReporter() {
        return new PlaybackReporter(mSongDb, mDownloader, mTaskRunner, mNetworkHelper);
    }

    private String getReportPath(long songId, Date startDate) {
        return String.format("/report_played?songId=%d&startTime=%f",
                             songId, startDate.getTime() / 1000.0);
    }

    private URL getReportURL(String reportPath) throws Exception {
        return new URL("https", "www.example.com", reportPath);
    }
}
