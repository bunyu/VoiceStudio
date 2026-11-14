package com.voicestudio.reader.voice

import com.voicestudio.reader.text.readBounded
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.zip.ZipFile

class VoiceBundleException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_A_BUNDLE, NO_AUDIO, TOO_LARGE }
}

/** What a VoiceStudio voice export holds, with the clip that matches [transcript] already extracted. */
data class BundleVoice(val name: String, val transcript: String, val language: String, val designed: Boolean, val audio: File, val preview: File?)

/**
 * Reads voices exported from the VoiceStudio desktop app: `.ovsvoice` persona bundles (Edit voice → Export
 * persona) and the older `.omnivoice` bundles. Both are ZIP files; only fixed member names are read and
 * nothing is extracted by its archive path.
 */
object VoiceBundle {
    private const val MAX_ENTRIES = 64
    private const val MAX_AUDIO = 32L * 1024 * 1024
    private const val MAX_JSON = 256L * 1024
    private val AUDIO = Regex("(ref_audio|locked_audio|preview)\\.([A-Za-z0-9]{1,5})")
    private val json = Json { ignoreUnknownKeys = true }

    fun read(source: File, workspace: File): BundleVoice {
        val zip = try { ZipFile(source) } catch (e: Exception) { throw VoiceBundleException(VoiceBundleException.Reason.NOT_A_BUNDLE) }
        zip.use {
            val entries = zip.entries().toList()
            if (entries.size > MAX_ENTRIES) throw VoiceBundleException(VoiceBundleException.Reason.NOT_A_BUNDLE)
            fun bytes(name: String, limit: Long): ByteArray? {
                val entry = entries.firstOrNull { !it.isDirectory && it.name == name } ?: return null
                if (entry.size > limit) throw VoiceBundleException(VoiceBundleException.Reason.TOO_LARGE)
                return zip.getInputStream(entry).use { it.readBounded(limit) } ?: throw VoiceBundleException(VoiceBundleException.Reason.TOO_LARGE)
            }
            fun obj(name: String): JsonObject? = bytes(name, MAX_JSON)?.let {
                runCatching { json.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
            }
            fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true

            val manifest = obj("manifest.json")?.takeIf { it.text("format") == "ovsvoice" }
            val persona = manifest?.get("persona") as? JsonObject
            val legacy = obj("metadata.json")
            val fields = persona ?: legacy ?: throw VoiceBundleException(VoiceBundleException.Reason.NOT_A_BUNDLE)
            val name = (persona?.text("name") ?: legacy?.text("profile_name")).orEmpty().ifEmpty { source.nameWithoutExtension }.take(200)
            // A locked voice's transcript describes its locked take, so that take is the matching reference.
            val members = entries.filter { !it.isDirectory && AUDIO.matches(it.name) }.associateBy { AUDIO.matchEntire(it.name)!!.groupValues[1] }
            val chosen = (if (fields.flag("is_locked")) members["locked_audio"] else null) ?: members["ref_audio"] ?: members["locked_audio"]
                ?: throw VoiceBundleException(VoiceBundleException.Reason.NO_AUDIO)
            workspace.mkdirs()
            fun extract(entryName: String, target: String): File {
                val data = bytes(entryName, MAX_AUDIO) ?: throw VoiceBundleException(VoiceBundleException.Reason.NO_AUDIO)
                val extension = AUDIO.matchEntire(entryName)!!.groupValues[2].lowercase()
                return File(workspace, "$target.$extension").apply { writeBytes(data) }
            }
            val audio = extract(chosen.name, "reference")
            val preview = members["preview"]?.let { extract(it.name, "preview") }
            return BundleVoice(name, fields.text("ref_text").take(4000), fields.text("language").ifEmpty { "Auto" },
                fields.text("kind") == "design", audio, preview)
        }
    }
}
