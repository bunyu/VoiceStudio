package com.voicestudio.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.voicestudio.mobile.local.BookImport
import com.voicestudio.reader.text.BookText
import com.voicestudio.reader.text.Chunker
import com.voicestudio.reader.text.Pause
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable data class StoredChapter(val title: String, val text: String)
@Serializable data class StoredBook(val id: String, val title: String, val author: String? = null, val chapters: List<StoredChapter>)
@Serializable data class BookSummary(val id: String, val title: String, val author: String? = null, val addedAt: Long, val chapterTitles: List<String>,
    val chapter: Int = 0, val piece: Int = 0, val totalPieces: Int = 0, val openedAt: Long = 0)

/** One spoken piece of a book; [index] runs through the whole book so playback crosses chapters seamlessly. */
data class BookPiece(val index: Int, val chapter: Int, val text: String, val pause: Pause)
class OpenBook(val book: StoredBook, val pieces: List<BookPiece>) {
    val chapterStarts: List<Int> = book.chapters.indices.map { c -> pieces.indexOfFirst { it.chapter == c }.let { if (it < 0) pieces.size else it } }
    fun piecesOf(chapter: Int) = pieces.filter { it.chapter == chapter }
}

/** Books live only in app-private storage. The original file is parsed once on import and kept as text. */
class Library(private val context: Context) {
    private val root = File(context.filesDir, "books").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<BookSummary>>(emptyList())
    val books: StateFlow<List<BookSummary>> = state.asStateFlow()

    private fun dir(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        return File(root, id)
    }
    fun audioDir(id: String) = File(dir(id), "audio")

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            state.value = root.listFiles().orEmpty().mapNotNull { folder ->
                runCatching { json.decodeFromString<BookSummary>(File(folder, "summary.json").readText()) }.getOrNull()
            }.sortedByDescending { maxOf(it.openedAt, it.addedAt) }
        }
    }

    /** Imports an EPUB, PDF or plain-text file chosen by the user. */
    suspend fun import(uri: Uri): BookSummary = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "book.epub"
        val extension = name.substringAfterLast('.', "epub").lowercase().takeIf { it in setOf("epub", "pdf", "txt", "md") } ?: "epub"
        val id = UUID.randomUUID().toString()
        val folder = dir(id).apply { mkdirs() }
        try {
            val source = File(folder, "source.$extension")
            (context.contentResolver.openInputStream(uri) ?: error("unreadable")).use { input ->
                source.outputStream().use { out ->
                    val buffer = ByteArray(262_144); var total = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 67_108_864L); out.write(buffer, 0, n) }
                }
            }
            val text = BookImport.read(source, context)
            val chapters = BookText.chapters(if (extension == "epub") text else "# ${name.substringBeforeLast('.')}\n$text").map { StoredChapter(it.title, it.text) }
            require(chapters.isNotEmpty())
            val (title, author) = if (extension == "epub") BookText.epubMetadata(source) else null to null
            val book = StoredBook(id, title ?: name.substringBeforeLast('.'), author, chapters)
            atomic(File(folder, "book.json"), json.encodeToString(StoredBook.serializer(), book))
            val summary = BookSummary(id, book.title, author, System.currentTimeMillis(), chapters.map { it.title }, totalPieces = pieces(book).size)
            atomic(File(folder, "summary.json"), json.encodeToString(BookSummary.serializer(), summary))
            source.delete()
            refresh(); summary
        } catch (e: Throwable) { folder.deleteRecursively(); throw e }
    }

    suspend fun open(id: String): OpenBook = withContext(Dispatchers.IO) {
        val book = json.decodeFromString<StoredBook>(File(dir(id), "book.json").readText())
        OpenBook(book, pieces(book))
    }

    private fun pieces(book: StoredBook): List<BookPiece> {
        val result = mutableListOf<BookPiece>()
        book.chapters.forEachIndexed { c, chapter ->
            // The title is read first, as a narrator would announce the chapter.
            val title = chapter.title.takeIf { it.isNotBlank() && !it.all(Char::isDigit) && !chapter.text.startsWith(it) }
            if (title != null) result += BookPiece(result.size, c, title, Pause.PARAGRAPH)
            for (piece in Chunker.split(chapter.text)) result += BookPiece(result.size, c, piece.text, piece.pause)
        }
        return result
    }

    suspend fun savePosition(id: String, chapter: Int, piece: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = File(dir(id), "summary.json")
            val current = runCatching { json.decodeFromString<BookSummary>(file.readText()) }.getOrNull() ?: return@withLock
            atomic(file, json.encodeToString(BookSummary.serializer(), current.copy(chapter = chapter, piece = piece, openedAt = System.currentTimeMillis())))
            state.value = state.value.map { if (it.id == id) current.copy(chapter = chapter, piece = piece, openedAt = System.currentTimeMillis()) else it }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { dir(id).deleteRecursively(); refresh() }
    suspend fun clearAudio(id: String) = withContext(Dispatchers.IO) { audioDir(id).deleteRecursively() }
    fun audioBytes(id: String): Long = audioDir(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun atomic(file: File, text: String) {
        val pending = File(file.parentFile, file.name + ".pending")
        pending.outputStream().use { it.write(text.toByteArray()); it.fd.sync() }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
