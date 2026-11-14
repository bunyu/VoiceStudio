package com.voicestudio.reader.vieneu

import java.io.Closeable
import java.io.File

/**
 * sea-g2p, the Vietnamese text normaliser and phonemiser VieNeu-TTS was trained with. The model only
 * understands this library's phoneme alphabet, so the app must use the same release as the model.
 * A handle is not thread-safe: callers serialise access.
 */
class SeaG2p(dictionary: File) : Closeable {
    private var handle: Long

    init {
        System.loadLibrary("reader_g2p")
        check(abiVersion() == ABI) { "sea-g2p ABI mismatch" }
        handle = open(dictionary.absolutePath.toByteArray())
        check(handle != 0L) { "sea-g2p dictionary could not be opened" }
    }

    /** Text to the phoneme string the model reads. [closeSentence] applies the library's trailing-punctuation rule. */
    @Synchronized fun phonemize(text: String, closeSentence: Boolean = true): String =
        phonemize(live(), text.toByteArray(), closeSentence)?.toString(Charsets.UTF_8) ?: error("sea-g2p failed")

    /** Spoken-form text (numbers, dates, units and abbreviations written out) without phonemising. */
    @Synchronized fun normalize(text: String, closeSentence: Boolean = false): String =
        normalize(live(), text.toByteArray(), closeSentence)?.toString(Charsets.UTF_8) ?: error("sea-g2p failed")

    @Synchronized override fun close() { if (handle != 0L) { close(handle); handle = 0L } }

    private fun live() = handle.also { check(it != 0L) { "sea-g2p is closed" } }

    private companion object {
        const val ABI = 1
        @JvmStatic external fun abiVersion(): Int
        @JvmStatic external fun open(path: ByteArray): Long
        @JvmStatic external fun close(handle: Long)
        @JvmStatic external fun phonemize(handle: Long, text: ByteArray, closeSentence: Boolean): ByteArray?
        @JvmStatic external fun normalize(handle: Long, text: ByteArray, closeSentence: Boolean): ByteArray?
    }
}
