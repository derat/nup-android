package org.erat.nup.test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.graphics.Bitmap;

import com.google.common.io.Files;

import org.erat.nup.BitmapDecoder;
import org.erat.nup.CoverLoader;
import org.erat.nup.Downloader;
import org.erat.nup.NetworkHelper;
import org.erat.nup.Util;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringBufferInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;

public class CoverLoaderTest {
    private FakeTaskRunner mTaskRunner;
    private File mTempDir;
    private CoverLoader mCoverLoader;

    @Mock private Downloader mDownloader;
    @Mock private BitmapDecoder mBitmapDecoder;
    @Mock private NetworkHelper mNetworkHelper;

    HashMap<String, Bitmap> mBitmapDataMap;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTaskRunner = new FakeTaskRunner();
        mTempDir = Files.createTempDir();

        mBitmapDataMap = new HashMap<String, Bitmap>();
        when(mBitmapDecoder.decodeFile(any(File.class)))
                .thenAnswer(
                        new Answer() {
                            @Override
                            public Object answer(InvocationOnMock invocation) {
                                FileInputStream inputStream = null;
                                try {
                                    inputStream =
                                            new FileInputStream(
                                                    (File) invocation.getArguments()[0]);
                                    String fileData = Util.getStringFromInputStream(inputStream);
                                    return mBitmapDataMap.get(fileData);
                                } catch (IOException e) {
                                    return null;
                                } finally {
                                    if (inputStream != null) {
                                        try {
                                            inputStream.close();
                                        } catch (IOException e) {
                                        }
                                    }
                                }
                            }
                        });

        mCoverLoader =
                new CoverLoader(mTempDir, mDownloader, mTaskRunner, mBitmapDecoder, mNetworkHelper);
    }

    @After
    public void tearDown() throws Exception {
        mTempDir.delete();
    }

    @Test
    public void downloadAndCacheCovers() throws Exception {
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(true);

        final URL COVER_URL_1 = new URL("https://www.example.com/cover1.jpg");
        final String DATA_1 = "foo";
        final Bitmap BITMAP_1 = createAndRegisterBitmap(DATA_1);
        HttpURLConnection conn1 = createConnection(200, DATA_1);
        when(mDownloader.download(COVER_URL_1, "GET", Downloader.AuthType.STORAGE, null))
                .thenReturn(conn1);

        final URL COVER_URL_2 = new URL("https://www.example.com/cover2.jpg");
        final String DATA_2 = "bar";
        final Bitmap BITMAP_2 = createAndRegisterBitmap(DATA_2);
        HttpURLConnection conn2 = createConnection(200, DATA_2);
        when(mDownloader.download(COVER_URL_2, "GET", Downloader.AuthType.STORAGE, null))
                .thenReturn(conn2);

        // The first load request for each cover should result in it being downloaded, but it should
        // be cached after that.
        assertEquals(BITMAP_1, mCoverLoader.loadCover(COVER_URL_1));
        assertEquals(BITMAP_2, mCoverLoader.loadCover(COVER_URL_2));
        assertEquals(BITMAP_1, mCoverLoader.loadCover(COVER_URL_1));
        assertEquals(BITMAP_1, mCoverLoader.loadCover(COVER_URL_1));
        assertEquals(BITMAP_2, mCoverLoader.loadCover(COVER_URL_2));
        verify(mDownloader, times(1))
                .download(COVER_URL_1, "GET", Downloader.AuthType.STORAGE, null);
        verify(mDownloader, times(1))
                .download(COVER_URL_2, "GET", Downloader.AuthType.STORAGE, null);
    }

    @Test
    public void skipDownloadWhenNetworkUnavailable() throws Exception {
        final URL COVER_URL = new URL("https://www.example.com/cover.jpg");
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(false);
        assertNull(mCoverLoader.loadCover(COVER_URL));
        verify(mDownloader, never()).download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null);
    }

    @Test
    public void returnNullForMissingFile() throws Exception {
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(true);
        final URL COVER_URL = new URL("https://www.example.com/cover.jpg");
        HttpURLConnection conn = createConnection(404, null);
        when(mDownloader.download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null))
                .thenReturn(conn);
        assertNull(mCoverLoader.loadCover(COVER_URL));
    }

    @Test
    public void returnNullForIOException() throws Exception {
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(true);
        final URL COVER_URL = new URL("https://www.example.com/cover.jpg");
        when(mDownloader.download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null))
                .thenThrow(new IOException());
        assertNull(mCoverLoader.loadCover(COVER_URL));
    }

    @Test
    public void cacheDecodedBitmap() throws Exception {
        when(mNetworkHelper.isNetworkAvailable()).thenReturn(true);

        final URL COVER_URL = new URL("https://www.example.com/cover.jpg");
        final String DATA = "foo";
        final Bitmap BITMAP = createAndRegisterBitmap(DATA);
        HttpURLConnection conn = createConnection(200, DATA);
        when(mDownloader.download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null))
                .thenReturn(conn);

        assertEquals(BITMAP, mCoverLoader.loadCover(COVER_URL));
        assertEquals(BITMAP, mCoverLoader.loadCover(COVER_URL));
        assertEquals(BITMAP, mCoverLoader.loadCover(COVER_URL));

        // The file should've only been decoded once.
        verify(mBitmapDecoder, times(1)).decodeFile(any(File.class));
    }

    private HttpURLConnection createConnection(int statusCode, String data) throws Exception {
        HttpURLConnection conn = Mockito.mock(HttpURLConnection.class);
        when(conn.getResponseCode()).thenReturn(statusCode);
        if (data != null) {
            when(conn.getInputStream()).thenReturn(new StringBufferInputStream(data));
        }
        return conn;
    }

    private Bitmap createAndRegisterBitmap(final String data) {
        Bitmap bitmap = Mockito.mock(Bitmap.class);
        mBitmapDataMap.put(data, bitmap);
        return bitmap;
    }
}
