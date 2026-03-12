package com.example.ytdlweb

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

    @PostMapping("/cache/start")
    @ResponseBody
    fun startCache(@RequestParam url: String, @RequestParam videoId: String, @RequestParam formatId: String) {
        ytDlpService.startCaching(url, videoId, formatId)
    }

    @PostMapping("/cache/cancel")
    @ResponseBody
    fun cancelCache(@RequestParam videoId: String) {
        ytDlpService.cancelCaching(videoId)
    }

    @GetMapping("/cache/status")
    @ResponseBody
    fun cacheStatus(@RequestParam videoId: String): CacheInfo {
        return ytDlpService.getCacheStatus(videoId)
    }

    @GetMapping("/download")
    fun download(@RequestParam videoId: String, @RequestParam filename: String, response: HttpServletResponse) {
        val file = java.io.File("cache", "$videoId.mp4")
        if (file.exists()) {
            response.contentType = "video/mp4"
            val disposition = ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build()
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            file.inputStream().use { input -> input.copyTo(response.outputStream) }
        } else {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
        }
    }

    @GetMapping("/stream")
    @ResponseBody
    fun stream(@RequestParam videoId: String, response: HttpServletResponse): FileSystemResource {
        val file = java.io.File("cache", "$videoId.mp4")
        if (!file.exists()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        response.contentType = "video/mp4"
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
