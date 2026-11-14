package com.voicestudio.reader.vieneu

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/** The recording cannot be enrolled; [reason] is `too_short` or `silent`. */
class EnrolmentFailed(val reason: String) : Exception(reason)

/**
 * Turns a short recording into a [NanoVoice], following `prepare_reference` in the upstream SDK: the
 * speaker vector comes from Kaldi filterbank features of the whole clip, the style tokens from the codec
 * latents of its first five seconds. Upstream also denoises the clip first; this port does not, so the
 * recording should be made somewhere quiet.
 */
class NanoEnroller(directory: File, threads: Int = 4) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setInterOpNumThreads(1)
        setIntraOpNumThreads(threads.coerceIn(1, 8))
    }
    private val config = Json.parseToJsonElement(File(directory, NanoEngine.CONFIG).readText()).jsonObject
    private val constants = Npy.readArchive(File(directory, NanoEngine.CONSTANTS))
    private val mean = constants.getValue("latent_mean").data
    private val deviation = constants.getValue("latent_std").data
    private val scale = constants["latent_scale"]?.data?.firstOrNull() ?: config["latent_scale"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 1f
    private val group = config["group"]?.jsonPrimitive?.intOrNull ?: 6
    private val maxFrames = config["ref_max_frames"]?.jsonPrimitive?.intOrNull ?: 140
    private val framesPerSecond = config["flow_fps"]?.jsonPrimitive?.doubleOrNull ?: (NanoEngine.SAMPLE_RATE / 256.0 / 6.0)
    private val speakerEncoder = environment.createSession(File(directory, SPEAKER_ENCODER).absolutePath, options)
    private val codecEncoder = environment.createSession(File(directory, CODEC_ENCODER).absolutePath, options)
    private val referenceEncoder = environment.createSession(File(directory, REFERENCE_ENCODER).absolutePath, options)

    @Synchronized
    fun enroll(recording: FloatArray, sampleRate: Int): NanoVoice {
        val (lead, tail) = SpeechFeatures.edgeSilence(recording, sampleRate, thresholdDb = -40f)
        if (lead >= recording.size) throw EnrolmentFailed("silent")
        val keep = sampleRate / 100
        val clip = recording.copyOfRange(max(0, lead - keep), min(recording.size, recording.size - tail + keep)).let { it.copyOf(min(it.size, MAX_SECONDS * sampleRate)) }
        if (clip.size < MIN_SECONDS * sampleRate) throw EnrolmentFailed("too_short")

        val features = SpeechFeatures.fbank(SpeechFeatures.resample(clip, sampleRate, 16_000))
        SpeechFeatures.meanNormalize(features)
        val flat = FloatArray(features.size * 80).also { out -> features.forEachIndexed { f, row -> row.copyInto(out, f * 80) } }
        val speaker = OnnxTensor.createTensor(environment, FloatBuffer.wrap(flat), longArrayOf(1, features.size.toLong(), 80)).use { input ->
            speakerEncoder.run(mapOf(speakerEncoder.inputNames.first() to input)).use { (it[0] as OnnxTensor).floatBuffer.toArray() }
        }

        val codecAudio = SpeechFeatures.resample(clip, sampleRate, NanoEngine.SAMPLE_RATE).let { it.copyOf(min(it.size, (STYLE_SECONDS * NanoEngine.SAMPLE_RATE).toInt())) }
        val (latent, channels, length) = OnnxTensor.createTensor(environment, FloatBuffer.wrap(codecAudio), longArrayOf(1, 1, codecAudio.size.toLong())).use { input ->
            codecEncoder.run(mapOf(codecEncoder.inputNames.first() to input)).use {
                val tensor = it[0] as OnnxTensor
                Triple(tensor.floatBuffer.toArray(), tensor.info.shape[1].toInt(), tensor.info.shape[2].toInt())
            }
        }
        require(channels == mean.size && channels == deviation.size) { "codec latent does not match constants.npz" }
        // Normalise as in training, then fold every `group` latent frames into one wider frame (zero-padded at the end).
        val frames = min((length + group - 1) / group, min((STYLE_SECONDS * framesPerSecond).toInt(), maxFrames))
        val grouped = FloatArray(channels * group * frames)
        for (c in 0 until channels) for (f in 0 until frames) for (j in 0 until group) {
            val t = f * group + j
            if (t < length) grouped[(c * group + j) * frames + f] = (latent[c * length + t] - mean[c]) / deviation[c] * scale
        }
        val style = OnnxTensor.createTensor(environment, FloatBuffer.wrap(grouped), longArrayOf(1, (channels * group).toLong(), frames.toLong())).use { reference ->
            OnnxTensor.createTensor(environment, arrayOf(BooleanArray(frames) { true })).use { mask ->
                referenceEncoder.run(mapOf("ref" to reference, "ref_mask" to mask)).use {
                    val tensor = it[0] as OnnxTensor
                    tensor.floatBuffer.toArray() to tensor.info.shape[1].toInt()
                }
            }
        }
        return NanoVoice(speaker, style.first, style.second)
    }

    private fun FloatBuffer.toArray() = FloatArray(remaining()).also { get(it) }

    @Synchronized override fun close() {
        listOf(speakerEncoder, codecEncoder, referenceEncoder).forEach { it.close() }
        options.close()
    }

    companion object {
        const val SPEAKER_ENCODER = "speaker_encoder.onnx"
        const val CODEC_ENCODER = "codec_encoder.onnx"
        const val REFERENCE_ENCODER = "reference_encoder.onnx"
        /** The speaker vector hears at most this much of the clip. */
        const val MAX_SECONDS = 30
        const val MIN_SECONDS = 2
        /** Style tokens come from the first seconds of the clip. */
        const val STYLE_SECONDS = 5.0
    }
}
