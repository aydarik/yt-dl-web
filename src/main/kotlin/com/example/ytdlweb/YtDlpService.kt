package com.example.ytdlweb

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.StreamSupport
import kotlin.concurrent.thread

@Service
class YtDlpService(private val objectMapper: ObjectMapper) {

    private val videoDetails = ConcurrentHashMap<String, VideoDetails>()
    private val downloadProgress = ConcurrentHashMap<String, CacheInfo>()
    private val activeProcesses = ConcurrentHashMap<String, Process>()
    private val cacheDir = File("cache").apply { mkdirs() }

    fun search(query: String): List<VideoInfo> {
        val process = ProcessBuilder("yt-dlp", "--dump-json", "--flat-playlist", "ytsearch10:$query").start()

        val videos = mutableListOf<VideoInfo>()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val node = objectMapper.readTree(line)
                videos.add(parseVideoInfo(node))
            }
        }
        process.waitFor()
        return videos
    }

    fun getVideoDetails(url: String): VideoDetails {
        if (videoDetails.containsKey(url)) return videoDetails[url]!!

        val process = ProcessBuilder("yt-dlp", "--dump-json", url).start()

        val node = objectMapper.readTree(process.inputStream)
        process.waitFor()

        val format = node.get("formats")?.mapNotNull { format ->
            try {
                FormatInfo(
                    id = format.get("format_id").asText(),
                    ext = format.get("ext").asText(),
                    resolution = format.get("resolution")?.asText(),
                    note = format.get("format_note")?.asText(),
                    vcodec = format.get("vcodec")?.asText() ?: "none",
                    acodec = format.get("acodec")?.asText() ?: "none",
                    url = format.get("url").asText()
                )
            } catch (_: Exception) {
                null
            }
        }?.filter { it.ext == "mp4" && it.vcodec != "none" && it.acodec != "none" }
            ?.filter { it.resolution?.matches(Regex("""^\d{2,5}x\d{2,5}$""")) ?: false }
            ?.associate { it.id to it.resolution!!.split("x").last().toInt() }
            ?.filter { it.value in 360..720 }
            ?.minByOrNull { it.value }

        val videoInfo = parseVideoInfo(node)
        val details = VideoDetails(info = videoInfo, cacheInfo = getCacheStatus(videoInfo.id), formatId = format?.key)
        videoDetails[url] = details
        return details
    }

    fun getCacheStatus(videoId: String): CacheInfo {
        if (File(cacheDir, "$videoId.mp4").exists()) {
            return CacheInfo(CacheStatus.CACHED, 100.0)
        }
        return downloadProgress[videoId] ?: CacheInfo(CacheStatus.NONE, 0.0)
    }

    fun startCaching(url: String, videoId: String, formatId: String? = null) {
        if (getCacheStatus(videoId).status != CacheStatus.NONE) return

        downloadProgress[videoId] = CacheInfo(CacheStatus.DOWNLOADING, 0.0)

        thread {
            try {
                val outputFile = File(cacheDir, "$videoId.mp4")
                val args = mutableListOf(
                    "yt-dlp",
                    "-f",
                    formatId
                        ?: "bestvideo[height>=360][height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height>=360][height<=720][ext=mp4]"
                )
                if (formatId == null) {
                    args += listOf(
                        "-S", "height",
                        "--merge-output-format", "mp4"
                    )
                }
                args += listOf(
                    "--sponsorblock-remove", "sponsor,selfpromo",
                    "--newline",
                    "-o", outputFile.absolutePath,
                    url
                )

                val process = ProcessBuilder(args).redirectErrorStream(true).start()

                val regex = Regex("""\[download\]\s+(\d+\.\d+)%""")
                activeProcesses[videoId] = process

                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        regex.find(line)?.let { matchResult ->
                            val progress = matchResult.groupValues[1].toDoubleOrNull() ?: 0.0
                            downloadProgress[videoId] = CacheInfo(CacheStatus.DOWNLOADING, progress)
                        }
                    }
                }

                val exitCode = process.waitFor()
                activeProcesses.remove(videoId)
                if (exitCode == 0 && outputFile.exists()) {
                    downloadProgress.remove(videoId) // Fully cached, rely on file existence
                } else {
                    downloadProgress[videoId] = CacheInfo(CacheStatus.NONE, 0.0) // Failed
                    // Cleanup partial file
                    if (outputFile.exists() && exitCode != 0) {
                        try {
                            outputFile.delete()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
                activeProcesses.remove(videoId)
                downloadProgress[videoId] = CacheInfo(CacheStatus.NONE, 0.0) // Failed
            }
        }
    }

    fun cancelCaching(videoId: String) {
        activeProcesses[videoId]?.let { process ->
            process.destroy()
            activeProcesses.remove(videoId)
        }
        downloadProgress.remove(videoId)
    }

    private fun parseVideoInfo(node: JsonNode): VideoInfo {
        return VideoInfo(
            id = node.get("id").asText(),
            title = node.get("title").asText(),
            url = node.get("webpage_url")?.asText() ?: "https://www.youtube.com/watch?v=${node.get("id").asText()}",
            thumbnail = StreamSupport.stream(node.get("thumbnails").spliterator(), false)
                .min { n1, n2 -> n1.get("width")?.asInt(0)?.compareTo(n2.get("width")?.asInt(0) ?: 0) ?: 0 }
                .orElse(null)?.get("url")?.asText()
                ?.let { "/proxy?url=${URLEncoder.encode(it, "UTF-8")}" } ?: "",
            duration = node.get("duration_string")?.asText() ?: "-",
            uploader = node.get("uploader")?.asText() ?: "Unknown")
    }
}

data class VideoInfo(
    val id: String,
    val title: String,
    val url: String,
    val thumbnail: String,
    val duration: String,
    val uploader: String
)

enum class CacheStatus {
    NONE, DOWNLOADING, CACHED
}

data class CacheInfo(
    val status: CacheStatus,
    val progress: Double
)

data class VideoDetails(
    val info: VideoInfo,
    val cacheInfo: CacheInfo,
    val formatId: String?
)

data class FormatInfo(
    val id: String,
    val ext: String,
    val resolution: String?,
    val note: String?,
    val vcodec: String,
    val acodec: String,
    val url: String
)
