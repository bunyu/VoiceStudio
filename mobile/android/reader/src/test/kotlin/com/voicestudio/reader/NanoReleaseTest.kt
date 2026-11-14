package com.voicestudio.reader

import com.voicestudio.reader.vieneu.DownloadFailed
import com.voicestudio.reader.vieneu.DownloadState
import com.voicestudio.reader.vieneu.NanoAssets
import com.voicestudio.reader.vieneu.NanoEngine
import com.voicestudio.reader.vieneu.NanoRelease
import com.voicestudio.reader.vieneu.ReleaseFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class NanoReleaseTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun shipped() = NanoRelease.decode(listOf("src/main/assets/vieneu-release.json", "reader/src/main/assets/vieneu-release.json").map(::File).first { it.isFile }.readText())

    @Test fun shippedReleaseIsPinnedAndComplete() {
        val release = shipped()
        val speech = release.files(ReleaseFile.SPEECH).map { it.name }
        for (needed in NanoEngine.FILES + listOf("sea_g2p.bin", "voices_v3_nano.json")) assertTrue(needed, needed in speech)
        assertEquals(setOf("speaker_encoder.onnx", "codec_encoder.onnx", "reference_encoder.onnx"), release.files(ReleaseFile.CLONE).map { it.name }.toSet())
        assertEquals("Apache-2.0", release.licence)
    }

    @Test fun rejectsUnpinnedOrForeignSources() {
        val good = shipped()
        fun with(url: String) = """{"id":"x","model":"m","licence":"l","credit":"c","source_url":"s","files":[{"name":"a.onnx","url":"$url","size":1,"sha256":"${"0".repeat(64)}"}]}"""
        NanoRelease.decode(with(good.files.first().url))
        for (bad in listOf(
            "https://huggingface.co/pnnbao-ump/VieNeu-TTS-v3-Nano/resolve/main/text_encoder.onnx",
            "http://huggingface.co/a/b/resolve/${"a".repeat(40)}/x.onnx",
            "https://example.com/a/b/resolve/${"a".repeat(40)}/x.onnx",
            "https://github.com/pnnbao97/sea-g2p/releases/latest/download/sea_g2p.bin",
            "https://raw.githubusercontent.com/pnnbao97/VieNeu-TTS/main/x.json",
        )) assertThrows(bad, IllegalArgumentException::class.java) { NanoRelease.decode(with(bad)) }
    }

    private class Served(private val body: ByteArray, private val honourRange: Boolean = true) : HttpURLConnection(URL("https://huggingface.co/")) {
        private var from = 0
        override fun setRequestProperty(key: String, value: String) { if (key == "Range" && honourRange) from = value.removePrefix("bytes=").removeSuffix("-").toInt() }
        override fun getResponseCode() = if (from > 0) 206 else 200
        override fun getInputStream() = ByteArrayInputStream(body, from, body.size - from)
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun release(body: ByteArray, hash: String = sha(body)) = NanoRelease("test", "m", "l", "c", "s",
        listOf(ReleaseFile("a.onnx", "https://huggingface.co/o/r/resolve/${"a".repeat(40)}/a.onnx", body.size.toLong(), hash)))

    @Test fun downloadsResumesAndVerifies() = runBlocking {
        val body = ByteArray(200_000) { (it * 31).toByte() }
        var requests = 0
        val assets = NanoAssets(temporary.root, release(body), unmetered = { true }, open = { requests++; Served(body) })
        File(assets.directory, "a.onnx.part").apply { parentFile!!.mkdirs(); writeBytes(body.copyOf(70_000)) }
        assertFalse(assets.installed())
        assets.download(ReleaseFile.SPEECH, allowMobileData = false)
        assertTrue(assets.installed())
        assertTrue(assets.file("a.onnx").readBytes().contentEquals(body))
        assertEquals(DownloadState(received = 200_000, total = 200_000), assets.state.value)
        // Installed files are verified again without the network.
        assets.download(ReleaseFile.SPEECH, allowMobileData = false)
        assertEquals(1, requests)
        assets.delete()
        assertFalse(assets.installed())
    }

    @Test fun refusesWrongContentMeteredNetworksAndFullStorage() = runBlocking {
        val body = ByteArray(1000) { 1 }
        val corrupt = NanoAssets(temporary.newFolder(), release(body, "0".repeat(64)), unmetered = { true }, open = { Served(body) })
        assertEquals("corrupt", runCatching { corrupt.download(ReleaseFile.SPEECH, false) }.exceptionOrNull().let { (it as DownloadFailed).reason })
        assertFalse(corrupt.installed())
        assertFalse(File(corrupt.directory, "a.onnx.part").exists())
        val metered = NanoAssets(temporary.newFolder(), release(body), unmetered = { false }, open = { Served(body) })
        assertEquals("wifi", (runCatching { metered.download(ReleaseFile.SPEECH, false) }.exceptionOrNull() as DownloadFailed).reason)
        metered.download(ReleaseFile.SPEECH, allowMobileData = true)
        assertTrue(metered.installed())
        val full = NanoAssets(temporary.newFolder(), release(body), unmetered = { true }, freeBytes = { 10 }, open = { Served(body) })
        assertEquals("storage", (runCatching { full.download(ReleaseFile.SPEECH, false) }.exceptionOrNull() as DownloadFailed).reason)
        assertEquals("storage", full.state.value.error)
    }
}
