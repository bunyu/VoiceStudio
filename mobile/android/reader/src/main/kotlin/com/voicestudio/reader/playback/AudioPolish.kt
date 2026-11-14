package com.voicestudio.reader.playback

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Makes separately generated pieces sound like one continuous reading: each piece is trimmed of the
 * model's own leading/trailing silence, brought to the same loudness, and followed by a pause whose
 * length depends on where it ends (sentence, paragraph or chapter).
 */
object AudioPolish {
    private const val TARGET_RMS = 0.08f   // about -22 dBFS: comfortable speech level
    private const val PEAK = 0.95f
    private const val GATE = 0.012f        // below this is treated as silence when trimming

    fun finish(samples: FloatArray, sampleRate: Int, pauseMillis: Int): FloatArray {
        require(sampleRate > 0 && pauseMillis >= 0)
        val window = max(1, sampleRate / 100) // 10 ms
        fun loud(at: Int): Boolean {
            var peak = 0f
            for (i in at until min(samples.size, at + window)) peak = max(peak, abs(samples[i]))
            return peak > GATE
        }
        var start = 0
        while (start < samples.size && !loud(start)) start += window
        var end = samples.size
        while (end > start && !loud(max(start, end - window))) end -= window
        val pause = sampleRate.toLong() * pauseMillis / 1000
        if (end <= start) return FloatArray(pause.toInt())
        // Keep a little breath either side so consonants are not clipped.
        start = max(0, start - sampleRate / 50)
        end = min(samples.size, end + sampleRate / 25)
        val speech = samples.copyOfRange(start, end)

        var sum = 0.0
        for (s in speech) sum += s * s
        val rms = sqrt(sum / speech.size).toFloat()
        val peak = speech.maxOf { abs(it) }
        var gain = if (rms > 0f) TARGET_RMS / rms else 1f
        if (peak * gain > PEAK) gain = PEAK / peak
        gain = gain.coerceIn(0.1f, 6f)
        // 5 ms fades remove clicks where pieces join.
        val fade = max(1, sampleRate / 200)
        val out = FloatArray(speech.size + pause.toInt())
        for (i in speech.indices) {
            val edge = min(1f, min(i, speech.size - 1 - i).toFloat() / fade)
            out[i] = (speech[i] * gain * edge).coerceIn(-1f, 1f)
        }
        return out
    }
}
