package com.bd.casttv.music

private val TIME_TAG_REGEX = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

data class MusicLrcLine(
    val timeMs: Long,
    val text: String,
)

object MusicLrcParser {
    fun parse(raw: String): List<MusicLrcLine> {
        if (raw.isBlank()) return emptyList()
        val result = mutableListOf<MusicLrcLine>()
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach lineLoop@{ line ->
                val matches = TIME_TAG_REGEX.findAll(line).toList()
                if (matches.isEmpty()) return@lineLoop
                val text = TIME_TAG_REGEX.replace(line, "").trim().ifBlank { "♪" }
                matches.forEach { match ->
                    val minute = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return@forEach
                    val second = match.groupValues.getOrNull(2)?.toLongOrNull() ?: return@forEach
                    val fractionRaw = match.groupValues.getOrNull(3).orEmpty()
                    val fractionMs = when (fractionRaw.length) {
                        0 -> 0L
                        1 -> fractionRaw.toLongOrNull()?.times(100L) ?: 0L
                        2 -> fractionRaw.toLongOrNull()?.times(10L) ?: 0L
                        else -> fractionRaw.take(3).toLongOrNull() ?: 0L
                    }
                    val timeMs = minute * 60_000L + second * 1_000L + fractionMs
                    result += MusicLrcLine(timeMs = timeMs, text = text)
                }
            }
        return result
            .distinctBy { "${it.timeMs}_${it.text}" }
            .sortedBy { it.timeMs }
    }
}
