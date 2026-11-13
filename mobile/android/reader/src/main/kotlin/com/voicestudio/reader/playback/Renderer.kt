package com.voicestudio.reader.playback

import com.voicestudio.mobile.local.BookExport
import com.voicestudio.mobile.local.LocalAudio
import com.voicestudio.mobile.local.markSynthetic
import com.voicestudio.mobile.network.ClientFailure
import com.voicestudio.reader.data.BookPiece
import com.voicestudio.reader.data.Settings
import com.voicestudio.reader.data.VoiceChoice
import com.voicestudio.reader.text.VietnameseText
import com.voicestudio.reader.voice.ReaderVoice
import com.voicestudio.reader.voice.ReaderVoices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** A voice ready to speak: everything a render needs, resolved once per settings change. */
class ResolvedVoice(val choice: VoiceChoice, val voice: ReaderVoice?, val reference: LocalAudio?, val rules: VietnameseText, private val identity: String) {
    fun key(piece: BookPiece): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$identity|${piece.pause.millis}|".toByteArray())
        digest.update(piece.text.toByteArray())
        return digest.digest().take(16).joinToString("") { "%02x".format(it) }
    }
}

class VoiceUnavailable(val reason: String) : Exception(reason)

/** Turns one book piece into a short, normalized, compressed audio file in the book's audio folder. */
class Renderer(private val engines: Engines, private val voices: ReaderVoices) {
    suspend fun resolve(settings: Settings): ResolvedVoice {
        val rules = settings.textRules()
        val rulesId = "${VietnameseText.VERSION}|${settings.dialect}|${settings.pronunciations.hashCode()}"
        return when (val choice = settings.voice ?: VoiceChoice.Preset(0)) {
            is VoiceChoice.Cloned -> {
                val voice = voices.voices.value.firstOrNull { it.id == choice.id } ?: throw VoiceUnavailable("voice_missing")
                if (!engines.ready(engines.omnivoice)) throw VoiceUnavailable("omnivoice_missing")
                ResolvedVoice(choice, voice, voices.reference(voice), rules, "omni|${engines.omnivoice.revision}|${voice.id}|${voice.transcript.hashCode()}|$rulesId")
            }
            is VoiceChoice.Preset -> {
                if (!engines.ready(engines.supertonic)) throw VoiceUnavailable("supertonic_missing")
                ResolvedVoice(choice, null, null, rules, "supertonic|${engines.supertonic.revision}|${choice.speaker}|$rulesId")
            }
        }
    }

    fun file(folder: File, voice: ResolvedVoice, piece: BookPiece) = File(folder, voice.key(piece) + ".m4a")

    suspend fun render(folder: File, voice: ResolvedVoice, piece: BookPiece): File = withContext(Dispatchers.Default) {
        val target = file(folder, voice, piece)
        if (target.isFile) return@withContext target
        folder.mkdirs()
        val spoken = voice.rules.normalize(piece.text)
        val audio = if (spoken.none { it.isLetterOrDigit() }) LocalAudio(FloatArray(2400), 24000) else synthesize(voice, spoken)
        val polished = AudioPolish.finish(audio.samples, audio.sampleRate, piece.pause.millis)
        val wave = File(folder, UUID.randomUUID().toString() + ".wav")
        try {
            markSynthetic(LocalAudio(if (polished.isEmpty()) FloatArray(audio.sampleRate / 10) else polished, audio.sampleRate), wave)
            val encoded = BookExport.encode(wave, "m4a")
            Files.move(encoded.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            target
        } finally { wave.delete(); File(folder, wave.nameWithoutExtension + ".m4a").delete() }
    }

    private suspend fun synthesize(voice: ResolvedVoice, text: String): LocalAudio {
        val engine = engines.runtime.engine
        suspend fun once() = when (val choice = voice.choice) {
            is VoiceChoice.Cloned -> engine.clone(engines.omnivoice, text, voice.reference!!, voice.voice!!.transcript)
            is VoiceChoice.Preset -> engine.synthesize(engines.supertonic, text, speaker = choice.speaker, speed = 1f, language = "vi")
        }
        // A diffusion model occasionally fails on one piece; one fresh attempt usually succeeds.
        return try { once() } catch (e: ClientFailure) {
            if (e.key == "mobile.local.inference_failed") once() else throw e
        }
    }
}
