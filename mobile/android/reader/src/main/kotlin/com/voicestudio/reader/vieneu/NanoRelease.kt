package com.voicestudio.reader.vieneu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/** One file of the pinned release: where it comes from and what it must hash to. */
@Serializable data class ReleaseFile(val name: String, val url: String, val size: Long, val sha256: String, val group: String = SPEECH) {
    companion object {
        /** Needed to read aloud. */
        const val SPEECH = "speech"
        /** Needed only to enrol a new voice from a recording. */
        const val CLONE = "clone"
    }
}

/**
 * Everything the app downloads, pinned: the VieNeu-TTS v3 Nano graphs at one Hugging Face revision, the
 * sea-g2p dictionary of the matching release, and the upstream preset voices. A file whose hash differs
 * is discarded, so a changed upstream cannot silently change what the app runs.
 */
@Serializable data class NanoRelease(
    val id: String,
    val model: String,
    val licence: String,
    val credit: String,
    @SerialName("source_url") val sourceUrl: String,
    val files: List<ReleaseFile>,
) {
    fun files(group: String) = files.filter { it.group == group }
    fun bytes(group: String) = files(group).sumOf { it.size }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val HOSTS = setOf("huggingface.co", "github.com", "raw.githubusercontent.com")

        fun decode(text: String): NanoRelease = json.decodeFromString<NanoRelease>(text).also { release ->
            require(Regex("[a-z0-9][a-z0-9.-]{0,63}").matches(release.id))
            require(release.files.isNotEmpty() && release.files.map { it.name }.distinct().size == release.files.size)
            for (file in release.files) {
                require(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,80}").matches(file.name) && !file.name.endsWith(".part"))
                require(file.size in 1..2_000_000_000L && Regex("[0-9a-f]{64}").matches(file.sha256))
                require(file.group == ReleaseFile.SPEECH || file.group == ReleaseFile.CLONE)
                val uri = URI(file.url)
                require(uri.scheme == "https" && uri.host in HOSTS && uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null)
                // Mutable references (branch names, "latest") are not pins.
                val parts = uri.path.trim('/').split('/')
                require(when (uri.host) {
                    "huggingface.co" -> parts.size >= 5 && parts[2] == "resolve" && Regex("[0-9a-f]{40}").matches(parts[3])
                    "raw.githubusercontent.com" -> parts.size >= 4 && Regex("[0-9a-f]{40}").matches(parts[2])
                    else -> parts.size == 6 && parts[2] == "releases" && parts[3] == "download" && Regex("v[0-9][0-9.]*").matches(parts[4])
                })
            }
        }
    }
}
