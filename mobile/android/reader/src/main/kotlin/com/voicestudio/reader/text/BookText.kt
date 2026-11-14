package com.voicestudio.reader.text

import java.io.File
import java.util.zip.ZipFile

data class ChapterText(val title: String, val text: String)

object BookText {
    /**
     * VoiceStudio's EPUB import yields "# Title\nbody\n\n" per spine document. This splits it back into
     * chapters; a body line that happens to start with "# " stays in its chapter unless a blank line precedes it.
     */
    fun chapters(imported: String): List<ChapterText> {
        val result = mutableListOf<ChapterText>()
        var title: String? = null
        val body = StringBuilder()
        var previousBlank = true
        fun flush() {
            val text = body.toString().trim()
            if (title != null && text.isNotEmpty()) result += ChapterText(title!!, text)
            else if (title == null && text.isNotEmpty()) result += ChapterText("", text)
            body.clear()
        }
        for (line in imported.lines()) {
            if (line.startsWith("# ") && previousBlank) {
                flush()
                title = line.removePrefix("# ").trim()
            } else {
                body.append(line).append('\n')
            }
            previousBlank = line.isBlank()
        }
        flush()
        return result.mapIndexed { index, chapter -> if (chapter.title.isBlank()) chapter.copy(title = (index + 1).toString()) else chapter }
    }

    /** Title and author from the EPUB package document; null when the file does not say. */
    fun epubMetadata(source: File): Pair<String?, String?> = runCatching {
        ZipFile(source).use { zip ->
            fun read(name: String): String? = zip.getEntry(name)?.takeIf { it.size in 0..1_048_576 }?.let { entry ->
                zip.getInputStream(entry).use { it.readBounded(1_048_576) }?.toString(Charsets.UTF_8)
            }
            val container = read("META-INF/container.xml") ?: return@use null to null
            val opfPath = Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: return@use null to null
            val opf = read(opfPath) ?: return@use null to null
            fun tag(name: String) = Regex("<(?:dc:)?$name\\b[^>]*>([\\s\\S]*?)</(?:dc:)?$name>").find(opf)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 300 }
            tag("title") to tag("creator")
        }
    }.getOrDefault(null to null)

    private fun unescape(value: String) = value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")
}

/** Reads at most [limit] bytes; null when the stream holds more. (InputStream.readNBytes needs API 33.) */
internal fun java.io.InputStream.readBounded(limit: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(65536)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        if (out.size() + n > limit) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
