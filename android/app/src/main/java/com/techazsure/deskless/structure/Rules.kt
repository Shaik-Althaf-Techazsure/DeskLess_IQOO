package com.techazsure.deskless.structure

import com.techazsure.deskless.data.Severity
import java.util.Locale

/**
 * Fallback for when the Gemma model is not on the phone: a title from the first sentence and a severity
 * from keywords. It does not try to find the location, owner or due date.
 */
object Rules {
    private val critical = listOf("collapse", "fire", "live wire", "exposed wire", "electrocut", "gas leak", "evacuat", "immediate danger")
    private val high = listOf("danger", "unsafe", "hazard", "urgent", "crack", "leak", "broken", "short circuit", "fall risk", "stop work", "injur")
    private val low = listOf("minor", "cosmetic", "paint", "scratch", "stain", "small", "touch up", "touch-up")

    fun structure(note: String): Fields {
        val text = note.lowercase(Locale.ENGLISH)
        val severity = when {
            critical.any { it in text } -> Severity.CRITICAL
            high.any { it in text } -> Severity.HIGH
            low.any { it in text } -> Severity.LOW
            else -> Severity.MEDIUM
        }
        val sentence = note.trim().split(Regex("""(?<=[.!?])\s+""")).firstOrNull().orEmpty().trimEnd('.', '!', '?')
        val words = sentence.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val title = if (words.size <= 8) sentence else words.take(8).joinToString(" ") + "…"
        return Fields(title = title, location = "", severity = severity, owner = "", dueDate = "")
    }
}
