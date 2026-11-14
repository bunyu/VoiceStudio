package com.voicestudio.reader.vieneu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class DownloadFailed(val reason: String) : Exception(reason)

data class DownloadState(val running: Boolean = false, val received: Long = 0, val total: Long = 0, val error: String? = null)

/**
 * Downloads the pinned release into private storage, resuming interrupted files and checking every hash.
 * Nothing is fetched until the user asks; after that the app reads offline.
 */
class NanoAssets(
    root: File,
    val release: NanoRelease,
    private val unmetered: () -> Boolean,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) {
    val directory = File(root, release.id)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = mutable.asStateFlow()

    fun file(name: String) = File(directory, name)
    private fun marker(group: String) = File(directory, ".verified-$group")

    /** True once every file of [group] was downloaded and verified. Sizes are rechecked; hashes are not. */
    fun installed(group: String = ReleaseFile.SPEECH): Boolean =
        marker(group).isFile && release.files(group).all { file(it.name).length() == it.size }

    suspend fun delete() = mutex.withLock { withContext(Dispatchers.IO) { directory.deleteRecursively(); mutable.value = DownloadState() } }

    /** @throws DownloadFailed with reason `wifi`, `storage`, `network` or `corrupt`. */
    suspend fun download(group: String, allowMobileData: Boolean) = mutex.withLock {
        val files = release.files(group)
        val total = files.sumOf { it.size }
        try {
            withContext(Dispatchers.IO) {
                directory.mkdirs()
                var done = files.filter { file(it.name).length() == it.size && marker(group).isFile }.sumOf { it.size }
                mutable.value = DownloadState(running = true, received = done, total = total)
                if (done < total) {
                    if (!allowMobileData && !unmetered()) throw DownloadFailed("wifi")
                    if (freeBytes(directory) < total - done + 64L * 1024 * 1024) throw DownloadFailed("storage")
                }
                marker(group).delete()
                done = 0
                for (item in files) {
                    fetch(item) { extra -> mutable.value = DownloadState(running = true, received = done + extra, total = total) }
                    done += item.size
                }
                marker(group).writeText(release.id)
            }
            mutable.value = DownloadState(received = total, total = total)
        } catch (e: DownloadFailed) {
            mutable.value = mutable.value.copy(running = false, error = e.reason); throw e
        } catch (e: IOException) {
            mutable.value = mutable.value.copy(running = false, error = "network"); throw DownloadFailed("network")
        } catch (e: Throwable) {
            mutable.value = mutable.value.copy(running = false); throw e
        }
    }

    private suspend fun fetch(item: ReleaseFile, progress: (Long) -> Unit) {
        val target = file(item.name)
        if (target.length() == item.size && hash(target) == item.sha256) { progress(item.size); return }
        target.delete()
        val part = File(directory, item.name + ".part")
        if (part.length() > item.size) part.delete()
        if (part.length() < item.size) {
            val connection = open(item.url)
            try {
                connection.connectTimeout = 20_000; connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                val offset = part.length()
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val code = connection.responseCode
                if (code != 200 && code != 206) throw IOException("HTTP $code")
                RandomAccessFile(part, "rw").use { out ->
                    // A server that ignores the range sends the whole file again.
                    if (code == 200) out.setLength(0) else out.seek(offset)
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            if (out.filePointer + n > item.size) throw DownloadFailed("corrupt")
                            out.write(buffer, 0, n)
                            progress(out.filePointer)
                        }
                    }
                }
            } finally { connection.disconnect() }
        }
        if (part.length() != item.size || hash(part) != item.sha256) { part.delete(); throw DownloadFailed("corrupt") }
        if (!part.renameTo(target)) throw IOException("rename")
        progress(item.size)
    }

    private suspend fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) { coroutineContext.ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
