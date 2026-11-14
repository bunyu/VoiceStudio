package com.voicestudio.reader.vieneu

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Signal processing for enrolling a voice. Plain Kotlin so it is tested against the reference implementation. */
object SpeechFeatures {
    /**
     * Band-limited resampling with a Kaiser-windowed sinc. Used for the speaker encoder (16 kHz) and the
     * codec (24 kHz); quality matters more than speed here, since a reference clip is a few seconds long.
     */
    fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        require(from > 0 && to > 0)
        if (from == to || samples.isEmpty()) return samples.copyOf()
        val ratio = to.toDouble() / from
        val cutoff = min(1.0, ratio) * 0.95          // of the input Nyquist frequency
        val half = (16 / min(1.0, ratio)).toInt()    // zero crossings kept on each side
        val beta = 8.6
        val out = FloatArray(max(1, floor(samples.size * ratio).toInt()))
        for (i in out.indices) {
            val centre = i / ratio
            val first = max(0, floor(centre).toInt() - half + 1)
            val last = min(samples.size - 1, floor(centre).toInt() + half)
            var sum = 0.0
            for (n in first..last) {
                val x = n - centre
                val window = bessel(beta * kotlin.math.sqrt(max(0.0, 1 - (x / half) * (x / half)))) / bessel(beta)
                sum += samples[n] * cutoff * sinc(cutoff * x) * window
            }
            out[i] = sum.toFloat()
        }
        return out
    }

    private fun sinc(x: Double) = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
    private fun bessel(x: Double): Double { var sum = 1.0; var term = 1.0; var k = 1; while (term > 1e-12 * sum) { term *= (x / (2 * k)) * (x / (2 * k)); sum += term; k++ }; return sum }

    /**
     * Kaldi log-mel filterbank features, as the speaker encoder was trained on: 25 ms Povey windows every
     * 10 ms, DC removal, 0.97 pre-emphasis, power spectrum, mel bins from 20 Hz to Nyquist, no dither,
     * whole frames only. Returns frames x [bins]; empty when the audio is shorter than one frame.
     */
    fun fbank(samples: FloatArray, sampleRate: Int = 16_000, bins: Int = 80): Array<FloatArray> {
        val length = sampleRate / 1000 * 25
        val shift = sampleRate / 1000 * 10
        if (samples.size < length) return emptyArray()
        var size = 1; while (size < length) size = size shl 1
        val window = DoubleArray(length) { (0.5 - 0.5 * cos(2 * PI * it / (length - 1))).pow(0.85) }
        val filters = melFilters(bins, size, sampleRate)
        val frames = 1 + (samples.size - length) / shift
        val real = DoubleArray(size); val imaginary = DoubleArray(size)
        return Array(frames) { f ->
            var mean = 0.0
            for (i in 0 until length) mean += samples[f * shift + i]
            mean /= length
            for (i in 0 until length) real[i] = samples[f * shift + i] - mean
            for (i in length - 1 downTo 1) real[i] -= 0.97 * real[i - 1]
            real[0] -= 0.97 * real[0]
            for (i in 0 until length) real[i] *= window[i]
            java.util.Arrays.fill(real, length, size, 0.0); java.util.Arrays.fill(imaginary, 0.0)
            fft(real, imaginary)
            FloatArray(bins) { b ->
                val (start, weights) = filters[b]
                var energy = 0.0
                for (k in weights.indices) { val bin = start + k; energy += weights[k] * (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) }
                ln(max(energy, FLOOR)).toFloat()
            }
        }
    }

    /** Subtracts each bin's mean over time, which removes the microphone's level and tone from the features. */
    fun meanNormalize(features: Array<FloatArray>) {
        if (features.isEmpty()) return
        for (b in features[0].indices) {
            var mean = 0.0
            for (frame in features) mean += frame[b]
            val m = (mean / features.size).toFloat()
            for (frame in features) frame[b] -= m
        }
    }

    private const val FLOOR = 1.1920928955078125e-07   // float epsilon, Kaldi's energy floor
    private fun mel(hz: Double) = 1127.0 * ln(1.0 + hz / 700.0)

    private fun melFilters(bins: Int, size: Int, sampleRate: Int): List<Pair<Int, DoubleArray>> {
        val low = mel(20.0); val high = mel(sampleRate / 2.0)
        val delta = (high - low) / (bins + 1)
        val resolution = sampleRate.toDouble() / size
        return List(bins) { b ->
            val left = low + b * delta; val centre = left + delta; val right = centre + delta
            var first = -1
            val weights = ArrayList<Double>()
            for (k in 0 until size / 2) {
                val m = mel(resolution * k)
                if (m > left && m < right) {
                    if (first < 0) first = k
                    weights += if (m <= centre) (m - left) / (centre - left) else (right - m) / (right - centre)
                }
            }
            max(first, 0) to weights.toDoubleArray()
        }
    }

    /** In-place radix-2 FFT. */
    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val r = real[i]; real[i] = real[j]; real[j] = r; val m = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = m }
        }
        var length = 2
        while (length <= n) {
            val angle = -2 * PI / length
            val stepR = cos(angle); val stepI = sin(angle)
            var i = 0
            while (i < n) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until length / 2) {
                    val a = i + k; val b = a + length / 2
                    val tr = real[b] * wr - imaginary[b] * wi; val ti = real[b] * wi + imaginary[b] * wr
                    real[b] = real[a] - tr; imaginary[b] = imaginary[a] - ti
                    real[a] += tr; imaginary[a] += ti
                    val next = wr * stepR - wi * stepI; wi = wr * stepI + wi * stepR; wr = next
                }
                i += length
            }
            length = length shl 1
        }
    }

    /** Samples of silence at the start and end, by 10 ms mean level against a threshold in dB below full scale. */
    fun edgeSilence(samples: FloatArray, sampleRate: Int, thresholdDb: Float = -45f): Pair<Int, Int> {
        val window = max(1, sampleRate / 100)
        val count = samples.size / window
        val threshold = 10.0.pow(thresholdDb / 20.0)
        var first = -1; var last = -1
        for (w in 0 until count) {
            var sum = 0.0
            for (i in 0 until window) sum += abs(samples[w * window + i])
            if (sum / window > threshold) { if (first < 0) first = w; last = w }
        }
        return if (first < 0) samples.size to 0 else first * window to samples.size - (last + 1) * window
    }
}
