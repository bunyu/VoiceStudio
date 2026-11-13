package com.voicestudio.reader.text

/** Regional readings that change how numbers sound; everything else is shared. */
enum class Dialect { NORTH, SOUTH }

/** Spoken Vietnamese for numbers, following the standard reading rules (mốt, lăm, tư, linh/lẻ). */
class VietnameseNumbers(private val dialect: Dialect = Dialect.NORTH) {
    private val digits = listOf("không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín")
    private val thousand get() = if (dialect == Dialect.NORTH) "nghìn" else "ngàn"
    private val zeroTens get() = if (dialect == Dialect.NORTH) "linh" else "lẻ"

    fun digit(d: Int): String = digits[d]

    /** Reads every digit on its own: phone numbers, codes, long runs and leading zeros. */
    fun spellDigits(value: String): String = value.filter { it.isDigit() }.map { digits[it - '0'] }.joinToString(" ")

    /** Cardinal reading of a non-negative whole number. */
    fun cardinal(value: Long): String {
        require(value >= 0)
        if (value == 0L) return digits[0]
        return scaled(value, leading = true).trim()
    }

    /** Cardinal reading of a digit string, falling back to digit-by-digit when it is too long to read as an amount. */
    fun cardinal(value: String): String {
        val clean = value.filter { it.isDigit() }
        if (clean.isEmpty()) return value
        if (clean.length > 15) return spellDigits(clean)
        return cardinal(clean.toLong())
    }

    private fun scaled(value: Long, leading: Boolean): String {
        val billion = 1_000_000_000L
        if (value >= billion) {
            val high = value / billion
            val rest = value % billion
            return cardinal(high) + " tỷ" + if (rest == 0L) "" else " " + scaled(rest, leading = false)
        }
        val groups = listOf(value / 1_000_000 to "triệu", (value / 1000) % 1000 to thousand, value % 1000 to "")
        val parts = mutableListOf<String>()
        var started = !leading
        for ((index, pair) in groups.withIndex()) {
            val (group, unit) = pair
            val remaining = groups.drop(index + 1).sumOf { it.first }
            if (group == 0L) continue
            val words = hundreds(group.toInt(), full = started)
            parts += if (unit.isEmpty()) words else "$words $unit"
            started = true
            if (remaining == 0L) break
        }
        return parts.joinToString(" ")
    }

    /** One three-digit group; [full] adds "không trăm" when an earlier group was read (1005 → một nghìn không trăm linh năm). */
    private fun hundreds(value: Int, full: Boolean): String {
        val h = value / 100
        val t = (value / 10) % 10
        val u = value % 10
        val out = mutableListOf<String>()
        val hasHundreds = h > 0 || full
        if (hasHundreds) out += digits[h] + " trăm"
        when {
            t == 0 && u == 0 -> Unit
            t == 0 -> { if (hasHundreds) out += zeroTens; out += digits[u] }
            t == 1 -> { out += "mười"; if (u > 0) out += units(u, afterTen = true) }
            else -> { out += digits[t] + " mươi"; if (u > 0) out += units(u, afterTen = false) }
        }
        return out.joinToString(" ")
    }

    private fun units(u: Int, afterTen: Boolean): String = when {
        u == 5 -> "lăm"
        u == 1 && !afterTen -> "mốt"
        u == 4 && !afterTen -> "tư"
        else -> digits[u]
    }

    /** Fraction digits: short parts read as a number ("3,25" → ba phẩy hai mươi lăm), others digit by digit. */
    fun decimal(whole: String, fraction: String, separatorWord: String): String {
        val frac = if (fraction.length <= 2 && !fraction.startsWith("0")) cardinal(fraction) else spellDigits(fraction)
        return cardinal(whole) + " " + separatorWord + " " + frac
    }

    /** "thứ 1" → thứ nhất, "thứ 4" → thứ tư; other ordinals read as cardinals. */
    fun ordinal(value: Long): String = when (value) {
        1L -> "nhất"
        4L -> "tư"
        else -> cardinal(value)
    }

    /** Month names: tháng 4 is "tháng tư"; others are cardinals. */
    fun month(value: Int): String = if (value == 4) "tư" else cardinal(value.toLong())
}
