package com.voicestudio.reader.voice

import com.voicestudio.mobile.local.AndroidAudioDecoder
import com.voicestudio.mobile.local.AudioDecoder
import com.voicestudio.mobile.local.LocalAudio
import com.voicestudio.reader.vieneu.EnrolmentFailed
import com.voicestudio.reader.vieneu.NanoVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** A voice the user cloned from a recording. [enrolledAt] is part of the audio cache key. */
@Serializable data class ReaderVoice(val id: String, val name: String, val transcript: String = "", val language: String = "vi", val designed: Boolean = false,
    val importedAt: Long, val enrolledAt: Long = 0)

/**
 * Private storage for cloned voices. Each voice keeps what the model speaks with (a speaker vector and style
 * tokens, `print.f32`) and the original clip, so it can be auditioned and enrolled again by a later model.
 */
class ReaderVoices(private val root: File, private val decoder: AudioDecoder = AndroidAudioDecoder()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<ReaderVoice>>(emptyList())
    val voices: StateFlow<List<ReaderVoice>> = state.asStateFlow()

    private fun dir(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        return File(root, id)
    }

    /** Voices imported before the app used VieNeu have no print and are not listed; their folders are left alone. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            state.value = root.listFiles().orEmpty().mapNotNull { folder ->
                runCatching { json.decodeFromString<ReaderVoice>(File(folder, "voice.json").readText()) }.getOrNull()
                    ?.takeIf { it.id == folder.name && File(folder, PRINT).length() > SPEAKER * 4L }
            }.sortedByDescending { it.importedAt }
        }
    }

    /**
     * Enrols [clip] (any audio the phone can decode) as a new voice. Only the first 30 seconds are heard.
     * @throws EnrolmentFailed when the clip is silent or shorter than two seconds.
     */
    suspend fun add(clip: File, name: String, enroll: suspend (LocalAudio) -> NanoVoice): ReaderVoice = withContext(Dispatchers.IO) {
        require(name.isNotBlank())
        val id = UUID.randomUUID().toString()
        val folder = dir(id).apply { mkdirs() }
        val pcm = File(folder, "reference.pcm")
        try {
            val decoded = decoder.decode(clip, pcm, 30)
            if (decoded.frames < 2 * decoded.sampleRate) throw EnrolmentFailed("too_short")
            val print = enroll(decoded.read(0, decoded.frames.toInt()))
            require(print.speaker.size == SPEAKER && print.styleTokens == STYLE_TOKENS)
            val bytes = ByteBuffer.allocate((print.speaker.size + print.style.size) * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(print.speaker).put(print.style)
            File(folder, PRINT).writeBytes(bytes.array())
            clip.copyTo(File(folder, "preview." + clip.extension.lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "audio" }), overwrite = true)
            val now = System.currentTimeMillis()
            val voice = ReaderVoice(id, name.trim().take(200), importedAt = now, enrolledAt = now)
            File(folder, "voice.json").writeText(json.encodeToString(ReaderVoice.serializer(), voice))
            refresh(); voice
        } catch (e: Throwable) { folder.deleteRecursively(); throw e }
        finally { pcm.delete() }
    }

    /** The enrolled voice (speaker vector, then style tokens, as little-endian floats), or null if it is missing. */
    fun print(voice: ReaderVoice): NanoVoice? = runCatching {
        val bytes = File(dir(voice.id), PRINT).readBytes()
        val floats = FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        NanoVoice(floats.copyOfRange(0, SPEAKER), floats.copyOfRange(SPEAKER, floats.size), STYLE_TOKENS)
    }.getOrNull()

    fun preview(voice: ReaderVoice): File? = dir(voice.id).listFiles().orEmpty().firstOrNull { it.name.startsWith("preview.") }

    suspend fun rename(voice: ReaderVoice, name: String) = withContext(Dispatchers.IO) {
        require(name.isNotBlank())
        File(dir(voice.id), "voice.json").writeText(json.encodeToString(ReaderVoice.serializer(), voice.copy(name = name.trim().take(200))))
        refresh()
    }

    suspend fun delete(voice: ReaderVoice) = withContext(Dispatchers.IO) { dir(voice.id).deleteRecursively(); refresh() }

    private companion object {
        const val PRINT = "print.f32"
        const val SPEAKER = 192
        const val STYLE_TOKENS = 50
    }
}
