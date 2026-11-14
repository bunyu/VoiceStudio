package com.voicestudio.reader.playback

import android.content.Context
import android.net.ConnectivityManager
import com.voicestudio.reader.vieneu.NanoAssets
import com.voicestudio.reader.vieneu.NanoEngine
import com.voicestudio.reader.vieneu.NanoOptions
import com.voicestudio.reader.vieneu.NanoPresets
import com.voicestudio.reader.vieneu.NanoRelease
import com.voicestudio.reader.vieneu.NanoVoice
import com.voicestudio.reader.vieneu.ReleaseFile
import com.voicestudio.reader.vieneu.SeaG2p
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app's voice engine: VieNeu-TTS v3 Nano with its sea-g2p phonemiser, both running on the phone.
 * The model (about 280 MB) and the dictionary are downloaded once from Settings; after that reading is offline.
 */
class VieNeu(context: Context) {
    val release: NanoRelease = context.assets.open(RELEASE).bufferedReader().use { NanoRelease.decode(it.readText()) }
    val assets = NanoAssets(File(context.filesDir, "vieneu"), release, unmetered = {
        context.getSystemService(ConnectivityManager::class.java)?.let { !it.isActiveNetworkMetered } ?: false
    })
    private val mutex = Mutex()
    private var loaded: Loaded? = null

    private class Loaded(val engine: NanoEngine, val g2p: SeaG2p, val presets: NanoPresets)

    val ready get() = assets.installed(ReleaseFile.SPEECH)

    private suspend fun load(): Loaded = mutex.withLock {
        loaded ?: withContext(Dispatchers.IO) {
            check(ready) { "model not installed" }
            // Big cores only: more threads land on the efficiency cores and slow every step down.
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
            Loaded(NanoEngine(assets.directory, threads), SeaG2p(assets.file(DICTIONARY)), NanoPresets.read(assets.file(PRESETS)))
        }.also { loaded = it }
    }

    suspend fun presets(): NanoPresets = load().presets

    /** Spoken-form text to 24 kHz samples. Text longer than [NanoEngine.MAX_CHARACTERS] must be split first. */
    suspend fun speak(text: String, voice: NanoVoice, fast: Boolean, seed: Long, cancelled: () -> Boolean): FloatArray {
        val model = load()
        return withContext(Dispatchers.Default) {
            try { model.engine.speak(model.g2p.phonemize(text), voice, options(fast), seed, cancelled) }
            // Stopping mid-passage is a cancellation, not a failed passage.
            catch (e: InterruptedException) { throw kotlinx.coroutines.CancellationException("cancelled") }
        }
    }

    /** Frees the model's memory, and its files when [delete] is set. */
    suspend fun unload(delete: Boolean = false) {
        mutex.withLock { loaded?.let { it.engine.close(); it.g2p.close() }; loaded = null }
        if (delete) assets.delete()
    }

    companion object {
        const val RELEASE = "vieneu-release.json"
        const val DICTIONARY = "sea_g2p.bin"
        const val PRESETS = "voices_v3_nano.json"
        /** Upstream's two settings: 16 Euler steps, or 8 with sway sampling for about twice the speed. */
        fun options(fast: Boolean) = if (fast) NanoOptions(steps = 8, sway = -1f) else NanoOptions()
    }
}
