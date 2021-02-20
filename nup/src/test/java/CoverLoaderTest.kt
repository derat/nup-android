/*
 * Copyright 2016 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup.test

import android.graphics.Bitmap
import android.test.mock.MockContext
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
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

class CoverLoaderTest {
    lateinit var tempDir: File
    lateinit var coverLoader: CoverLoader
    lateinit var bitmapDataMap: HashMap<String, Bitmap>

    @Mock lateinit var context: MockContext
    @Mock lateinit var downloader: Downloader
    @Mock lateinit var networkHelper: NetworkHelper

    @Before fun setUp() {
        MockitoAnnotations.initMocks(this)

        tempDir = Files.createTempDirectory(null).toFile()
        tempDir.deleteOnExit()
        Mockito.`when`(context.externalCacheDir).thenReturn(tempDir)

        bitmapDataMap = HashMap()
        coverLoader = object : CoverLoader(context, downloader, networkHelper) {
            override fun decodeFile(path: String): Bitmap? {
                return try {
                    val fileData = File(path)
                        .inputStream().bufferedReader().use(BufferedReader::readText)
                    bitmapDataMap[fileData]
                } catch (e: IOException) {
                    null
                }
            }
        }
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
        Assert.assertEquals(BITMAP_1, load(COVER_URL_1))
        Assert.assertEquals(BITMAP_2, load(COVER_URL_2))
        Assert.assertEquals(BITMAP_1, load(COVER_URL_1))
        Assert.assertEquals(BITMAP_1, load(COVER_URL_1))
        Assert.assertEquals(BITMAP_2, load(COVER_URL_2))

        Mockito.verify(downloader, Mockito.times(1))
            .download(COVER_URL_1, "GET", Downloader.AuthType.STORAGE, null)
        Mockito.verify(downloader, Mockito.times(1))
            .download(COVER_URL_2, "GET", Downloader.AuthType.STORAGE, null)
    }

    @Test fun skipDownloadWhenNetworkUnavailable() {
        val COVER_URL = URL("https://www.example.com/cover.jpg")
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(false)
        Assert.assertNull(load(COVER_URL))
        Mockito.verify(downloader, Mockito.never())
            .download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null)
    }

    @Test fun returnNullForMissingFile() {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)
        val COVER_URL = URL("https://www.example.com/cover.jpg")
        val conn = createConnection(404, null)
        Mockito.`when`(downloader.download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null))
            .thenReturn(conn)
        Assert.assertNull(load(COVER_URL))
    }

    @Test fun returnNullForIOException() {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)
        val COVER_URL = URL("https://www.example.com/cover.jpg")
        Mockito.`when`(downloader.download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null))
            .thenThrow(IOException())
        Assert.assertNull(load(COVER_URL))
    }

    @Test fun cacheDecodedBitmap() {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)
        val COVER_URL = URL("https://www.example.com/cover.jpg")
        val DATA = "foo"
        val BITMAP = createAndRegisterBitmap(DATA)
        val conn = createConnection(200, DATA)
        Mockito.`when`(downloader.download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null))
            .thenReturn(conn)
        Assert.assertEquals(BITMAP, load(COVER_URL))
        Assert.assertEquals(BITMAP, load(COVER_URL))
        Assert.assertEquals(BITMAP, load(COVER_URL))
    }

    fun createConnection(statusCode: Int, data: String?): HttpURLConnection {
        val conn = Mockito.mock(HttpURLConnection::class.java)
        Mockito.`when`(conn.responseCode).thenReturn(statusCode)
        if (data != null) {
            Mockito.`when`(conn.inputStream).thenReturn(ByteArrayInputStream(data.toByteArray()))
        }
        return conn
    }

    fun createAndRegisterBitmap(data: String): Bitmap {
        val bitmap = Mockito.mock(Bitmap::class.java)
        bitmapDataMap[data] = bitmap
        return bitmap
    }

    fun load(url: URL): Bitmap? = runBlocking { coverLoader.loadCover(url) }
}
