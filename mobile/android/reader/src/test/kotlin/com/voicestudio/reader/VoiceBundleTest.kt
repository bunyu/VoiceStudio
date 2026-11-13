package com.voicestudio.reader

import com.voicestudio.reader.voice.VoiceBundle
import com.voicestudio.reader.voice.VoiceBundleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VoiceBundleTest {
    @get:Rule val temp = TemporaryFolder()

    private fun zip(vararg members: Pair<String, ByteArray>): File = temp.newFile("voice-${members.size}-${System.nanoTime()}.ovsvoice").also { file ->
        ZipOutputStream(file.outputStream()).use { out -> for ((name, data) in members) { out.putNextEntry(ZipEntry(name)); out.write(data); out.closeEntry() } }
    }

    @Test fun readsPersonaBundleFromDesktopExport() {
        val manifest = """{"format":"ovsvoice","schema_version":1,"persona":{"name":"Giọng của Phúc","kind":"clone","language":"Vietnamese","ref_text":"Xin chào các bạn.","is_locked":false}}"""
        val file = zip("manifest.json" to manifest.toByteArray(), "metadata.json" to """{"bundle_version":1,"profile_name":"old"}""".toByteArray(),
            "ref_audio.wav" to byteArrayOf(1, 2, 3), "preview.wav" to byteArrayOf(4))
        val voice = VoiceBundle.read(file, temp.newFolder())
        assertEquals("Giọng của Phúc", voice.name)
        assertEquals("Xin chào các bạn.", voice.transcript)
        assertEquals("Vietnamese", voice.language)
        assertFalse(voice.designed)
        assertEquals(listOf<Byte>(1, 2, 3), voice.audio.readBytes().toList())
        assertTrue(voice.preview!!.isFile)
    }

    @Test fun lockedVoiceUsesItsLockedTake() {
        val file = zip("metadata.json" to """{"bundle_version":1,"profile_name":"Kể chuyện","ref_text":"Câu đã khoá.","is_locked":true,"kind":"design"}""".toByteArray(),
            "ref_audio.wav" to byteArrayOf(1), "locked_audio.flac" to byteArrayOf(9, 9))
        val voice = VoiceBundle.read(file, temp.newFolder())
        assertEquals("flac", voice.audio.extension)
        assertTrue(voice.designed)
        assertEquals("Câu đã khoá.", voice.transcript)
    }

    @Test fun rejectsArchivesWithoutVoiceData() {
        val noAudio = zip("metadata.json" to """{"bundle_version":1,"profile_name":"x"}""".toByteArray(), "../ref_audio.wav" to byteArrayOf(1))
        assertEquals(VoiceBundleException.Reason.NO_AUDIO, runCatching { VoiceBundle.read(noAudio, temp.newFolder()) }.exceptionOrNull().let { (it as VoiceBundleException).reason })
        val notZip = temp.newFile("plain.ovsvoice").apply { writeText("hello") }
        assertEquals(VoiceBundleException.Reason.NOT_A_BUNDLE, (runCatching { VoiceBundle.read(notZip, temp.newFolder()) }.exceptionOrNull() as VoiceBundleException).reason)
    }
}
