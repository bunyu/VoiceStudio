package com.voicestudio.reader

import com.voicestudio.reader.vieneu.SeaG2p
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs only where a host build of the bridge and the dictionary are available:
 * `cargo build --release` in `native/g2p`, then set READER_G2P_DICT and add the library folder to
 * `java.library.path`. Device builds cannot load an Android library in a JVM test.
 */
class SeaG2pTest {
    private fun open(): SeaG2p {
        val dictionary = System.getenv("READER_G2P_DICT")?.let(::File)
        assumeTrue(dictionary?.isFile == true)
        return SeaG2p(dictionary!!)
    }

    @Test fun phonemizesVietnameseAndWritesOutNumbers() = open().use { g2p ->
        val spoken = g2p.normalize("Năm 2024, giá tăng 3,5%.")
        assertFalse(spoken, spoken.any { it.isDigit() })
        assertTrue(spoken, "phần trăm" in spoken)
        val phones = g2p.phonemize("Xin chào các bạn, hôm nay trời đẹp quá.")
        assertTrue(phones, phones.isNotBlank() && phones.trimEnd().endsWith("."))
        assertEquals(phones, g2p.phonemize("Xin chào các bạn, hôm nay trời đẹp quá."))
    }

    @Test fun survivesEmojiAndEmbeddedNul() = open().use { g2p ->
        assertTrue(g2p.phonemize("Chào 😀 bạn\u0000 nhé").isNotBlank())
    }
}
