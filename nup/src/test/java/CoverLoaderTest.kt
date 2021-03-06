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

        val file1 = "cover1.jpg"
        val data1 = "foo"
        val bitmap1 = createAndRegisterBitmap(data1)
        val conn1 = createConnection(200, data1)
        Mockito.`when`(downloader.getServerUrl(getPath(file1))).thenReturn(getUrl(file1))
        Mockito.`when`(downloader.download(getUrl(file1), "GET", Downloader.AuthType.SERVER, null))
            .thenReturn(conn1)

        val file2 = "cover2.jpg"
        val data2 = "bar"
        val bitmap2 = createAndRegisterBitmap(data2)
        val conn2 = createConnection(200, data2)
        Mockito.`when`(downloader.getServerUrl(getPath(file2))).thenReturn(getUrl(file2))
        Mockito.`when`(downloader.download(getUrl(file2), "GET", Downloader.AuthType.SERVER, null))
            .thenReturn(conn2)

        // The first load request for each cover should result in it being downloaded, but it should
        // be cached after that.
        Assert.assertEquals(bitmap1, load(file1))
        Assert.assertEquals(bitmap2, load(file2))
        Assert.assertEquals(bitmap1, load(file1))
        Assert.assertEquals(bitmap1, load(file1))
        Assert.assertEquals(bitmap2, load(file2))

        Mockito.verify(downloader, Mockito.times(1))
            .download(getUrl(file1), "GET", Downloader.AuthType.SERVER, null)
        Mockito.verify(downloader, Mockito.times(1))
            .download(getUrl(file2), "GET", Downloader.AuthType.SERVER, null)
    }

    @Test fun skipDownloadWhenNetworkUnavailable() {
        val file = "cover.jpg"
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(false)
        Assert.assertNull(load(file))
        Mockito.verifyZeroInteractions(downloader)
    }

    @Test fun returnNullForMissingFile() {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)
        val file = "cover.jpg"
        val conn = createConnection(404, null)
        Mockito.`when`(downloader.getServerUrl(getPath(file))).thenReturn(getUrl(file))
        Mockito.`when`(downloader.download(getUrl(file), "GET", Downloader.AuthType.SERVER, null))
            .thenReturn(conn)
        Assert.assertNull(load(file))
    }

    @Test fun returnNullForIOException() {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)
        val file = "cover.jpg"
        Mockito.`when`(downloader.getServerUrl(getPath(file))).thenReturn(getUrl(file))
        Mockito.`when`(downloader.download(getUrl(file), "GET", Downloader.AuthType.SERVER, null))
            .thenThrow(IOException())
        Assert.assertNull(load(file))
    }

    @Test fun cacheDecodedBitmap() {
        Mockito.`when`(networkHelper.isNetworkAvailable).thenReturn(true)
        val file = "cover.jpg"
        val data = "foo"
        val bitmap = createAndRegisterBitmap(data)
        val conn = createConnection(200, data)
        Mockito.`when`(downloader.getServerUrl(getPath(file))).thenReturn(getUrl(file))
        Mockito.`when`(downloader.download(getUrl(file), "GET", Downloader.AuthType.SERVER, null))
            .thenReturn(conn)
        Assert.assertEquals(bitmap, load(file))
        Assert.assertEquals(bitmap, load(file))
        Assert.assertEquals(bitmap, load(file))
    }

    fun getPath(filename: String) = "/cover?filename=$filename&size=512"
    fun getUrl(filename: String) = URL("https://www.example.org" + getPath(filename))

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

    fun load(path: String): Bitmap? = runBlocking { coverLoader.loadCover(path) }
}
