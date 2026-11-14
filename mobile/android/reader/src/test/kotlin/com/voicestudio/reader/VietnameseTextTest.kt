package com.voicestudio.reader

import com.voicestudio.reader.text.Dialect
import com.voicestudio.reader.text.Pronunciation
import com.voicestudio.reader.text.VietnameseNumbers
import com.voicestudio.reader.text.VietnameseText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VietnameseTextTest {
    private val n = VietnameseNumbers()
    private val vi = VietnameseText()

    @Test fun cardinalsFollowSpokenRules() {
        val expected = mapOf(
            0L to "không", 5L to "năm", 10L to "mười", 11L to "mười một", 14L to "mười bốn", 15L to "mười lăm",
            20L to "hai mươi", 21L to "hai mươi mốt", 24L to "hai mươi tư", 25L to "hai mươi lăm", 101L to "một trăm linh một",
            105L to "một trăm linh năm", 110L to "một trăm mười", 1000L to "một nghìn", 1005L to "một nghìn không trăm linh năm",
            1050L to "một nghìn không trăm năm mươi", 2024L to "hai nghìn không trăm hai mươi tư",
            1945L to "một nghìn chín trăm bốn mươi lăm", 1_000_000L to "một triệu", 1_000_001L to "một triệu không trăm linh một",
            2_500_000L to "hai triệu năm trăm nghìn", 1_000_000_005L to "một tỷ không trăm linh năm",
            3_000_000_000_000L to "ba nghìn tỷ",
        )
        for ((value, words) in expected) assertEquals("$value", words, n.cardinal(value))
    }

    @Test fun southernDialectUsesNganAndLe() {
        assertEquals("một ngàn không trăm lẻ năm", VietnameseNumbers(Dialect.SOUTH).cardinal(1005))
    }

    @Test fun numbersInText() {
        assertEquals("Anh ấy có một triệu đồng.", vi.normalize("Anh ấy có 1.000.000đ."))
        assertEquals("giá hai mươi lăm nghìn đồng", vi.normalize("giá 25.000 VNĐ"))
        assertEquals("tăng ba phẩy năm phần trăm", vi.normalize("tăng 3,5%"))
        assertEquals("số không chín một hai", vi.normalize("số 0912"))
        assertEquals("mười đô la", vi.normalize("$10"))
        assertEquals("cao một phẩy bảy mươi lăm mét", vi.normalize("cao 1,75 m"))
        assertEquals("đi năm ki lô mét", vi.normalize("đi 5km"))
        assertEquals("mười đến mười lăm phút", vi.normalize("10-15 phút"))
    }

    @Test fun datesAndTimes() {
        assertEquals("ngày hai tháng chín năm một nghìn chín trăm bốn mươi lăm", vi.normalize("ngày 2/9/1945"))
        assertEquals("ngày ba mươi tháng tư năm một nghìn chín trăm bảy mươi lăm", vi.normalize("30/4/1975"))
        assertEquals("vào tháng tư, trời mưa", vi.normalize("vào tháng 4, trời mưa"))
        assertEquals("lúc bảy giờ ba mươi phút sáng", vi.normalize("lúc 7h30 sáng"))
        assertEquals("lúc mười giờ", vi.normalize("lúc 10:00"))
        assertEquals("thứ tư và thứ hai, hạng nhất", vi.normalize("thứ 4 và thứ 2, hạng 1"))
    }

    @Test fun abbreviationsAndRomanNumerals() {
        assertEquals("thành phố Hồ Chí Minh và Hà Nội", vi.normalize("TP.HCM và Hà Nội"))
        assertEquals("sách, vở, vân vân", vi.normalize("sách, vở, v.v."))
        assertEquals("giáo sư tiến sĩ Nam", vi.normalize("GS.TS. Nam"))
        assertEquals("Chương bốn: Gặp lại", vi.normalize("Chương IV: Gặp lại"))
        assertEquals("thế kỷ hai mươi", vi.normalize("thế kỷ XX"))
        assertEquals("phần C của bài", vi.normalize("phần C của bài"))
        assertEquals("Tôi không biết", vi.normalize("Tôi ko biết"))
        assertEquals("vua Lê Lợi", vi.normalize("vua Lê Lợi"))
    }

    @Test fun composesToneMarksAndCleansLayout() {
        // "Việt" written with combining marks, as some EPUBs store it.
        val decomposed = "Việt Nam"
        assertEquals("Việt Nam", vi.normalize(decomposed))
        assertEquals("Anh đi đâu?\nKhông biết…", vi.normalize("— Anh đi đâu?\n***\n- Không biết..."))
        assertEquals("Một câu chuyện.", vi.normalize("Một câu chuyện.[3]"))
        assertFalse(vi.normalize("“Xin chào”").contains("“"))
    }

    @Test fun userPronunciationsWin() {
        val custom = VietnameseText(pronunciations = VietnameseText.parsePronunciations("Lâm Phong = Lâm Phông\nAI = ây ai\nbad line"))
        assertEquals("Lâm Phông nói về ây ai", custom.normalize("Lâm Phong nói về AI"))
        assertEquals(listOf(Pronunciation("x", "y")), VietnameseText.parsePronunciations("x=y\n=z\n"))
    }
}
