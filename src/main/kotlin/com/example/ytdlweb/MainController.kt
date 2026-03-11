package com.example.ytdlweb

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
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
        return ytDlpService.getVideoDetails(url)
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
