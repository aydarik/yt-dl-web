package ru.gumerbaev.ytdlweb

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.io.File

class YtDlpServiceTest {

    private lateinit var service: YtDlpService
    private lateinit var controller: MainController

    private val testVideoId = "test_vid_123"
    private val cacheDir = File("cache")

    @BeforeEach
    fun setup() {
        cacheDir.mkdirs()
        service = YtDlpService(ObjectMapper(), MockEnvironment())
        controller = MainController(service)
    }

    @AfterEach
    fun cleanup() {
        File(cacheDir, "$testVideoId.mp4").delete()
        File(cacheDir, "$testVideoId.mp3").delete()
    }

    @Test
    fun `cacheStatus returns NONE when file not cached`() {
        val status = controller.cacheStatus(testVideoId, null)
        assertEquals(CacheStatus.NONE, status.status)
        assertEquals(0.0, status.progress)
        assertFalse(status.hasMp4)
        assertFalse(status.hasMp3)
    }

    @Test
    fun `cacheStatus returns CACHED when mp4 file exists`() {
        val mp4File = File(cacheDir, "$testVideoId.mp4")
        mp4File.writeText("fake mp4 content")

        val status = controller.cacheStatus(testVideoId, null)
        assertEquals(CacheStatus.CACHED, status.status)
        assertEquals(100.0, status.progress)
        assertEquals("mp4", status.ext)
        assertTrue(status.hasMp4)
        assertFalse(status.hasMp3)
    }

    @Test
    fun `cacheStatus returns CACHED when mp3 file exists`() {
        val mp3File = File(cacheDir, "$testVideoId.mp3")
        mp3File.writeText("fake mp3 content")

        val status = controller.cacheStatus(testVideoId, null)
        assertEquals(CacheStatus.CACHED, status.status)
        assertEquals(100.0, status.progress)
        assertEquals("mp3", status.ext)
        assertFalse(status.hasMp4)
        assertTrue(status.hasMp3)
    }

    @Test
    fun `cacheStatus works with url parameter`() {
        val mp4File = File(cacheDir, "$testVideoId.mp4")
        mp4File.writeText("fake mp4 content")

        val testUrl = "https://www.youtube.com/watch?v=$testVideoId"
        val status = controller.cacheStatus(null, testUrl)
        assertEquals(CacheStatus.CACHED, status.status)
        assertEquals("mp4", status.ext)
    }

    @Test
    fun `cacheStatus returns DOWNLOADING when download in progress`() {
        val field = YtDlpService::class.java.getDeclaredField("downloadProgress")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val progressMap = field.get(service) as MutableMap<String, CacheInfo>
        progressMap[testVideoId] = CacheInfo(CacheStatus.DOWNLOADING, 42.5, "mp4", hasMp4 = true, hasMp3 = false)

        val status = controller.cacheStatus(testVideoId, null)
        assertEquals(CacheStatus.DOWNLOADING, status.status)
        assertEquals(42.5, status.progress)
        assertEquals("mp4", status.ext)
    }
}
