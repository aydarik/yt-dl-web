package com.example.ytdlweb

import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.*

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
        response.setHeader("Content-Disposition", "attachment; filename=\"$filename\"")
        ytDlpService.streamDownload(url, formatId, response.outputStream)
    }
}
