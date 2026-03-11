package com.example.ytdlweb

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.io.OutputStream
import java.util.stream.StreamSupport


@Service
class YtDlpService(private val objectMapper: ObjectMapper) {

    fun search(query: String): List<VideoInfo> {
        val process = ProcessBuilder(
            "yt-dlp",
            "--dump-json",
            "--flat-playlist",
            "ytsearch10:$query"
        ).start()

        val videos = mutableListOf<VideoInfo>()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val node = objectMapper.readTree(line)
                videos.add(parseVideoInfo(node))
            }
        }
        process.waitFor()
        return videos.sortedByDescending { it.epoch }
    }

    fun getVideoDetails(url: String): VideoDetails {
        val process = ProcessBuilder(
            "yt-dlp",
            "--dump-json",
            url
        ).start()

        val node = objectMapper.readTree(process.inputStream)
        process.waitFor()

        val formats = node.get("formats").map { format ->
            FormatInfo(
                id = format.get("format_id").asText(),
                ext = format.get("ext").asText(),
                resolution = format.get("resolution")?.asText() ?: "N/A",
                note = format.get("format_note")?.asText() ?: "",
                vcodec = format.get("vcodec")?.asText() ?: "none",
                acodec = format.get("acodec")?.asText() ?: "none"
            )
        }

        return VideoDetails(
            info = parseVideoInfo(node),
            formats = formats
        )
    }

    fun streamDownload(url: String, formatId: String, outputStream: OutputStream) {
        val process = ProcessBuilder(
            "yt-dlp",
            "-f", formatId,
            "-o", "-",
            url
        ).start()

        process.inputStream.use { input ->
            input.copyTo(outputStream)
        }
        process.waitFor()
    }

    private fun parseVideoInfo(node: JsonNode): VideoInfo {
        return VideoInfo(
            id = node.get("id").asText(),
            title = node.get("title").asText(),
            url = node.get("webpage_url")?.asText() ?: "https://www.youtube.com/watch?v=${node.get("id").asText()}",
            thumbnail = StreamSupport.stream(node.get("thumbnails").spliterator(), false)
                .min { n1, n2 -> n1.get("width")?.asInt(0)?.compareTo(n2.get("width")?.asInt(0) ?: 0) ?: 0 }
                .orElse(null)?.get("url")
                ?.asText() ?: "",
            duration = node.get("duration")?.asInt() ?: 0,
            uploader = node.get("uploader")?.asText() ?: "Unknown",
            epoch = node.get("epoch").asLong()
        )
    }
}

data class VideoInfo(
    val id: String,
    val title: String,
    val url: String,
    val thumbnail: String,
    val duration: Int,
    val uploader: String,
    val epoch: Long
)

data class VideoDetails(
    val info: VideoInfo,
    val formats: List<FormatInfo>
)

data class FormatInfo(
    val id: String,
    val ext: String,
    val resolution: String,
    val note: String,
    val vcodec: String,
    val acodec: String
)
