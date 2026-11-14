package com.voicestudio.reader.playback

import com.voicestudio.mobile.local.BookExport
import com.voicestudio.mobile.local.LocalAudio
import com.voicestudio.mobile.local.markSynthetic
import com.voicestudio.reader.data.BookPiece
import com.voicestudio.reader.data.Settings
import com.voicestudio.reader.data.VoiceChoice
import com.voicestudio.reader.text.Chunker
import com.voicestudio.reader.text.VietnameseText
import com.voicestudio.reader.vieneu.NanoEngine
import com.voicestudio.reader.vieneu.NanoVoice
import com.voicestudio.reader.voice.ReaderVoices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** A voice ready to speak: everything a render needs, resolved once per settings change. */
class ResolvedVoice(val choice: VoiceChoice, val voice: NanoVoice, val rules: VietnameseText, val fast: Boolean, private val identity: String) {
    private fun digest(piece: BookPiece): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$identity|${piece.pause.millis}|".toByteArray())
        digest.update(piece.text.toByteArray())
        return digest.digest()
    }
    fun key(piece: BookPiece): String = digest(piece).take(16).joinToString("") { "%02x".format(it) }
    /** The starting noise for a piece: fixed, so re-reading a passage sounds the same every time. */
    fun seed(piece: BookPiece, part: Int): Long = ByteBuffer.wrap(digest(piece)).getLong(16) + part
}

class VoiceUnavailable(val reason: String) : Exception(reason)

/** Turns one book piece into a short, normalized, compressed audio file in the book's audio folder. */
class Renderer(private val vieneu: VieNeu, private val voices: ReaderVoices) {
    suspend fun resolve(settings: Settings): ResolvedVoice {
        if (!vieneu.ready) throw VoiceUnavailable("engine_missing")
        val rules = settings.textRules()
        val shared = "${vieneu.release.id}|${settings.fast}|${VietnameseText.VERSION}|${settings.dialect}|${settings.pronunciations.hashCode()}"
        return when (val choice = settings.voice) {
            is VoiceChoice.Cloned -> {
                val voice = voices.voices.value.firstOrNull { it.id == choice.id } ?: throw VoiceUnavailable("voice_missing")
                val print = voices.print(voice) ?: throw VoiceUnavailable("voice_missing")
                ResolvedVoice(choice, print, rules, settings.fast, "clone|${voice.id}|${voice.enrolledAt}|$shared")
            }
            is VoiceChoice.Preset, null -> {
                val presets = vieneu.presets()
                val preset = (choice as? VoiceChoice.Preset)?.let { presets[it.name] } ?: presets.default ?: throw VoiceUnavailable("voice_missing")
                ResolvedVoice(VoiceChoice.Preset(preset.name), preset.voice, rules, settings.fast, "preset|${preset.name}|$shared")
            }
        }
    }

    fun file(folder: File, voice: ResolvedVoice, piece: BookPiece) = File(folder, voice.key(piece) + ".m4a")

    suspend fun render(folder: File, voice: ResolvedVoice, piece: BookPiece): File = withContext(Dispatchers.Default) {
        val target = file(folder, voice, piece)
        if (target.isFile) return@withContext target
        folder.mkdirs()
        val rate = NanoEngine.SAMPLE_RATE
        val spoken = voice.rules.normalize(piece.text)
        // The model reads at most about fifteen seconds in one go, so a long piece is spoken in parts.
        val parts = if (spoken.none { it.isLetterOrDigit() }) emptyList() else Chunker.split(spoken, maxChars = NanoEngine.MAX_CHARACTERS, minChars = 40)
        val context = coroutineContext
        val polished = ArrayList<FloatArray>()
        parts.forEachIndexed { index, part ->
            context.ensureActive()
            val samples = vieneu.speak(part.text, voice.voice, voice.fast, voice.seed(piece, index)) { !context.isActive }
            polished += AudioPolish.finish(samples, rate, if (index == parts.lastIndex) piece.pause.millis else part.pause.millis)
        }
        val joined = FloatArray(polished.sumOf { it.size }).also { out -> var at = 0; for (p in polished) { p.copyInto(out, at); at += p.size } }
        val wave = File(folder, UUID.randomUUID().toString() + ".wav")
        try {
            markSynthetic(LocalAudio(if (joined.isEmpty()) FloatArray(rate / 10) else joined, rate), wave)
            val encoded = BookExport.encode(wave, "m4a")
            Files.move(encoded.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            target
        } finally { wave.delete(); File(folder, wave.nameWithoutExtension + ".m4a").delete() }
    }
}
