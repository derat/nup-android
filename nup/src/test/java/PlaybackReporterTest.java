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
import java.util.Date;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PlaybackReporterTest {
    // Timeout for verifying async tasks in milliseconds.
    private final long TIMEOUT_MS = 1000;

    private final FakeTaskRunner mTaskRunner = new FakeTaskRunner();

    @Mock private SongDatabase mSongDb;
    @Mock private Downloader mDownloader;
    @Mock private NetworkHelper mNetworkHelper;

    private HttpURLConnection mSuccessfulConnection;

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mSuccessfulConnection = Mockito.mock(HttpURLConnection.class);
        when(mSuccessfulConnection.getResponseCode()).thenReturn(200);
    }

    @Test public void immediateSuccessfulReport() throws Exception {
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(true);
        final long SONG_ID = 1234;
        final Date START_DATE = new Date(1000 * 1469974953);

        final String EXPECTED_URL = String.format(
            "/report_played?songId=%d&startTime=%f", SONG_ID, START_DATE.getTime() / 1000.0);
        final URL DOWNLOAD_URL = new URL("http://www.example.com/foo?id=bar");
        when(mDownloader.getServerUrl(EXPECTED_URL)).thenReturn(DOWNLOAD_URL);
        when(mDownloader.download(DOWNLOAD_URL, "POST", Downloader.AuthType.SERVER, null)).thenReturn(mSuccessfulConnection);

        PlaybackReporter reporter = createReporter();
        reporter.report(SONG_ID, START_DATE);

        verify(mDownloader, times(1)).download(DOWNLOAD_URL, "POST", Downloader.AuthType.SERVER, null);
    }

    private PlaybackReporter createReporter() {
        return new PlaybackReporter(mSongDb, mDownloader, mTaskRunner, mNetworkHelper);
    }
}
