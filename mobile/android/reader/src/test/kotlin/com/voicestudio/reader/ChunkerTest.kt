package com.voicestudio.reader

import com.voicestudio.reader.text.BookText
import com.voicestudio.reader.text.Chunker
import com.voicestudio.reader.text.Pause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {
    @Test fun sentencesDoNotBreakOnAbbreviationsOrDecimals() {
        val text = "GS. Nam đến TP. Huế lúc 7h. Giá là 3.5 triệu. Anh hỏi: \"Thật sao?\" Cô gật đầu."
        assertEquals(listOf("GS. Nam đến TP. Huế lúc 7h.", "Giá là 3.5 triệu.", "Anh hỏi: \"Thật sao?\"", "Cô gật đầu."), Chunker.sentences(text))
    }

    @Test fun piecesStayWithinLimitAndMarkPauses() {
        val paragraph = (1..30).joinToString(" ") { "Đây là câu thứ $it trong đoạn văn dài." }
        val pieces = Chunker.split(paragraph + "\n\nĐoạn hai ngắn.", maxChars = 200)
        assertTrue(pieces.all { it.text.length <= 200 })
        assertEquals(Pause.PARAGRAPH, pieces[pieces.size - 2].pause)
        assertEquals(Pause.CHAPTER, pieces.last().pause)
        assertEquals("Đoạn hai ngắn.", pieces.last().text)
        // Nothing is lost or reordered.
        assertEquals((paragraph + " Đoạn hai ngắn.").replace(" ", ""), pieces.joinToString("") { it.text.replace(" ", "") })
    }

    @Test fun longSentencesBreakAtClausesNotInsideWords() {
        val sentence = (1..40).joinToString(", ") { "mệnh đề số $it" } + "."
        val parts = Chunker.fit(sentence, 120)
        assertTrue(parts.all { it.length <= 120 })
        assertTrue(parts.dropLast(1).all { it.endsWith(",") })
    }

    @Test fun importedBookSplitsIntoChapters() {
        val imported = "# Chương 1\nMở đầu.\n# không phải tiêu đề\n\n# Chương 2\nTiếp theo.\n\n"
        val chapters = BookText.chapters(imported)
        assertEquals(listOf("Chương 1", "Chương 2"), chapters.map { it.title })
        assertTrue(chapters[0].text.contains("# không phải tiêu đề"))
    }
}
