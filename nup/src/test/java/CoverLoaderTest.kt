package org.erat.nup.test

import android.graphics.Bitmap
import com.google.common.io.Files
import org.erat.nup.BitmapDecoder
import org.erat.nup.CoverLoader
import org.erat.nup.Downloader
import org.erat.nup.NetworkHelper
import org.erat.nup.Util.getStringFromInputStream
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.stubbing.Answer
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.StringBufferInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class CoverLoaderTest {
    private var mTaskRunner: FakeTaskRunner? = null
    private var mTempDir: File? = null
    private var mCoverLoader: CoverLoader? = null

    @Mock private val mDownloader: Downloader? = null
    @Mock private val mBitmapDecoder: BitmapDecoder? = null
    @Mock private val mNetworkHelper: NetworkHelper? = null
    var mBitmapDataMap: HashMap<String, Bitmap>? = null

    @Before
    @Throws(Exception::class)
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mTaskRunner = FakeTaskRunner()
        mTempDir = Files.createTempDir()
        mBitmapDataMap = HashMap()
        Mockito.`when`(mBitmapDecoder!!.decodeFile(MockitoHelper.anyObject()))
                .thenAnswer(
                        Answer<Any?> { invocation ->
                            var inputStream: FileInputStream? = null
                            try {
                                inputStream = FileInputStream(invocation.arguments[0] as File)
                                val fileData = getStringFromInputStream(inputStream)
                                return@Answer mBitmapDataMap!![fileData]
                            } catch (e: IOException) {
                                return@Answer null
                            } finally {
                                if (inputStream != null) {
                                    try {
                                        inputStream.close()
                                    } catch (e: IOException) {
                                    }
                                }
                            }
                        })
        mCoverLoader = CoverLoader(mTempDir!!, mDownloader!!, mTaskRunner!!, mBitmapDecoder, mNetworkHelper!!)
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        mTempDir!!.delete()
    }

    @Test
    @Throws(Exception::class)
    fun downloadAndCacheCovers() {
        Mockito.`when`(mNetworkHelper!!.isNetworkAvailable).thenReturn(true)
        val COVER_URL_1 = URL("https://www.example.com/cover1.jpg")
        val DATA_1 = "foo"
        val BITMAP_1 = createAndRegisterBitmap(DATA_1)
        val conn1 = createConnection(200, DATA_1)
        Mockito.`when`(mDownloader!!.download(COVER_URL_1, "GET", Downloader.AuthType.STORAGE, null))
                .thenReturn(conn1)
        val COVER_URL_2 = URL("https://www.example.com/cover2.jpg")
        val DATA_2 = "bar"
        val BITMAP_2 = createAndRegisterBitmap(DATA_2)
        val conn2 = createConnection(200, DATA_2)
        Mockito.`when`(mDownloader.download(COVER_URL_2, "GET", Downloader.AuthType.STORAGE, null))
                .thenReturn(conn2)

        // The first load request for each cover should result in it being downloaded, but it should
        // be cached after that.
        Assert.assertEquals(BITMAP_1, mCoverLoader!!.loadCover(COVER_URL_1))
        Assert.assertEquals(BITMAP_2, mCoverLoader!!.loadCover(COVER_URL_2))
        Assert.assertEquals(BITMAP_1, mCoverLoader!!.loadCover(COVER_URL_1))
        Assert.assertEquals(BITMAP_1, mCoverLoader!!.loadCover(COVER_URL_1))
        Assert.assertEquals(BITMAP_2, mCoverLoader!!.loadCover(COVER_URL_2))
        Mockito.verify(mDownloader, Mockito.times(1))
                .download(COVER_URL_1, "GET", Downloader.AuthType.STORAGE, null)
        Mockito.verify(mDownloader, Mockito.times(1))
                .download(COVER_URL_2, "GET", Downloader.AuthType.STORAGE, null)
    }

    @Test
    @Throws(Exception::class)
    fun skipDownloadWhenNetworkUnavailable() {
        val COVER_URL = URL("https://www.example.com/cover.jpg")
        Mockito.`when`(mNetworkHelper!!.isNetworkAvailable).thenReturn(false)
        Assert.assertNull(mCoverLoader!!.loadCover(COVER_URL))
        Mockito.verify(mDownloader!!, Mockito.never()).download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null)
    }

    @Test
    @Throws(Exception::class)
    fun returnNullForMissingFile() {
        Mockito.`when`(mNetworkHelper!!.isNetworkAvailable).thenReturn(true)
        val COVER_URL = URL("https://www.example.com/cover.jpg")
        val conn = createConnection(404, null)
        Mockito.`when`(mDownloader!!.download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null))
                .thenReturn(conn)
        Assert.assertNull(mCoverLoader!!.loadCover(COVER_URL))
    }

    @Test
    @Throws(Exception::class)
    fun returnNullForIOException() {
        Mockito.`when`(mNetworkHelper!!.isNetworkAvailable).thenReturn(true)
        val COVER_URL = URL("https://www.example.com/cover.jpg")
        Mockito.`when`(mDownloader!!.download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null))
                .thenThrow(IOException())
        Assert.assertNull(mCoverLoader!!.loadCover(COVER_URL))
    }

    @Test
    @Throws(Exception::class)
    fun cacheDecodedBitmap() {
        Mockito.`when`(mNetworkHelper!!.isNetworkAvailable).thenReturn(true)
        val COVER_URL = URL("https://www.example.com/cover.jpg")
        val DATA = "foo"
        val BITMAP = createAndRegisterBitmap(DATA)
        val conn = createConnection(200, DATA)
        Mockito.`when`(mDownloader!!.download(COVER_URL, "GET", Downloader.AuthType.STORAGE, null))
                .thenReturn(conn)
        Assert.assertEquals(BITMAP, mCoverLoader!!.loadCover(COVER_URL))
        Assert.assertEquals(BITMAP, mCoverLoader!!.loadCover(COVER_URL))
        Assert.assertEquals(BITMAP, mCoverLoader!!.loadCover(COVER_URL))

        // The file should've only been decoded once.
        Mockito.verify(mBitmapDecoder!!, Mockito.times(1)).decodeFile(MockitoHelper.anyObject())
    }

    @Throws(Exception::class)
    private fun createConnection(statusCode: Int, data: String?): HttpURLConnection {
        val conn = Mockito.mock(HttpURLConnection::class.java)
        Mockito.`when`(conn.responseCode).thenReturn(statusCode)
        if (data != null) {
            Mockito.`when`(conn.inputStream).thenReturn(StringBufferInputStream(data))
        }
        return conn
    }

    private fun createAndRegisterBitmap(data: String): Bitmap {
        val bitmap = Mockito.mock(Bitmap::class.java)
        mBitmapDataMap!![data] = bitmap
        return bitmap
    }
}
