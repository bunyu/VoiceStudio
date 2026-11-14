package com.voicestudio.reader

import com.voicestudio.reader.vieneu.NanoEngine
import com.voicestudio.reader.vieneu.NanoEnroller
import com.voicestudio.reader.vieneu.NanoOptions
import com.voicestudio.reader.vieneu.NanoPresets
import com.voicestudio.reader.vieneu.NanoVoice
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Runs the real model when its files are available: set READER_NANO_DIR to a folder holding the downloaded
 * release (the graphs, config.json, constants.npz and voices_v3_nano.json). Writes the audio next to the
 * build output so a person can listen, and prints how fast this machine generated it.
 */
class NanoModelTest {
    // "Xin chào các bạn, hôm nay trời đẹp quá. Chúng ta cùng đọc một cuốn tiểu thuyết nhé." through sea-g2p 0.10.0.
    private val phonemes = "sˈin tʃˈaː2w kˌaːɜc bˈaː6n, hˈom nˈaj tʃˈəː2j ɗˈɛ6p kwˈaːɜ. tʃˈuɜŋ t̪ˈaː kˈu2ŋ ɗˈɔ6k mˈo6t̪ kˈuəɜn t̪ˈiɛ4w twˈiɛɜt̪ ɲˈɛɜ."

    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)
    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return dot / sqrt(aa * bb)
    }

    private fun save(name: String, samples: FloatArray) {
        val file = File("build/nano-samples", name).apply { parentFile!!.mkdirs() }
        val data = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(NanoEngine.SAMPLE_RATE).putInt(NanoEngine.SAMPLE_RATE * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples.size * 2)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        file.writeBytes(data.array())
        println("wrote ${file.absolutePath}")
    }

    private fun timed(label: String, engine: NanoEngine, voice: NanoVoice, options: NanoOptions): FloatArray {
        val start = System.nanoTime()
        val audio = engine.speak(phonemes, voice, options, seed = 1)
        val took = (System.nanoTime() - start) / 1e9
        val seconds = audio.size.toDouble() / NanoEngine.SAMPLE_RATE
        println("$label: %.2f s of audio in %.2f s (real-time factor %.2f), level %.3f".format(seconds, took, took / seconds, rms(audio)))
        assertTrue("$label length $seconds", seconds in 3.0..12.0)
        assertTrue("$label is silent", rms(audio) > 0.01)
        return audio
    }

    @Test fun speaksAndClonesWithTheRealModel() {
        val directory = System.getenv("READER_NANO_DIR")?.let(::File)
        assumeTrue(directory != null && NanoEngine.FILES.all { File(directory, it).isFile } && File(directory, "voices_v3_nano.json").isFile)
        val presets = NanoPresets.read(File(directory, "voices_v3_nano.json"))
        println("voices: " + presets.voices.joinToString { it.name } + "; default " + presets.default?.name)
        val preset = presets.default!!
        NanoEngine(directory!!).use { engine ->
            timed("first run (includes warm-up)", engine, preset.voice, NanoOptions())
            val standard = timed("16 steps", engine, preset.voice, NanoOptions())
            save("preset-16-steps.wav", standard)
            save("preset-8-steps.wav", timed("8 steps with sway", engine, preset.voice, NanoOptions(steps = 8, sway = -1f)))
            if (listOf(NanoEnroller.SPEAKER_ENCODER, NanoEnroller.CODEC_ENCODER, NanoEnroller.REFERENCE_ENCODER).all { File(directory, it).isFile }) {
                // Enrolling the model's own speech must land near the voice that spoke it.
                val cloned = NanoEnroller(directory).use { it.enroll(standard, NanoEngine.SAMPLE_RATE) }
                val similarity = cosine(cloned.speaker, preset.voice.speaker)
                val other = presets.voices.filter { it !== preset }.maxOf { cosine(cloned.speaker, it.voice.speaker) }
                println("speaker similarity to the source voice %.3f, closest other voice %.3f".format(similarity, other))
                assertTrue("speaker vector is far from its source: $similarity", similarity > 0.5 && similarity > other)
                save("cloned-from-preset.wav", timed("cloned voice", engine, cloned, NanoOptions()))
            }
        }
    }
}
