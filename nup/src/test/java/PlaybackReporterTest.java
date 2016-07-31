package org.erat.nup.test;

import org.erat.nup.Downloader;
import org.erat.nup.NetworkHelper;
import org.erat.nup.PlaybackReporter;
import org.erat.nup.SongDatabase;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PlaybackReporterTest {
    @Mock private SongDatabase mSongDb;
    @Mock private Downloader mDownloader;
    @Mock private NetworkHelper mNetworkHelper;

    private PlaybackReporter mReporter;

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mReporter = new PlaybackReporter(mSongDb, mDownloader, mNetworkHelper);
    }

    @Test public void trueIsTrue() throws Exception {
        assertEquals(true, true);
    }
}
