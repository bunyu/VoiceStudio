package com.voicestudio.reader.text

import java.text.Normalizer

/** One user pronunciation fix: every whole-word [written] is read as [spoken]. */
data class Pronunciation(val written: String, val spoken: String)

/**
 * Turns novel text into what a Vietnamese narrator would say. Speech models read digits, symbols and
 * abbreviations badly (or in English), so numbers, dates, times, money, units, Roman numerals and common
 * abbreviations become words. Unicode is composed (NFC) first: many EPUBs store tone marks as combining
 * characters, which the models treat as unknown symbols.
 */
class VietnameseText(dialect: Dialect = Dialect.NORTH, pronunciations: List<Pronunciation> = emptyList()) {
    companion object {
        /** Part of every rendered-audio cache key; bump when the spoken output of any rule changes. */
        const val VERSION = 1
        private const val LETTER = "\\p{L}\\p{N}_"
        private fun word(pattern: String) = Regex("(?<![$LETTER])(?:$pattern)(?![$LETTER])")

        fun parsePronunciations(text: String): List<Pronunciation> = text.lines().mapNotNull { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val written = parts[0].trim()
            val spoken = parts[1].trim()
            if (written.isEmpty() || spoken.isEmpty() || written.length > 100 || spoken.length > 300) null else Pronunciation(written, spoken)
        }

        /** Fixed abbreviations, case-sensitive and longest first so "TP.HCM" wins over "TP.". */
        private val ABBREVIATIONS: List<Pair<String, String>> = listOf(
            "TP.HCM" to "thành phố Hồ Chí Minh", "TP. HCM" to "thành phố Hồ Chí Minh", "TPHCM" to "thành phố Hồ Chí Minh",
            "TP.HN" to "thành phố Hà Nội", "TP." to "thành phố", "Tp." to "thành phố",
            "v.v..." to "vân vân", "v.v…" to "vân vân", "v.v." to "vân vân", "v.v" to "vân vân",
            "PGS.TS." to "phó giáo sư tiến sĩ", "PGS.TS" to "phó giáo sư tiến sĩ", "GS.TS." to "giáo sư tiến sĩ", "GS.TS" to "giáo sư tiến sĩ",
            "PGS." to "phó giáo sư", "GS." to "giáo sư", "TS." to "tiến sĩ", "ThS." to "thạc sĩ", "BS." to "bác sĩ", "KS." to "kỹ sư",
            "UBND" to "ủy ban nhân dân", "HĐND" to "hội đồng nhân dân", "THPT" to "trung học phổ thông", "THCS" to "trung học cơ sở",
            "ĐH" to "đại học", "NXB" to "nhà xuất bản", "CLB" to "câu lạc bộ", "LHQ" to "Liên Hợp Quốc", "CSGT" to "cảnh sát giao thông",
            "VN" to "Việt Nam", "TQ" to "Trung Quốc", "BCH" to "ban chấp hành", "HLV" to "huấn luyện viên",
            "ko" to "không", "hok" to "không", "đc" to "được",
        ).sortedByDescending { it.first.length }

        private val UNITS: List<Pair<String, String>> = listOf(
            "km/h" to "ki lô mét trên giờ", "m/s" to "mét trên giây", "km²" to "ki lô mét vuông", "km2" to "ki lô mét vuông",
            "m²" to "mét vuông", "m2" to "mét vuông", "m³" to "mét khối", "m3" to "mét khối", "cm" to "xen ti mét", "mm" to "mi li mét",
            "km" to "ki lô mét", "kg" to "ki lô gam", "mg" to "mi li gam", "ml" to "mi li lít", "ha" to "héc ta", "°C" to "độ C",
            "°F" to "độ F", "°" to "độ", "kW" to "ki lô oát", "kWh" to "ki lô oát giờ", "MB" to "mê ga bai", "GB" to "gi ga bai",
            "m" to "mét", "g" to "gam", "l" to "lít", "t" to "tấn", "tr" to "triệu", "k" to "nghìn",
        ).sortedByDescending { it.first.length }

        private val ROMAN_CONTEXT = "chương|phần|hồi|quyển|tập|thế kỷ|thế kỉ|khóa|khoá|đệ|vua|giáo hoàng|tiết|bài|mục|kỳ|kì"
        private val ROMAN = "M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})"
    }

