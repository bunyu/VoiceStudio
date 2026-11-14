package com.voicestudio.reader

import com.voicestudio.reader.vieneu.Npy
import com.voicestudio.reader.vieneu.SpeechFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SpeechFeaturesTest {
    /** The reference comes from kaldi-native-fbank with the upstream options (scripts/make_fbank_fixture.py). */
    @Test fun filterbankMatchesKaldi() {
        val reference = Npy.readArchive(File(javaClass.getResource("/fbank/reference.npz")!!.toURI()))
        val expected = reference.getValue("features")
        val actual = SpeechFeatures.fbank(reference.getValue("wave").data)
        assertEquals(expected.shape[0], actual.size)
        var worst = 0f
        for (f in actual.indices) for (b in 0 until 80) worst = maxOf(worst, abs(actual[f][b] - expected.data[f * 80 + b]))
        assertTrue("largest difference $worst", worst < 2e-3f)
        SpeechFeatures.meanNormalize(actual)
        for (b in 0 until 80) assertEquals(0.0, actual.sumOf { it[b].toDouble() } / actual.size, 1e-4)
        assertEquals(0, SpeechFeatures.fbank(FloatArray(399)).size)
    }

    private fun tone(hz: Double, rate: Int, seconds: Double) = FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }
    private fun rms(x: FloatArray, from: Int, to: Int): Double { var s = 0.0; for (i in from until to) s += x[i] * x[i]; return sqrt(s / (to - from)) }

    @Test fun resamplingKeepsSpeechBandAndRemovesAliases() {
        val down = SpeechFeatures.resample(tone(1000.0, 24_000, 0.5), 24_000, 16_000)
        assertEquals(8000, down.size)
        val expected = tone(1000.0, 16_000, 0.5)
        for (i in 500 until 7500) assertEquals(expected[i], down[i], 5e-3f)
        // 11 kHz cannot exist at 16 kHz: it must vanish instead of folding down to 5 kHz.
        val alias = SpeechFeatures.resample(tone(11_000.0, 24_000, 0.5), 24_000, 16_000)
        assertTrue(rms(alias, 500, 7500) < 0.005)
        val up = SpeechFeatures.resample(tone(440.0, 16_000, 0.25), 16_000, 24_000)
        assertEquals(6000, up.size)
        assertEquals(rms(tone(440.0, 24_000, 0.25), 500, 5500), rms(up, 500, 5500), 2e-3)
    }

    @Test fun findsSilenceAtTheEdges() {
        val clip = FloatArray(16_000)
        for (i in 4000 until 12_000) clip[i] = 0.2f
        assertEquals(4000 to 4000, SpeechFeatures.edgeSilence(clip, 16_000))
        assertEquals(16_000 to 0, SpeechFeatures.edgeSilence(FloatArray(16_000), 16_000))
    }
}
