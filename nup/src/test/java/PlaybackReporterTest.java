package org.erat.nup.test;

import org.erat.nup.Downloader;
import org.erat.nup.NetworkHelper;
import org.erat.nup.PlaybackReporter;
import org.erat.nup.SongDatabase;

import org.junit.Before;
import org.junit.Test;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Date;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PlaybackReporterTest {
    // Timeout for verifying async tasks in milliseconds.
    private final long TIMEOUT_MS = 1000;

    private final long SONG_ID = 1234;
    private final Date START_DATE = new Date(1000 * 1469974953);

    private final FakeTaskRunner mTaskRunner = new FakeTaskRunner();

    @Mock private SongDatabase mSongDb;
    @Mock private Downloader mDownloader;
    @Mock private NetworkHelper mNetworkHelper;

    private HttpURLConnection mSuccessConn;
    private HttpURLConnection mServerErrorConn;

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mSuccessConn = Mockito.mock(HttpURLConnection.class);
        when(mSuccessConn.getResponseCode()).thenReturn(200);

        mServerErrorConn = Mockito.mock(HttpURLConnection.class);
        when(mServerErrorConn.getResponseCode()).thenReturn(500);
    }

    @Test public void immediateSuccessfulReport() throws Exception {
        final String REPORT_PATH = getReportPath(SONG_ID, START_DATE);
        final URL REPORT_URL = getReportURL(REPORT_PATH);
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(true);
        when(mDownloader.getServerUrl(REPORT_PATH)).thenReturn(REPORT_URL);
        when(mDownloader.download(REPORT_URL, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(mSuccessConn);

        PlaybackReporter reporter = createReporter();
        reporter.report(SONG_ID, START_DATE);

        verify(mDownloader, times(1)).download(REPORT_URL, "POST", Downloader.AuthType.SERVER, null);
    }

    @Test public void deferReportWhenNetworkUnavailable() throws Exception {
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(false);

        PlaybackReporter reporter = createReporter();
        reporter.report(SONG_ID, START_DATE);

        verify(mSongDb, times(1)).addPendingPlaybackReport(SONG_ID, START_DATE);
        verifyNoMoreInteractions(mDownloader);
    }

    @Test public void deferReportOnServerError() throws Exception {
        final String REPORT_PATH = getReportPath(SONG_ID, START_DATE);
        final URL REPORT_URL = getReportURL(REPORT_PATH);
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(true);
        when(mDownloader.getServerUrl(REPORT_PATH)).thenReturn(REPORT_URL);
        when(mDownloader.download(REPORT_URL, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(mServerErrorConn);

        PlaybackReporter reporter = createReporter();
        reporter.report(SONG_ID, START_DATE);

        verify(mDownloader, times(1)).download(REPORT_URL, "POST", Downloader.AuthType.SERVER, null);
        verify(mSongDb, times(1)).addPendingPlaybackReport(SONG_ID, START_DATE);
    }

    @Test public void reportPendingAtCreation() throws Exception {
        final String REPORT_PATH = getReportPath(SONG_ID, START_DATE);
        final URL REPORT_URL = getReportURL(REPORT_PATH);
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(true);
        when(mSongDb.getAllPendingPlaybackReports()).thenReturn(
            Arrays.asList(new SongDatabase.PendingPlaybackReport(SONG_ID, START_DATE)));
        when(mDownloader.getServerUrl(REPORT_PATH)).thenReturn(REPORT_URL);
        when(mDownloader.download(REPORT_URL, "POST", Downloader.AuthType.SERVER, null))
            .thenReturn(mSuccessConn);

        PlaybackReporter reporter = createReporter();

        verify(mDownloader, times(1)).download(REPORT_URL, "POST", Downloader.AuthType.SERVER, null);
        verify(mSongDb, times(1)).removePendingPlaybackReport(SONG_ID, START_DATE);
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