    private val numbers = VietnameseNumbers(dialect)
    private val custom = pronunciations.sortedByDescending { it.written.length }.map { word(Regex.escape(it.written)) to it.spoken }
    private val abbreviations = ABBREVIATIONS.map { (written, spoken) ->
        // A trailing dot is part of the abbreviation, so only the start needs a word boundary.
        val tail = if (written.last().isLetterOrDigit()) "(?![$LETTER])" else ""
        Regex("(?<![$LETTER])" + Regex.escape(written) + tail) to spoken
    }

    // A number: Vietnamese grouping (1.000.000), English grouping (1,000,000), or a plain run with an optional decimal part.
    private val num = "\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?|\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:[.,]\\d+)?"

    fun normalize(input: String): String {
        var text = Normalizer.normalize(input, Normalizer.Form.NFC)
        text = cleanTypography(text)
        for ((regex, spoken) in custom) text = regex.replace(text) { spoken }
        text = romanNumerals(text)
        text = dates(text)
        text = times(text)
        text = money(text)
        text = percentAndUnits(text)
        for ((regex, spoken) in abbreviations) text = regex.replace(text) { spoken }
        text = ordinals(text)
        text = ranges(text)
        text = symbols(text)
        text = Regex(num).replace(text) { readNumber(it.value) }
        return text.replace(Regex("[ \\t]+"), " ").replace(Regex(" *\\n[\\s]*"), "\n").trim()
    }

