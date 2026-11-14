package com.voicestudio.reader

import com.voicestudio.reader.vieneu.NanoEngine
import com.voicestudio.reader.vieneu.NanoOptions
import com.voicestudio.reader.vieneu.NanoVoice
import com.voicestudio.reader.vieneu.Npy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.pow

/**
 * Checks the sampling loop against a stand-in model (scripts/make_nano_fixture.py) whose vector field is -x:
 * n uniform Euler steps then leave exactly noise * (1 - 1/n)^n, with or without guidance.
 */
class NanoEngineTest {
    private val model = File(javaClass.getResource("/nano/config.json")!!.toURI()).parentFile
    private val voice = NanoVoice(FloatArray(4) { 0.1f }, FloatArray(6) { 0.2f }, styleTokens = 2)

    @Test fun readsNumpyArchive() {
        val arrays = Npy.readArchive(File(model, "constants.npz"))
        assertArrayEquals(intArrayOf(2, 3), arrays.getValue("null_style").shape)
        assertEquals(4, arrays.getValue("latent_mean").data.size)   // stored as 64-bit
        assertEquals(1f, arrays.getValue("latent_scale").data.single(), 0f)
    }

    @Test fun encodesKnownSymbolsAndEmotionTags() = NanoEngine(model, threads = 1).use { engine ->
        assertArrayEquals(longArrayOf(1, 3, 5, 4, 7, 6, 2), engine.encode("a bz<|emotion_1|>."))
    }

    @Test fun integratesTheVectorFieldFromSeededNoise() = NanoEngine(model, threads = 1).use { engine ->
        val frames = 10   // 0.64 s at 15.625 frames per second
        val random = Random(7)
        val noise = FloatArray(6 * frames) { random.nextGaussian().toFloat() }
        for (options in listOf(NanoOptions(steps = 16), NanoOptions(steps = 8, guidance = 0f))) {
            val audio = engine.speak("ab.", voice, options, seed = 7)
            val factor = (1.0 - 1.0 / options.steps).pow(options.steps).toFloat()
            assertEquals(noise.size, audio.size)
            for (i in noise.indices) assertEquals((noise[i] * factor).coerceIn(-1f, 1f), audio[i], 1e-4f)
        }
        assertFalse(engine.speak("ab.", voice, seed = 8).contentEquals(engine.speak("ab.", voice, seed = 7)))
        assertEquals(6 * 5, engine.speak("ab.", voice, NanoOptions(speed = 2f)).size)
        assertEquals(0, engine.speak("zzz", voice).size)
        assertThrows(InterruptedException::class.java) { engine.speak("ab.", voice, cancelled = { true }) }
        assertTrue(engine.speak("ab.", voice, NanoOptions(sway = -1f)).all { it in -1f..1f })
    }
}
