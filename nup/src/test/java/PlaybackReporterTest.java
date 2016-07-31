package org.erat.nup.test;

import android.test.mock.MockContext;

import org.erat.nup.PlaybackReporter;
import org.erat.nup.SongDatabase;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PlaybackReporterTest {
    @Mock private MockContext mContext;
    @Mock private SongDatabase mSongDb;

    private PlaybackReporter mReporter;

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        // FIXME: This crashes due to mContext not returning real services.
        mReporter = new PlaybackReporter(mContext, mSongDb);
    }

    @Test public void trueIsTrue() throws Exception {
        assertEquals(true, true);
    }
}
