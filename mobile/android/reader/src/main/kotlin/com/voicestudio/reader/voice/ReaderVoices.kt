package com.voicestudio.reader.voice

import com.voicestudio.mobile.local.AndroidAudioDecoder
import com.voicestudio.mobile.local.AudioDecoder
import com.voicestudio.mobile.local.LocalAudio
import com.voicestudio.mobile.local.PcmFile
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
import java.util.UUID

/** A voice imported from VoiceStudio: a short reference clip plus exactly what is said in it. */
@Serializable data class ReaderVoice(val id: String, val name: String, val transcript: String, val language: String, val designed: Boolean, val importedAt: Long)

/**
 * Private voice storage. The reference is kept as 24 kHz mono PCM (what OmniVoice clones from) together with
 * the original preview, so the voice can be auditioned without running the model.
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

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            state.value = root.listFiles().orEmpty().mapNotNull { folder ->
                runCatching { json.decodeFromString<ReaderVoice>(File(folder, "voice.json").readText()) }.getOrNull()
                    ?.takeIf { File(folder, "reference.pcm").length() >= 9600 }
            }.sortedByDescending { it.importedAt }
        }
    }

    /** Reads a bundle for review before anything is stored; the caller shows its name and transcript. */
    suspend fun inspect(bundle: File, workspace: File): BundleVoice = withContext(Dispatchers.IO) { VoiceBundle.read(bundle, workspace) }

    /**
     * Stores a reviewed voice. [transcript] must be what the clip says, word for word: OmniVoice lines the
     * clip up with it, and a mismatch makes the cloned voice slur or skip words.
     */
    suspend fun install(bundle: BundleVoice, name: String, transcript: String): ReaderVoice = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && transcript.isNotBlank())
        val id = UUID.randomUUID().toString()
        val folder = dir(id).apply { mkdirs() }
        try {
            // At most 30 seconds: the on-device cloning model accepts nothing longer.
            val pcm = decoder.decode(bundle.audio, File(folder, "reference.pcm"), 30)
            require(pcm.frames in 2400..720_000)
            (bundle.preview ?: bundle.audio).copyTo(File(folder, "preview." + (bundle.preview ?: bundle.audio).extension), overwrite = true)
            val voice = ReaderVoice(id, name.trim().take(200), transcript.trim().take(4000), bundle.language, bundle.designed, System.currentTimeMillis())
            File(folder, "voice.json").writeText(json.encodeToString(ReaderVoice.serializer(), voice))
            refresh(); voice
        } catch (e: Throwable) { folder.deleteRecursively(); throw e }
    }

    suspend fun reference(voice: ReaderVoice): LocalAudio = withContext(Dispatchers.IO) {
        val pcm = PcmFile(File(dir(voice.id), "reference.pcm"), 24000)
        pcm.read(0, pcm.frames.toInt())
    }

    fun preview(voice: ReaderVoice): File? = dir(voice.id).listFiles().orEmpty().firstOrNull { it.name.startsWith("preview.") }

    suspend fun rename(voice: ReaderVoice, name: String, transcript: String) = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && transcript.isNotBlank())
        File(dir(voice.id), "voice.json").writeText(json.encodeToString(ReaderVoice.serializer(), voice.copy(name = name.trim().take(200), transcript = transcript.trim().take(4000))))
        refresh()
    }

    suspend fun delete(voice: ReaderVoice) = withContext(Dispatchers.IO) { dir(voice.id).deleteRecursively(); refresh() }
}
