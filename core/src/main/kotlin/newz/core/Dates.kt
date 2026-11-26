package newz.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** Turns the date strings found in feeds and on pages into epoch milliseconds. */
object Dates {
    private val zoned = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        fmt("EEE, d MMM yyyy HH:mm:ss zzz"),
        fmt("EEE, d MMM yyyy HH:mm zzz"),
        fmt("d MMM yyyy HH:mm:ss zzz"),
        fmt("EEE, d MMM yyyy HH:mm:ss Z"),
        fmt("yyyy-MM-dd HH:mm:ss Z"),
    )
    private val localDateTimes = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        fmt("yyyy-MM-dd HH:mm:ss"),
        fmt("yyyy-MM-dd HH:mm"),
    )
    private val localDates = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        fmt("MMMM d, yyyy"),
        fmt("MMM d, yyyy"),
        fmt("d MMMM yyyy"),
        fmt("d MMM yyyy"),
        fmt("MM/dd/yyyy"),
    )
    private val relative = Regex(
        """(\d+|an?)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|weeks?|[smhdw])\s+ago""",
        RegexOption.IGNORE_CASE,
    )

    private fun fmt(pattern: String): DateTimeFormatter =
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.US)

    fun parse(text: String?, now: Long = System.currentTimeMillis()): Long? {
        val s = text?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (s.isEmpty()) return null

        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        for (f in zoned) {
            runCatching { return ZonedDateTime.parse(s, f).toInstant().toEpochMilli() }
        }
        for (f in localDateTimes) {
            runCatching { return LocalDateTime.parse(s, f).toInstant(ZoneOffset.UTC).toEpochMilli() }
        }

        relative.find(s)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: 1L
            val unit = m.groupValues[2].lowercase()
            val millis = when {
                unit.startsWith("s") -> 1_000L
                unit.startsWith("m") -> 60_000L
                unit.startsWith("h") -> 3_600_000L
                unit.startsWith("d") -> 86_400_000L
                else -> 7 * 86_400_000L
            }
            return now - n * millis
        }
        when (s.lowercase()) {
            "just now", "now" -> return now
            "yesterday" -> return now - 86_400_000L
            "today" -> return now
        }

        // "Nov. 26, 2026" and "Updated November 26, 2026" style strings.
        val cleaned = s.replace(".", "").replace(Regex("^(updated|published|posted)\\s*:?\\s*", RegexOption.IGNORE_CASE), "")
        for (f in localDates) {
            runCatching { return LocalDate.parse(cleaned, f).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
        }
        return null
    }
}
