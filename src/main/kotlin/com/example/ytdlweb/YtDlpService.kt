package com.example.ytdlweb

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

@Service
class YtDlpService(private val objectMapper: ObjectMapper, env: Environment) {

    private val logger = LoggerFactory.getLogger(YtDlpService::class.java)

    private val cookies = env.getProperty("COOKIES")

    private val videoDetails = ConcurrentHashMap<String, VideoInfo>()
    private val downloadProgress = ConcurrentHashMap<String, CacheInfo>()
    private val activeProcesses = ConcurrentHashMap<String, Process>()

    private val cacheDir = File("cache").apply { mkdirs() }

    fun search(query: String): List<VideoInfo> {
        val args = mutableListOf("yt-dlp")
        if (cookies != null) {
            args += listOf(
                "--cookies", cookies
            )
        }
        args += listOf(
            "--dump-json",
            "ytsearch9:$query"
        )

        val process = ProcessBuilder(args).redirectErrorStream(true).start()

        val videos = mutableListOf<VideoInfo>()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                try {
                    val node = objectMapper.readTree(line)
                    val videoInfo = parseVideoInfo(node)
                    if (videoInfo != null) {
                        videoDetails[videoInfo.url] = videoInfo
                        videos.add(videoInfo)
                    } else {
                        logger.warn("{}: {}", query, line)
                    }
                } catch (_: Exception) {
                    logger.error("{}: {}", query, line)
                }
            }
        }
        process.waitFor()
        return videos
    }

    fun getVideoDetails(url: String): VideoDetails? {
        var videoInfo = videoDetails[url]

        if (videoInfo == null) {
            val args = mutableListOf("yt-dlp")
            if (cookies != null) {
                args += listOf(
                    "--cookies", cookies
                )
            }
            args += listOf(
                "--dump-json",
                url
            )

            try {
                val process = ProcessBuilder(args).redirectErrorStream(true).start()
                val node = objectMapper.readTree(process.inputStream)
                process.waitFor()

                videoInfo = parseVideoInfo(node)
                if (videoInfo != null) {
                    videoDetails[url] = videoInfo
                } else {
                    logger.warn("Couldn't get info: {}", url)
                }
            } catch (_: Exception) {
                logger.error("Failed get info: {}", url)
            }
        }

        if (videoInfo != null) {
            return VideoDetails(info = videoInfo, cacheInfo = getCacheStatus(videoInfo.id))
        }
        return null
    }

    fun getCacheStatus(videoId: String): CacheInfo {
        if (File(cacheDir, "$videoId.mp4").exists()) {
            return CacheInfo(CacheStatus.CACHED, 100.0)
        }
        return downloadProgress[videoId] ?: CacheInfo(CacheStatus.NONE, 0.0)
    }

    fun startCaching(url: String, videoId: String, formatId: String? = null) {
        if (!listOf(CacheStatus.NONE, CacheStatus.FAILED).contains(getCacheStatus(videoId).status)) {
            logger.info("Skip caching {}", videoId)
            return
        }

        logger.info("Start caching {}", videoId)
        downloadProgress[videoId] = CacheInfo(CacheStatus.DOWNLOADING, 0.0)

        thread {
            try {
                val outputFile = File(cacheDir, "$videoId.mp4")
                val args = mutableListOf("yt-dlp")

                if (formatId == null || formatId == "undefined") {
                    val defaultFormat =
                        "bestvideo[height>=360][height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height>=360][height<=720][ext=mp4]"
                    args += listOf(
                        "-f", defaultFormat,
                        "-S", "height",
                        "--merge-output-format", "mp4"
                    )
                } else {
                    args += listOf(
                        "-f", formatId
                    )
                }

                if (cookies != null) {
                    args += listOf(
                        "--cookies", cookies
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
                            downloadProgress[videoId]?.progress = progress
                        }
                        if (line.contains("WARNING") || line.contains("ERROR")) {
                            logger.warn("{}: {}", videoId, line)
                        }
                    }
                }

                val exitCode = process.waitFor()
                activeProcesses.remove(videoId)
                if (exitCode == 0 && outputFile.exists()) {
                    logger.info("Finished {}", videoId)
                    downloadProgress.remove(videoId) // Fully cached, rely on file existence
                } else {
                    logger.warn("Exited {} with code {}", videoId, exitCode)
                    downloadProgress[videoId] = CacheInfo(CacheStatus.FAILED, 0.0)
                }
            } catch (_: Exception) {
                activeProcesses.remove(videoId)
                downloadProgress[videoId] = CacheInfo(CacheStatus.FAILED, 0.0)
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

    private fun parseVideoInfo(node: JsonNode): VideoInfo? {
        val id = node.get("id") ?: return null

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

        return VideoInfo(
            id = id.asText(),
            title = node.get("title").asText(),
            url = node.get("webpage_url")?.asText() ?: "https://www.youtube.com/watch?v=${id.asText()}",
            thumbnail = "/proxy?url=${
                URLEncoder.encode(
                    "https://i.ytimg.com/vi/${id.asText()}/mqdefault.jpg",
                    "UTF-8"
                )
            }",
            duration = node.get("duration_string")?.asText() ?: "-",
            uploader = node.get("uploader")?.asText() ?: "Unknown",
            timestamp = node.get("timestamp")?.asLong(),
            formatId = format?.key
        )
    }
}

data class VideoInfo(
    val id: String,
    val title: String,
    val url: String,
    val thumbnail: String,
    val duration: String,
    val uploader: String,
    val timestamp: Long?,
    val formatId: String?
)

enum class CacheStatus {
    NONE, DOWNLOADING, CACHED, FAILED
}

data class CacheInfo(
    val status: CacheStatus,
    var progress: Double
)

data class VideoDetails(
    val info: VideoInfo,
    val cacheInfo: CacheInfo,
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
