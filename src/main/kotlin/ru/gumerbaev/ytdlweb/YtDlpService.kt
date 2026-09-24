package ru.gumerbaev.ytdlweb

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
        logger.info("Search '{}'", query)

        val args = mutableListOf("yt-dlp")
        if (cookies != null) {
            args += listOf(
                "--cookies", cookies
            )
        }
        args += listOf(
            "--dump-json",
            "--flat-playlist",
            "--no-playlist",
            "--no-warnings",
            "--retries", "1",
            "--default-search", "ytsearch9",
            "--",
            query
        )

        val process = ProcessBuilder(args).redirectErrorStream(false).start()

        val videos = mutableListOf<VideoInfo>()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                try {
                    if (line.isNotBlank()) {
                        val node = objectMapper.readTree(line)
                        val videoInfo = parseVideoInfo(node, false)
                        if (videoInfo != null) {
                            videos.add(videoInfo)
                            videoDetails[videoInfo.url] = videoInfo
                            videoDetails[videoInfo.id] = videoInfo
                            videoDetails["https://www.youtube.com/watch?v=${videoInfo.id}"] = videoInfo
                        } else {
                            logger.warn("Unparseable video info for query '{}': {}", query, line)
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to parse search line for query '{}': {}", query, line, e)
                }
            }
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val stderr = process.errorStream.bufferedReader().readText()
            logger.warn("Search exited with code {} for '{}': {}", exitCode, query, stderr.trim())
        }
        return videos
    }

    fun getVideoDetails(url: String): VideoDetails? {
        var videoInfo = videoDetails[url]

        if (videoInfo == null) {
            val idMatch = Regex("""(?:v=|\/shorts\/|\/embed\/|youtu\.be\/|^)([a-zA-Z0-9_-]{11})""").find(url)?.groupValues?.get(1)
            if (idMatch != null) {
                videoInfo = videoDetails[idMatch]
            }
        }

        if (videoInfo == null) {
            logger.info("Details '{}'", url)

            val args = mutableListOf("yt-dlp")
            if (cookies != null) {
                args += listOf(
                    "--cookies", cookies
                )
            }
            args += listOf(
                "--dump-json",
                "--no-playlist",
                "--no-warnings",
                "--retries", "1",
                "--",
                url
            )

            try {
                val process = ProcessBuilder(args).redirectErrorStream(false).start()
                val stdout = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode == 0 && stdout.isNotBlank()) {
                    val node = objectMapper.readTree(stdout)
                    videoInfo = parseVideoInfo(node, true)
                    if (videoInfo != null) {
                        videoDetails[url] = videoInfo
                        videoDetails[videoInfo.id] = videoInfo
                        logger.info("Loaded details: {}", videoInfo.title)
                    } else {
                        logger.warn("Couldn't parse info: {}", url)
                    }
                } else {
                    val stderr = process.errorStream.bufferedReader().readText()
                    logger.warn("Failed to get info for '{}' (exit code {}): {}", url, exitCode, stderr.trim())
                }
            } catch (e: Exception) {
                logger.error("Failed get info: {}", url, e)
            }
        }

        if (videoInfo != null) {
            return VideoDetails(info = videoInfo, cacheInfo = getCacheStatus(videoInfo.id))
        }
        return null
    }

    fun getCacheStatus(videoId: String): CacheInfo {
        // Return active progress first so merging/postprocessing files are not reported as CACHED prematurely
        downloadProgress[videoId]?.let { return it }
        val mp4 = File(cacheDir, "$videoId.mp4")
        val mp3 = File(cacheDir, "$videoId.mp3")
        val hasMp4 = mp4.exists()
        val hasMp3 = mp3.exists()
        if (hasMp4 || hasMp3) {
            val ext = if (hasMp4) "mp4" else "mp3"
            return CacheInfo(CacheStatus.CACHED, 100.0, ext = ext, hasMp4 = hasMp4, hasMp3 = hasMp3)
        }
        return CacheInfo(CacheStatus.NONE, 0.0)
    }

    fun startCaching(url: String, videoId: String, formatSpec: String? = null, sortSpec: String? = null, audioOnly: Boolean = false) {
        if (!listOf(CacheStatus.NONE, CacheStatus.FAILED).contains(getCacheStatus(videoId).status)) {
            logger.info("Skip caching {}", videoId)
            return
        }

        val ext = if (audioOnly) "mp3" else "mp4"
        logger.info("Downloading '{}' (audioOnly={})", videoId, audioOnly)
        downloadProgress[videoId] = CacheInfo(
            status = CacheStatus.DOWNLOADING,
            progress = 0.0,
            ext = ext,
            hasMp4 = !audioOnly,
            hasMp3 = audioOnly
        )

        thread {
            val ext = if (audioOnly) "mp3" else "mp4"
            val outputFile = File(cacheDir, "$videoId.$ext")
            try {
                val args = mutableListOf("yt-dlp")

                val effectiveSpec = formatSpec?.takeIf { it.isNotBlank() }
                val effectiveSort = sortSpec?.takeIf { it.isNotBlank() }

                if (audioOnly) {
                    // Audio-only: extract and re-encode to the chosen container
                    val aFormat = effectiveSpec ?: "ba[acodec^=mp3]/ba/b"
                    args += listOf("-f", aFormat, "-x", "--audio-format", "mp3")
                } else {
                    val vFormat = effectiveSpec ?: "bv*[height<=720]+ba/b[height<=720]/b"
                    args += listOf("-f", vFormat, "--merge-output-format", "mp4")
                    if (effectiveSort != null) {
                        args += listOf("-S", effectiveSort)
                    } else {
                        args += listOf("-S", "res:720,ext:mp4:m4a")
                    }
                }

                if (cookies != null) {
                    args += listOf("--cookies", cookies)
                }

                args += listOf(
                    "--concurrent-fragments", "4",
                    "--sponsorblock-remove", "sponsor,selfpromo",
                    "--no-playlist",
                    "--retries", "3",
                    "--progress-delta", "1",
                    "--max-filesize", "1000M",
                    "--newline",
                    "-o", outputFile.absolutePath,
                    "--",
                    url
                )

                val process = ProcessBuilder(args).redirectErrorStream(true).start()
                activeProcesses[videoId] = process

                val regex = Regex("""\[download\]\s+(\d+(?:\.\d+)?)%""")

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
                    if (exitCode == 143 || !downloadProgress.containsKey(videoId)) {
                        logger.info("Terminated {}", videoId)
                        downloadProgress.remove(videoId)
                    } else {
                        logger.warn("Exited {} with code {}", videoId, exitCode)
                        downloadProgress[videoId] = CacheInfo(CacheStatus.FAILED, 0.0)
                    }
                    if (exitCode != 0 && outputFile.exists()) {
                        outputFile.delete()
                    }
                }
            } catch (e: Exception) {
                logger.error("Error during download for {}: {}", videoId, e.message)
                activeProcesses.remove(videoId)
                downloadProgress[videoId] = CacheInfo(CacheStatus.FAILED, 0.0)
            }
        }
    }

    fun cancelCaching(videoId: String) {
        downloadProgress.remove(videoId)
        activeProcesses.remove(videoId)?.let { process ->
            try {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            } catch (e: Exception) {
                logger.warn("Error terminating process tree for {}: {}", videoId, e.message)
            }
        }
        // Clean up partial downloads
        cacheDir.listFiles { _, name ->
            name.startsWith(videoId) &&
            (name.endsWith(".part") || name.endsWith(".temp") || name.endsWith(".mp4") || name.endsWith(".mp3"))
        }?.forEach { it.delete() }
    }

    fun getAvailableFormats(): List<DownloadFormat> = defaultFormats()

    private fun formatsFor(): List<DownloadFormat> {
        val formats = mutableListOf<DownloadFormat>()
        listOf(1080, 720, 480, 360).forEach { h ->
            formats += DownloadFormat(
                id = "video_${h}p",
                label = "${h}p HD Video",
                quality = "${h}p",
                type = "video",
                formatSpec = "bv*[height<=${h}]+ba/b[height<=${h}]/b",
                sortSpec = "res:${h},ext:mp4:m4a"
            )
        }
        formats += DownloadFormat(
            id = "audio_mp3",
            label = "Audio Only (MP3)",
            quality = "128k",
            type = "audio",
            formatSpec = "ba[acodec^=mp3]/ba/b",
            sortSpec = ""
        )
        formats += DownloadFormat(
            id = "audio_m4a",
            label = "Audio Only (M4A)",
            quality = "best",
            type = "audio",
            formatSpec = "ba[ext=m4a]/ba/b",
            sortSpec = ""
        )
        return formats
    }

    private fun defaultFormats() = formatsFor()

    private fun parseVideoInfo(node: JsonNode, withFormat: Boolean): VideoInfo? {
        val id = node.get("id") ?: return null

        val format = if (withFormat) {
            node.get("formats")?.mapNotNull { format ->
                try {
                    FormatInfo(
                        id = format.get("format_id").asText(),
                        ext = format.get("ext").asText(),
                        resolution = format.get("resolution")?.asText(),
                        note = format.get("format_note")?.asText(),
                        vcodec = format.get("vcodec")?.asText() ?: "none",
                        acodec = format.get("acodec")?.asText() ?: "none",
                        url = format.get("url")?.asText() ?: ""
                    )
                } catch (_: Exception) {
                    null
                }
            }?.filter { it.ext == "mp4" && it.vcodec != "none" && it.acodec != "none" }
                ?.filter { it.resolution?.matches(Regex("""^\d{2,5}x\d{2,5}$""")) ?: false }
                ?.associate { it.id to it.resolution!!.split("x").last().toInt() }
                ?.filter { it.value in 360..720 }
                ?.maxByOrNull { it.value }
        } else {
            null
        }

        val rawViews = node.get("view_count")?.asLong()
        val viewCount = when {
            rawViews == null -> null
            rawViews >= 1_000_000 -> "${rawViews / 1_000_000}M views"
            rawViews >= 1_000 -> "${rawViews / 1_000}K views"
            else -> "$rawViews views"
        }

        return VideoInfo(
            id = id.asText(),
            title = node.get("title")?.asText() ?: "Unknown",
            url = node.get("webpage_url")?.asText() ?: "https://www.youtube.com/watch?v=${id.asText()}",
            thumbnail = "/proxy?url=${
                URLEncoder.encode(
                    "https://i.ytimg.com/vi/${id.asText()}/mqdefault.jpg",
                    "UTF-8"
                )
            }",
            duration = node.get("duration_string")?.asText() ?: "-",
            uploader = node.get("uploader")?.asText() ?: "Unknown",
            viewCount = viewCount,
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
    val viewCount: String? = null,
    val formatId: String?
)

data class DownloadFormat(
    val id: String,
    val label: String,
    val quality: String,
    val type: String,       // "video" or "audio"
    val formatSpec: String,
    val sortSpec: String
)

enum class CacheStatus {
    NONE, DOWNLOADING, CACHED, FAILED
}

data class CacheInfo(
    val status: CacheStatus,
    var progress: Double,
    val ext: String? = null,
    val hasMp4: Boolean = false,
    val hasMp3: Boolean = false
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
