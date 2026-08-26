package com.bd.casttv.util

/**
 * 自然排序器（与 FavoritesPage 收藏页排序保持一致）。
 * 处理 "第1集/第2集" "第一集/第三集" "第01集" "E01 S02" 等含数字或中文大写数字的标题，
 * 按「词 + 数值」维度逐段比较，保证频道/条目列表从第1集到第N集正序。
 */
object NaturalSorter {

    private data class Token(val text: String? = null, val number: Long? = null)

    private fun isChineseNumberChar(ch: Char): Boolean = ch in "零〇一二两三四五六七八九十百千万"

    private fun chineseDigitValue(ch: Char): Long = when (ch) {
        '一' -> 1L
        '二', '两' -> 2L
        '三' -> 3L
        '四' -> 4L
        '五' -> 5L
        '六' -> 6L
        '七' -> 7L
        '八' -> 8L
        '九' -> 9L
        else -> 0L
    }

    private fun parseChineseNumber(value: String): Long {
        if (value.isBlank()) return 0L
        if (value.none { it in "十百千万" }) {
            return value.fold(0L) { acc, ch -> acc * 10L + chineseDigitValue(ch) }
        }
        var total = 0L
        var section = 0L
        var number = 0L
        value.forEach { ch ->
            when (ch) {
                '零', '〇' -> number = 0L
                '一' -> number = 1L
                '二', '两' -> number = 2L
                '三' -> number = 3L
                '四' -> number = 4L
                '五' -> number = 5L
                '六' -> number = 6L
                '七' -> number = 7L
                '八' -> number = 8L
                '九' -> number = 9L
                '十' -> {
                    section += (if (number == 0L) 1L else number) * 10L
                    number = 0L
                }
                '百' -> {
                    section += (if (number == 0L) 1L else number) * 100L
                    number = 0L
                }
                '千' -> {
                    section += (if (number == 0L) 1L else number) * 1000L
                    number = 0L
                }
                '万' -> {
                    total += (section + number).coerceAtLeast(1L) * 10000L
                    section = 0L
                    number = 0L
                }
            }
        }
        return total + section + number
    }

    private fun naturalSortTokens(value: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                ch.isDigit() -> {
                    var number = 0L
                    while (index < value.length && value[index].isDigit()) {
                        val digit = Character.getNumericValue(value[index]).takeIf { it in 0..9 } ?: 0
                        number = (number * 10L + digit).coerceAtMost(Long.MAX_VALUE / 10L)
                        index++
                    }
                    tokens.add(Token(number = number))
                }
                isChineseNumberChar(ch) -> {
                    val start = index
                    while (index < value.length && isChineseNumberChar(value[index])) index++
                    tokens.add(Token(number = parseChineseNumber(value.substring(start, index))))
                }
                else -> {
                    val start = index
                    while (index < value.length && !value[index].isDigit() && !isChineseNumberChar(value[index])) index++
                    tokens.add(Token(text = value.substring(start, index).lowercase()))
                }
            }
        }
        return tokens
    }

    private fun normalizeNaturalSortTokens(tokens: List<Token>): List<Token> {
        // 「第一集」与「1集 / 第01集」应按同一个数字维度比较，不能让"第"这个序号前缀影响排序。
        return if (tokens.firstOrNull()?.text == "第" && tokens.getOrNull(1)?.number != null) tokens.drop(1) else tokens
    }

    fun compare(left: String, right: String): Int {
        val leftTokens = normalizeNaturalSortTokens(naturalSortTokens(left))
        val rightTokens = normalizeNaturalSortTokens(naturalSortTokens(right))
        val size = minOf(leftTokens.size, rightTokens.size)
        for (i in 0 until size) {
            val l = leftTokens[i]
            val r = rightTokens[i]
            val result = when {
                l.number != null && r.number != null -> l.number.compareTo(r.number)
                l.text != null && r.text != null -> l.text.compareTo(r.text)
                l.number != null -> -1
                else -> 1
            }
            if (result != 0) return result
        }
        val sizeCompare = leftTokens.size.compareTo(rightTokens.size)
        return if (sizeCompare != 0) sizeCompare else left.compareTo(right)
    }

    /** 比较器，便于 Collections.sort / sortedWith 直接使用。 */
    val comparator: Comparator<String> = Comparator { a, b -> compare(a, b) }
}
