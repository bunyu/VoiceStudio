package com.voicestudio.reader

import com.voicestudio.reader.playback.AudioPolish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioPolishTest {
    private val rate = 24000
    private fun tone(seconds: Double, amplitude: Float) = FloatArray((seconds * rate).toInt()) { (amplitude * sin(2 * PI * 220 * it / rate)).toFloat() }
    private fun rms(x: FloatArray) = sqrt(x.sumOf { (it * it).toDouble() } / x.size).toFloat()

    @Test fun trimsModelSilenceAndAddsTheRequestedPause() {
        val speech = FloatArray(rate) + tone(1.0, 0.3f) + FloatArray(rate)
        val out = AudioPolish.finish(speech, rate, 650)
        val pause = (rate * 0.65).toInt()
        // About one second of speech plus breath margins, then exactly the pause.
        assertTrue(out.size in rate + pause..rate + pause + rate / 10)
        assertTrue(out.takeLast(pause).all { it == 0f })
    }

    @Test fun quietAndLoudPiecesEndUpAtTheSameLevel() {
        val quiet = AudioPolish.finish(tone(1.0, 0.03f), rate, 0)
        val loud = AudioPolish.finish(tone(1.0, 0.6f), rate, 0)
        assertEquals(rms(quiet), rms(loud), 0.01f)
        assertTrue(loud.all { abs(it) <= 0.95f })
    }

    @Test fun silentPieceBecomesOnlyThePause() {
        assertEquals(rate / 4, AudioPolish.finish(FloatArray(rate), rate, 250).size)
    }
}
