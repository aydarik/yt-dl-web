package ru.gumerbaev.ytdlweb

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import org.springframework.core.io.FileSystemResource
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets

@Controller
class MainController(private val ytDlpService: YtDlpService) {

    @GetMapping("/", "/watch")
    fun index(): String {
        return "index"
    }

    @GetMapping("/search")
    @ResponseBody
    fun search(@RequestParam query: String): List<VideoInfo> {
        return ytDlpService.search(query)
    }

    @GetMapping("/details")
    @ResponseBody
    fun details(@RequestParam url: String): VideoDetails? {
        return ytDlpService.getVideoDetails(url)
    }

    @GetMapping("/formats")
    @ResponseBody
    fun formats(): List<DownloadFormat> {
        return ytDlpService.getAvailableFormats()
    }

    private fun isValidVideoId(videoId: String): Boolean {
        return videoId.matches(Regex("""^[a-zA-Z0-9_-]{1,64}$"""))
    }

    @PostMapping("/cache/start")
    @ResponseBody
    fun startCache(
        @RequestParam url: String,
        @RequestParam videoId: String,
        @RequestParam(required = false) formatSpec: String?,
        @RequestParam(required = false) sortSpec: String?,
        @RequestParam(defaultValue = "false") audioOnly: Boolean
    ) {
        if (!isValidVideoId(videoId)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        ytDlpService.startCaching(url, videoId, formatSpec, sortSpec, audioOnly)
    }

    @PostMapping("/cache/cancel")
    @ResponseBody
    fun cancelCache(@RequestParam videoId: String) {
        if (!isValidVideoId(videoId)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        ytDlpService.cancelCaching(videoId)
    }

    private fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (isValidVideoId(trimmed)) return trimmed
        val regex = Regex("""(?:v=|\/shorts\/|\/embed\/|youtu\.be\/)([a-zA-Z0-9_-]{1,64})""")
        return regex.find(trimmed)?.groupValues?.get(1)?.takeIf { isValidVideoId(it) }
    }

    @GetMapping("/cache/status")
    @ResponseBody
    fun cacheStatus(
        @RequestParam(required = false) videoId: String?,
        @RequestParam(required = false) url: String?
    ): CacheInfo {
        val id = videoId?.takeIf { isValidVideoId(it) } ?: url?.let { extractVideoId(it) }
        if (id == null || !isValidVideoId(id)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        return ytDlpService.getCacheStatus(id)
    }

    @GetMapping("/download")
    fun download(@RequestParam videoId: String, @RequestParam filename: String, response: HttpServletResponse) {
        if (!isValidVideoId(videoId)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST)
            return
        }
        // Check both video and audio cached files
        val mp4 = File("cache", "$videoId.mp4")
        val mp3 = File("cache", "$videoId.mp3")
        val (file, contentType) = when {
            filename.endsWith(".mp3", ignoreCase = true) && mp3.exists() -> mp3 to "audio/mpeg"
            filename.endsWith(".mp4", ignoreCase = true) && mp4.exists() -> mp4 to "video/mp4"
            mp4.exists() -> mp4 to "video/mp4"
            mp3.exists() -> mp3 to "audio/mpeg"
            else -> {
                response.sendError(HttpServletResponse.SC_NOT_FOUND)
                return
            }
        }
        response.contentType = contentType
        val disposition = ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build()
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        file.inputStream().use { input -> input.copyTo(response.outputStream) }
    }

    @GetMapping("/stream")
    @ResponseBody
    fun stream(@RequestParam videoId: String, response: HttpServletResponse): FileSystemResource {
        if (!isValidVideoId(videoId)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        val mp4 = File("cache", "$videoId.mp4")
        val mp3 = File("cache", "$videoId.mp3")
        val (file, contentType) = when {
            mp4.exists() -> mp4 to "video/mp4"
            mp3.exists() -> mp3 to "audio/mpeg"
            else -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        response.contentType = contentType
        return FileSystemResource(file)
    }

    @GetMapping("/proxy")
    fun proxy(@RequestParam url: String, response: HttpServletResponse) {
        try {
            val connection = URI(url).toURL().openConnection()
            connection.connect()
            response.contentType = connection.contentType ?: "image/jpeg"
            connection.getInputStream().use { input -> input.copyTo(response.outputStream) }
        } catch (_: Exception) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
        }
    }
}
