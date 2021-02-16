/*
 * Copyright 2016 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup.test

import android.graphics.Bitmap
import com.google.common.io.Files
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.StringBufferInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.test.TestCoroutineScope
import org.erat.nup.BitmapDecoder
import org.erat.nup.CoverLoader
import org.erat.nup.Downloader
import org.erat.nup.NetworkHelper
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.stubbing.Answer

class CoverLoaderTest {
    val scope = TestCoroutineScope()

    lateinit var tempDir: File
    lateinit var coverLoader: CoverLoader
    lateinit var bitmapDataMap: HashMap<String, Bitmap>

    @Mock lateinit var downloader: Downloader
    @Mock lateinit var bitmapDecoder: BitmapDecoder
    @Mock lateinit var networkHelper: NetworkHelper

    @Before fun setUp() {
        MockitoAnnotations.initMocks(this)
        tempDir = Files.createTempDir()
        bitmapDataMap = HashMap()
        Mockito.`when`(bitmapDecoder.decodeFile(MockitoHelper.anyObject()))
            .thenAnswer(
                Answer<Any?> { invocation ->
                    var inputStream: FileInputStream? = null
                    try {
                        inputStream = FileInputStream(invocation.arguments[0] as File)
                        val fileData = inputStream.bufferedReader().use(BufferedReader::readText)
                        return@Answer bitmapDataMap[fileData]
                    } catch (e: IOException) {
                        return@Answer null
                    } finally {
                        inputStream?.close()
                    }
                }
            )

        coverLoader = CoverLoader(tempDir, downloader, bitmapDecoder, networkHelper)
    }

    @After fun tearDown() {
        tempDir.delete()
    }

    @Test fun downloadAndCacheCovers() {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)

        val COVER_URL_1 = URL("https://www.example.com/cover1.jpg")
        val DATA_1 = "foo"
        val BITMAP_1 = createAndRegisterBitmap(DATA_1)
        val conn1 = createConnection(200, DATA_1)
        Mockito.`when`(downloader.download(COVER_URL_1, "GET", Downloader.AuthType.STORAGE, null))
            .thenReturn(conn1)

        val COVER_URL_2 = URL("https://www.example.com/cover2.jpg")
        val DATA_2 = "bar"
        val BITMAP_2 = createAndRegisterBitmap(DATA_2)
        val conn2 = createConnection(200, DATA_2)
        Mockito.`when`(
            downloader.download(COVER_URL_2, "GET", Downloader.AuthType.STORAGE, null)
        ).thenReturn(conn2)

        // The first load request for each cover should result in it being downloaded, but it should
        // be cached after that.
        Assert.assertEquals(BITMAP_1, coverLoader.loadCover(COVER_URL_1))
        Assert.assertEquals(BITMAP_2, coverLoader.loadCover(COVER_URL_2))
        Assert.assertEquals(BITMAP_1, coverLoader.loadCover(COVER_URL_1))
        Assert.assertEquals(BITMAP_1, coverLoader.loadCover(COVER_URL_1))
        Assert.assertEquals(BITMAP_2, coverLoader.loadCover(COVER_URL_2))

        Mockito.verify(downloader, Mockito.times(1))
            .download(COVER_URL_1, "GET", Downloader.AuthType.STORAGE, null)
        Mockito.verify(downloader, Mockito.times(1))
            .download(COVER_URL_2, "GET", Downloader.AuthType.STORAGE, null)
    }

    @Test fun skipDownloadWhenNetworkUnavailable() {
        val COVER_URL = URL("https://www.example.com/cover.jpg")
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(false)
        Assert.assertNull(coverLoader.loadCover(COVER_URL))
        Mockito.verify(downloader, Mockito.never())
            .download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null)
    }

    @Test fun returnNullForMissingFile() {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)
        val COVER_URL = URL("https://www.example.com/cover.jpg")
        val conn = createConnection(404, null)
        Mockito.`when`(downloader.download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null))
            .thenReturn(conn)
        Assert.assertNull(coverLoader.loadCover(COVER_URL))
    }

    @Test fun returnNullForIOException() {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)
        val COVER_URL = URL("https://www.example.com/cover.jpg")
        Mockito.`when`(downloader.download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null))
            .thenThrow(IOException())
        Assert.assertNull(coverLoader.loadCover(COVER_URL))
    }

    @Test fun cacheDecodedBitmap() {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)
        val COVER_URL = URL("https://www.example.com/cover.jpg")
        val DATA = "foo"
        val BITMAP = createAndRegisterBitmap(DATA)
        val conn = createConnection(200, DATA)
        Mockito.`when`(downloader.download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null))
            .thenReturn(conn)
        Assert.assertEquals(BITMAP, coverLoader.loadCover(COVER_URL))
        Assert.assertEquals(BITMAP, coverLoader.loadCover(COVER_URL))
        Assert.assertEquals(BITMAP, coverLoader.loadCover(COVER_URL))

        // The file should've only been decoded once.
        Mockito.verify(bitmapDecoder, Mockito.times(1)).decodeFile(MockitoHelper.anyObject())
    }

    fun createConnection(statusCode: Int, data: String?): HttpURLConnection {
        val conn = Mockito.mock(HttpURLConnection::class.java)
        Mockito.`when`(conn.responseCode).thenReturn(statusCode)
        if (data != null) Mockito.`when`(conn.inputStream).thenReturn(StringBufferInputStream(data))
        return conn
    }

    fun createAndRegisterBitmap(data: String): Bitmap {
        val bitmap = Mockito.mock(Bitmap::class.java)
        bitmapDataMap[data] = bitmap
        return bitmap
    }
}
