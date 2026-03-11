package com.example.ytdlweb

import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import java.net.URI
import java.nio.charset.StandardCharsets

@Controller
class MainController(private val ytDlpService: YtDlpService) {

    @GetMapping("/")
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
    fun details(@RequestParam url: String): VideoDetails {
        val details = ytDlpService.getVideoDetails(url)
        // Pre-cache the best format (first mp4 with video+audio or just first format)
        val previewFormat = details.formats.find { it.ext == "mp4" && it.vcodec != "none" && it.acodec != "none" }
            ?: details.formats.find { it.vcodec != "none" && it.acodec != "none" }
            ?: details.formats.firstOrNull()

        previewFormat?.let {
            ytDlpService.preview(url, it.id)
        }
        return details
    }

    @GetMapping("/download")
    fun download(
        @RequestParam url: String,
        @RequestParam formatId: String,
        @RequestParam filename: String,
        response: HttpServletResponse
    ) {
        response.contentType = "application/octet-stream"
        val disposition = ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build()
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        ytDlpService.streamDownload(url, formatId, response.outputStream)
    }

    @GetMapping("/stream")
    fun stream(
        @RequestParam url: String
    ): ResponseEntity<Resource> {
        val file = ytDlpService.preview(url)
        val resource = FileSystemResource(file)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("video/mp4"))
            .body(resource)
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
