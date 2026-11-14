package com.voicestudio.reader.vieneu

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A voice the model can speak with: a 192-number speaker vector and a grid of style tokens. */
class NanoVoice(val speaker: FloatArray, val style: FloatArray, val styleTokens: Int) {
    init { require(speaker.isNotEmpty() && styleTokens > 0 && style.isNotEmpty() && style.size % styleTokens == 0) }
    val styleWidth get() = style.size / styleTokens
}

/** How a passage is generated. Fewer steps are faster and slightly rougher; upstream pairs 8 steps with sway -1. */
data class NanoOptions(val steps: Int = 16, val guidance: Float = 3f, val sway: Float = 0f, val speed: Float = 1f)

/**
 * VieNeu-TTS v3 Nano: a 48M-parameter flow-matching model that turns sea-g2p phonemes into 24 kHz speech.
 * This is a line-for-line port of the sampling loop in the upstream Python SDK (`vieneu/v3nano.py`,
 * Apache-2.0): encode the text with the voice's style, predict the duration, integrate the vector field
 * from noise with Euler steps and classifier-free guidance, then decode the latents to audio.
 *
 * One passage at a time: [speak] is synchronised. Plain JVM code, so it is tested off the phone.
 */
class NanoEngine(directory: File, threads: Int = 4) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setInterOpNumThreads(1)
        setIntraOpNumThreads(threads.coerceIn(1, 8))
        // Spinning worker threads keep cores hot between the short per-step runs and drain the battery.
        addConfigEntry("session.intra_op.allow_spinning", "0")
    }
    private val config: JsonObject = Json.parseToJsonElement(File(directory, CONFIG).readText()).jsonObject
    // Phonemes are single characters; the multi-character entries are the markers (<pad>, <bos>, <eos>) added by id below.
    private val vocabulary: Map<Int, Long> = config.getValue("vocab").jsonObject.entries
        .filter { (symbol, _) -> symbol.codePointCount(0, symbol.length) == 1 }
        .associate { (symbol, id) -> symbol.codePointAt(0) to id.jsonPrimitive.content.toLong() }
    private val emotions: Map<String, String> = (config["emotion_tags"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
    private val begin = number("bos_id").toLong()
    private val end = number("eos_id").toLong()
    private val padding = number("pad_id").toLong()
    /** Latent frames per second of audio. */
    val framesPerSecond: Float = config["flow_fps"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: (SAMPLE_RATE / 256f / 6f)
    private val constants = Npy.readArchive(File(directory, CONSTANTS))
    private val nullSpeaker = constants.getValue("null_spk")
    private val nullStyle = constants.getValue("null_style")

    private val textEncoder = session(directory, TEXT_ENCODER)
    private val durationPredictor = session(directory, DURATION_PREDICTOR)
    private val vectorEstimator = session(directory, VECTOR_ESTIMATOR)
    private val decoder = session(directory, DECODER)
    private val latentChannels: Int = (vectorEstimator.inputInfo.getValue("x").info as ai.onnxruntime.TensorInfo).shape[1].toInt().takeIf { it > 0 } ?: 144

    // The unconditional branch of guidance does not depend on the text or the voice: encode it once.
    private val nullStyleShape = longArrayOf(1, nullStyle.shape[nullStyle.shape.size - 2].toLong(), nullStyle.shape.last().toLong())
    private val nullContext: FloatArray
    private val nullContextShape: LongArray

    init {
        val (data, shape) = floats(nullStyle.data, nullStyleShape).use { style ->
            longs(longArrayOf(begin, end), longArrayOf(1, 2)).use { ids ->
                textEncoder.run(mapOf("ids" to ids, "style" to style)).use { result ->
                    val context = result[0] as OnnxTensor
                    context.floatBuffer.toArray() to context.info.shape
                }
            }
        }
        nullContext = data
        nullContextShape = shape
    }

    private fun number(name: String) = config.getValue(name).jsonPrimitive.intOrNull ?: error("config.json: $name")
    private fun session(directory: File, name: String) = environment.createSession(File(directory, name).absolutePath, options)
    private fun floats(data: FloatArray, shape: LongArray) = OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape)
    private fun longs(data: LongArray, shape: LongArray) = OnnxTensor.createTensor(environment, LongBuffer.wrap(data), shape)
    private fun mask(length: Int, value: (Int) -> Boolean = { true }) = OnnxTensor.createTensor(environment, arrayOf(BooleanArray(length, value)))
    private fun FloatBuffer.toArray() = FloatArray(remaining()).also { get(it) }

    /** Phonemes to model ids. Symbols the model was not trained on are dropped, as upstream does. */
    fun encode(phonemes: String): LongArray {
        var text = phonemes
        for ((tag, symbol) in emotions) text = text.replace(tag, symbol)
        val ids = ArrayList<Long>(text.length + 2)
        ids += begin
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            vocabulary[point]?.let { ids += it }
            index += Character.charCount(point)
        }
        ids += end
        return ids.toLongArray()
    }

    /**
     * One passage of phonemes as 24 kHz samples in -1..1. [seed] fixes the starting noise, so the same
     * passage and voice always produce the same audio. [cancelled] is polled between steps.
     */
    @Synchronized
    fun speak(phonemes: String, voice: NanoVoice, settings: NanoOptions = NanoOptions(), seed: Long = 0, cancelled: () -> Boolean = { false }): FloatArray {
        val ids = encode(phonemes)
        if (ids.size <= 2) return FloatArray(0)
        val steps = settings.steps.coerceIn(1, 64)
        val styleShape = longArrayOf(1, voice.styleTokens.toLong(), voice.styleWidth.toLong())
        floats(voice.style, styleShape).use { style -> floats(voice.speaker, longArrayOf(1, voice.speaker.size.toLong())).use { speaker ->
        longs(ids, longArrayOf(1, ids.size.toLong())).use { idTensor -> mask(ids.size) { ids[it] != padding }.use { textMask ->
        textEncoder.run(mapOf("ids" to idTensor, "style" to style)).use { encoded ->
            val context = encoded[0] as OnnxTensor
            val logSeconds = durationPredictor.run(mapOf("ctx" to context, "ctx_mask" to textMask, "spk" to speaker)).use { (it[0] as OnnxTensor).floatBuffer.get(0) }
            val seconds = min(exp(logSeconds) / max(settings.speed, 1e-3f), MAX_SECONDS)
            val frames = max(MIN_FRAMES, (seconds * framesPerSecond).roundToInt())
            val shape = longArrayOf(1, latentChannels.toLong(), frames.toLong())
            val random = Random(seed)
            val x = FloatArray(latentChannels * frames) { random.nextGaussian().toFloat() }
            // Sway bends the time grid towards the early, noisy steps; 0 keeps it uniform.
            val times = DoubleArray(steps + 1) { val u = it.toDouble() / steps; u + settings.sway * (cos(PI / 2 * u) - 1 + u) }
            floats(nullContext, nullContextShape).use { emptyContext -> mask(nullContextShape[1].toInt()).use { emptyMask ->
            floats(nullSpeaker.data, longArrayOf(1, nullSpeaker.data.size.toLong())).use { emptySpeaker -> floats(nullStyle.data, nullStyleShape).use { emptyStyle ->
                for (step in 0 until steps) {
                    if (cancelled()) throw InterruptedException("cancelled")
                    floats(x, shape).use { latent -> floats(floatArrayOf(times[step].toFloat()), longArrayOf(1)).use { time ->
                        val guided = vectorEstimator.run(mapOf("x" to latent, "t" to time, "ctx" to context, "ctx_mask" to textMask, "spk" to speaker, "style" to style))
                            .use { (it[0] as OnnxTensor).floatBuffer.toArray() }
                        if (settings.guidance > 0f) {
                            val free = vectorEstimator.run(mapOf("x" to latent, "t" to time, "ctx" to emptyContext, "ctx_mask" to emptyMask, "spk" to emptySpeaker, "style" to emptyStyle))
                                .use { (it[0] as OnnxTensor).floatBuffer.toArray() }
                            for (i in guided.indices) guided[i] = free[i] + settings.guidance * (guided[i] - free[i])
                        }
                        val delta = (times[step + 1] - times[step]).toFloat()
                        for (i in x.indices) x[i] += delta * guided[i]
                    } }
                }
            } } } }
            return floats(x, shape).use { latent ->
                decoder.run(mapOf("x" to latent)).use { audio -> (audio[0] as OnnxTensor).floatBuffer.toArray().also { for (i in it.indices) it[i] = it[i].coerceIn(-1f, 1f) } }
            }
        } } } } }
    }

    @Synchronized override fun close() {
        listOf(textEncoder, durationPredictor, vectorEstimator, decoder).forEach { it.close() }
        options.close()
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        /** Training clips were at most 15 s; longer passages drift. */
        const val MAX_SECONDS = 15f
        /** Passages longer than this many characters must be split before phonemising (upstream default). */
        const val MAX_CHARACTERS = 140
        private const val MIN_FRAMES = 2
        const val CONFIG = "config.json"
        const val CONSTANTS = "constants.npz"
        const val TEXT_ENCODER = "text_encoder.onnx"
        const val DURATION_PREDICTOR = "duration_predictor.onnx"
        const val VECTOR_ESTIMATOR = "vector_estimator.onnx"
        const val DECODER = "codec_decoder.onnx"
        val FILES = listOf(TEXT_ENCODER, DURATION_PREDICTOR, VECTOR_ESTIMATOR, DECODER, CONFIG, CONSTANTS)
    }
}