    private fun cleanTypography(source: String): String {
        var text = source
            .replace('\u00A0', ' ').replace('\u2009', ' ').replace('\u202F', ' ')
            .replace(Regex("[\u200B\u200C\u200D\uFEFF\u00AD]"), "")
            .replace(Regex("[\u201C\u201D\u201E\u00AB\u00BB]"), "\"").replace(Regex("[\u2018\u2019\u201A]"), "'")
            .replace(Regex("\\.{3,}|…+"), "…")
        // Footnote markers such as [1] or (12) right after a word, and superscript digits.
        text = text.replace(Regex("(?<=\\p{L}|[.,;:!?\"'])\\[\\d{1,3}]"), "").replace(Regex("(?<=[\\p{L}&&[^mM]]|[.,;:!?\"'])[\u00B9\u00B2\u00B3\u2070-\u2079]+(?![\\p{L}])"), "")
        // Dialogue dashes and scene-break lines are layout, not speech.
        text = text.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.matches(Regex("[*#=~_\\-–—•·.\\s]{3,}")) -> ""
                else -> trimmed.replace(Regex("^[-–—]+\\s*"), "")
            }
        }
        return text
    }

    private fun romanNumerals(source: String): String {
        val contextual = Regex("(?i)(?<![$LETTER])($ROMAN_CONTEXT)\\s+($ROMAN)(?![$LETTER])")
        var text = contextual.replace(source) { m ->
            val roman = m.groupValues[2]
            val value = roman(roman)
            val plausible = roman.length >= 2 || roman in setOf("I", "V", "X")
            if (value == null || !plausible || roman != roman.uppercase()) m.value else m.groupValues[1] + " " + numbers.cardinal(value.toLong())
        }
        // A heading line that is only a Roman numeral ("IV." or "XII").
        text = Regex("(?m)^($ROMAN)\\.?$").replace(text) { m -> roman(m.groupValues[1])?.let { numbers.cardinal(it.toLong()) } ?: m.value }
        return text
    }

    private fun roman(value: String): Int? {
        if (value.isEmpty() || !value.matches(Regex(ROMAN))) return null
        val map = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        for (i in value.indices) {
            val current = map.getValue(value[i])
            val next = value.getOrNull(i + 1)?.let { map.getValue(it) } ?: 0
            total += if (current < next) -current else current
        }
        return total.takeIf { it > 0 }
    }

    private fun dates(source: String): String {
        fun day(d: String) = numbers.cardinal(d.toLong())
        fun valid(d: Int, m: Int) = d in 1..31 && m in 1..12
        var text = Regex("(?<![\\d/.-])(ngày\\s+)?(\\d{1,2})([/.-])(\\d{1,2})\\3(\\d{4})(?![\\d/])").replace(source) { m ->
            val (prefix, d, _, mo, y) = m.destructured
            if (!valid(d.toInt(), mo.toInt())) m.value
            else (if (prefix.isEmpty()) "ngày " else prefix) + day(d) + " tháng " + numbers.month(mo.toInt()) + " năm " + numbers.cardinal(y.toLong())
        }
        // "ngày 2/9" needs the word before it, or fractions would turn into dates.
        text = Regex("(ngày\\s+)(\\d{1,2})/(\\d{1,2})(?![\\d/])").replace(text) { m ->
            val (prefix, d, mo) = m.destructured
            if (!valid(d.toInt(), mo.toInt())) m.value else prefix + day(d) + " tháng " + numbers.month(mo.toInt())
        }
        text = Regex("(tháng\\s+)(\\d{1,2})[/.-](\\d{4})(?!\\d)").replace(text) { m ->
            val (prefix, mo, y) = m.destructured
            if (mo.toInt() !in 1..12) m.value else prefix + numbers.month(mo.toInt()) + " năm " + numbers.cardinal(y.toLong())
        }
        text = Regex("(?i)(tháng\\s+)(\\d{1,2})(?![\\d/]|[.,]\\d)").replace(text) { m ->
            val mo = m.groupValues[2].toInt()
            if (mo !in 1..12) m.value else m.groupValues[1] + numbers.month(mo)
        }
        return text
    }

    private fun times(source: String): String {
        var text = Regex("(?<![\\d:])(\\d{1,2}):(\\d{2}):(\\d{2})(?![\\d:])").replace(source) { m ->
            val (h, mi, s) = m.destructured
            if (h.toInt() > 23 || mi.toInt() > 59 || s.toInt() > 59) m.value
            else numbers.cardinal(h.toLong()) + " giờ " + numbers.cardinal(mi.toLong()) + " phút " + numbers.cardinal(s.toLong()) + " giây"
        }
        text = Regex("(?<![\\d:])(\\d{1,2}):(\\d{2})(?![\\d:])").replace(text) { m ->
            val (h, mi) = m.destructured
            if (h.toInt() > 23 || mi.toInt() > 59) m.value else clock(h, mi)
        }
        // 7h, 7h30, 7g30, 7h30p, 7h30' — Vietnamese shorthand for clock times.
        text = Regex("(?<![$LETTER])(\\d{1,2})[hg](\\d{2})?(?:p|ph|')?(?![$LETTER])").replace(text) { m ->
            val h = m.groupValues[1]
            val mi = m.groupValues[2]
            if (h.toInt() > 24 || (mi.isNotEmpty() && mi.toInt() > 59)) m.value else clock(h, mi)
        }
        return text
    }

    private fun clock(h: String, mi: String): String =
        numbers.cardinal(h.toLong()) + " giờ" + if (mi.isEmpty() || mi.toInt() == 0) "" else " " + numbers.cardinal(mi.toLong()) + " phút"

    private fun money(source: String): String {
        var text = Regex("(?<![$LETTER])($num)\\s?(VNĐ|VND|vnđ|vnd|đồng|đ)(?![$LETTER])").replace(source) { m ->
            readNumber(m.groupValues[1]) + " đồng"
        }
        val foreign = listOf("\\$" to "đô la", "US\\$" to "đô la Mỹ", "€" to "ơ rô", "£" to "bảng Anh", "¥" to "yên")
        for ((symbol, spoken) in foreign) {
            text = Regex("(?<![$LETTER])$symbol\\s?($num)").replace(text) { m -> readNumber(m.groupValues[1]) + " " + spoken }
            text = Regex("($num)\\s?$symbol").replace(text) { m -> readNumber(m.groupValues[1]) + " " + spoken }
        }
        text = Regex("($num)\\s?(USD|EUR)(?![$LETTER])").replace(text) { m ->
            readNumber(m.groupValues[1]) + if (m.groupValues[2] == "USD") " đô la Mỹ" else " ơ rô"
        }
        return text
    }

    private fun percentAndUnits(source: String): String {
        var text = Regex("($num)\\s?%").replace(source) { m -> readNumber(m.groupValues[1]) + " phần trăm" }
        val alternatives = UNITS.joinToString("|") { Regex.escape(it.first) }
        val spoken = UNITS.toMap()
        text = Regex("(?<![$LETTER.,])($num)\\s?($alternatives)(?![$LETTER²³])").replace(text) { m ->
            readNumber(m.groupValues[1]) + " " + spoken.getValue(m.groupValues[2])
        }
        return text
    }

    private fun ordinals(source: String): String =
        Regex("(?i)(?<![$LETTER])(thứ|hạng)\\s+(\\d{1,3})(?![\\d:/]|[.,]\\d)").replace(source) { m ->
            m.groupValues[1] + " " + numbers.ordinal(m.groupValues[2].toLong())
        }

    private fun ranges(source: String): String =
        Regex("(?<![\\d.,])(\\d+)\\s?[-–]\\s?(\\d+)(?![\\d.,])").replace(source) { m ->
            readNumber(m.groupValues[1]) + " đến " + readNumber(m.groupValues[2])
        }

    private fun symbols(source: String): String = source
        .replace(Regex("(?<=\\d)\\s?[x×]\\s?(?=\\d)"), " nhân ")
        .replace(Regex("(?<=\\d)\\s?\\+\\s?(?=\\d)"), " cộng ")
        .replace(Regex("(?<=\\d)\\s?=\\s?(?=\\d)"), " bằng ")
        .replace(Regex("~\\s?(?=\\d)"), "khoảng ")
        .replace(Regex("\\s&\\s"), " và ")
        .replace(Regex("(?<=\\d)/(?=\\d)"), " trên ")

    /** A number token in any of the shapes [num] accepts. */
    private fun readNumber(token: String): String {
        val vnGrouped = Regex("\\d{1,3}(?:\\.\\d{3})+(?:,(\\d+))?")
        val enGrouped = Regex("\\d{1,3}(?:,\\d{3})+(?:\\.(\\d+))?")
        vnGrouped.matchEntire(token)?.let { m ->
            val whole = token.substringBefore(',').replace(".", "")
            val fraction = m.groupValues[1]
            return if (fraction.isEmpty()) numbers.cardinal(whole) else numbers.decimal(whole, fraction, "phẩy")
        }
        enGrouped.matchEntire(token)?.let { m ->
            val whole = token.substringBefore('.').replace(",", "")
            val fraction = m.groupValues[1]
            return if (fraction.isEmpty()) numbers.cardinal(whole) else numbers.decimal(whole, fraction, "phẩy")
        }
        Regex("(\\d+)([.,])(\\d+)").matchEntire(token)?.let { m ->
            val (whole, _, fraction) = m.destructured
            return numbers.decimal(whole, fraction, "phẩy")
        }
        // Leading zeros mean a code or phone number, which is read digit by digit.
        return if (token.length > 1 && token.startsWith("0")) numbers.spellDigits(token) else numbers.cardinal(token)
    }
}
