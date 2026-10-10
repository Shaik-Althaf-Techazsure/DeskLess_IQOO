package com.techazsure.deskless.report

import com.techazsure.deskless.capture.Geo
import com.techazsure.deskless.data.Check
import com.techazsure.deskless.data.Inspection
import com.techazsure.deskless.data.Instrument
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.Readings
import java.util.Locale
import kotlin.math.roundToInt

/** What the review makes of a report's evidence. Only a report that looks genuine is approved. */
enum class Verdict(val label: String, val decision: String) {
    GENUINE("Looks genuine", "Approved"),
    DOUBTFUL("Doubtful", "Held for a person to check"),
    FAKE("Does not hold together", "Rejected"),
}

enum class Mark { PASS, FAIL, NOTE }

/**
 * One thing the review looked at. [weight] counts the issues it shows to be plainly contradicted, which is
 * what tells a report that does not hold together from one that only has loose ends.
 */
data class Finding(val mark: Mark, val text: String, val weight: Int = 0)

data class Review(
    val verdict: Verdict,
    val reason: String,
    val findings: List<Finding>,
    /** True when the on-device model gave its own verdict; false when only the fixed checks ran. */
    val byModel: Boolean,
) {
    val approved: Boolean get() = verdict == Verdict.GENUINE
}

/**
 * Checks a finished report against itself before it goes out: do the pictures show what the notes say, do
 * the positions lie on one site, were the pictures taken in order during this walk, and do the phone's
 * sensor readings agree with what was said? The fixed checks here decide what can be decided by rule; the
 * on-device model then reads the evidence and gives its own verdict, and the stricter of the two stands.
 * It is a check of the report's own evidence, to help a reviewer. It is not proof.
 */
object Reviewer {
    /** Positions further apart than this are not one site. */
    private const val ONE_SITE_METRES = 1500f

    private val DARK = Regex("""\b(dark|dim|no light|poor light|poor lighting|badly lit|not enough light)\b""")
    private val OVERHEAD = Regex("""\b(ceiling|roof|overhead)\b""")
    private val GROUND = Regex("""\b(floor|ground|pothole)\b""")
    private val NOISY = Regex("""\b(noisy|loud|noise)\b""")
    private val SLOPING = Regex("""\b(slope|slopes|sloping|tilted|uneven|not level|out of level)\b""")

    fun findings(inspection: Inspection, issues: List<Issue>): List<Finding> {
        val numbered = issues.mapIndexed { i, issue -> i + 1 to issue }
        fun named(numbers: List<Int>) = if (numbers.size == 1) "issue ${numbers[0]}" else "issues " + numbers.joinToString(", ")
        val found = ArrayList<Finding>()

        found += Finding(Mark.PASS, "Every picture was taken with the app's own camera during the walk; none was brought in from elsewhere.")

        // The pictures against the notes, as the model judged each one when it was captured.
        val mismatched = numbered.filter { it.second.check == Check.MISMATCH }.map { it.first }
        val unsure = numbered.filter { it.second.check == Check.UNSURE }.map { it.first }
        val unchecked = numbered.filter { it.second.check == Check.NONE }.map { it.first }
        val overruled = issues.count { it.check == Check.CONFIRMED }
        if (mismatched.isNotEmpty()) {
            found += Finding(Mark.FAIL, "The picture does not show what the note says in ${named(mismatched)}.", weight = mismatched.size)
        }
        if (unsure.isNotEmpty()) found += Finding(Mark.FAIL, "It is not clear that the picture shows the problem in ${named(unsure)}.")
        if (unchecked.isNotEmpty()) found += Finding(Mark.FAIL, "The picture was never checked against a note in ${named(unchecked)}.")
        if (mismatched.isEmpty() && unsure.isEmpty() && unchecked.isEmpty()) {
            found += Finding(
                Mark.PASS,
                "Every picture shows what its note describes" +
                    if (overruled > 0) " ($overruled confirmed by the inspector after the AI had doubts)." else ".",
            )
        }

        // Where: the positions the phone recorded should lie on one site.
        val located = issues.filter { it.located }
        found += when {
            located.isEmpty() -> Finding(Mark.NOTE, "No issue carries a satellite position, as happens indoors.")
            located.size == 1 -> Finding(Mark.NOTE, "1 of ${issues.size} issues carries a satellite position.")
            else -> {
                val span = Geo.span(Geo.project(located.map { it.lat!! to it.lon!! }), floor = 0f)
                if (span <= ONE_SITE_METRES) {
                    Finding(Mark.PASS, "The ${located.size} positions recorded lie within ${span.roundToInt().coerceAtLeast(1)} m of each other: one site.")
                } else {
                    Finding(Mark.FAIL, String.format(Locale.ENGLISH, "The positions recorded are up to %.1f km apart, which is not one site.", span / 1000f), weight = 1)
                }
            }
        }

        // When: in order, and not from before this walk began.
        val times = issues.map { it.createdAt }
        if (times.isNotEmpty()) {
            val inOrder = times == times.sorted() && times.first() >= inspection.startedAt - 60_000
            val minutes = (times.last() - times.first()) / 60_000
            found += when {
                !inOrder -> Finding(Mark.FAIL, "The capture times are out of order, or from before this walk began.", weight = 1)
                issues.size == 1 -> Finding(Mark.PASS, "The picture was taken during this walk.")
                else -> Finding(Mark.PASS, "The pictures were taken in order over " + (if (minutes < 1) "less than a minute." else "$minutes minutes."))
            }
        }

        // What the phone itself measured, against what was said.
        val stamped = issues.count { it.stamp != null }
        found += if (stamped == issues.size) {
            Finding(Mark.PASS, "Every picture carries sensor readings from the moment it was taken.")
        } else {
            Finding(Mark.NOTE, "$stamped of ${issues.size} pictures carry sensor readings from the moment they were taken.")
        }
        val clashes = numbered.flatMap { (number, issue) -> contradictions(number, issue) }
        found += when {
            clashes.isNotEmpty() -> clashes.map { Finding(Mark.FAIL, it, weight = 1) }
            issues.any(::testable) -> listOf(Finding(Mark.PASS, "Where a note says something the sensors can test, the readings agree with it."))
            else -> listOf(Finding(Mark.NOTE, "No note says anything the phone's sensors could test."))
        }
        return found
    }

    /** Where what was said and what the phone measured cannot both be true. */
    fun contradictions(number: Int, issue: Issue): List<String> {
        val note = issue.note.lowercase(Locale.ENGLISH)
        val aim = Readings.aim(issue.stamp?.pitch)
        val lux = issue.stamp?.lux
        val noise = issue.readings.lastOrNull { it.kind == Instrument.NOISE }?.value
        val slope = issue.readings.lastOrNull { it.kind == Instrument.LEVEL }?.value
        return listOfNotNull(
            if (DARK.containsMatchIn(note) && lux != null && lux >= 300f && issue.stamp?.torch != true) {
                String.format(Locale.ENGLISH, "Issue %d says it is dark, but the phone measured %.0f lux there.", number, lux)
            } else null,
            if (OVERHEAD.containsMatchIn(note) && aim == "at ground level") {
                "Issue $number is about something overhead, but the camera was pointed at the ground."
            } else null,
            if (GROUND.containsMatchIn(note) && !OVERHEAD.containsMatchIn(note) && aim == "overhead") {
                "Issue $number is about the floor or ground, but the camera was pointed up."
            } else null,
            if (NOISY.containsMatchIn(note) && noise != null && noise < 50f) {
                String.format(Locale.ENGLISH, "Issue %d says it is noisy, but the noise meter read %.0f dB.", number, noise)
            } else null,
            if (SLOPING.containsMatchIn(note) && slope != null && slope < 0.3f) {
                String.format(Locale.ENGLISH, "Issue %d says the surface slopes, but the level read %.1f°.", number, slope)
            } else null,
        )
    }

    /** True when the note says something one of the phone's readings for this issue can be held against. */
    private fun testable(issue: Issue): Boolean {
        val note = issue.note.lowercase(Locale.ENGLISH)
        return (DARK.containsMatchIn(note) && issue.stamp?.lux != null) ||
            ((OVERHEAD.containsMatchIn(note) || GROUND.containsMatchIn(note)) && issue.stamp?.pitch != null) ||
            (NOISY.containsMatchIn(note) && issue.readings.any { it.kind == Instrument.NOISE }) ||
            (SLOPING.containsMatchIn(note) && issue.readings.any { it.kind == Instrument.LEVEL })
    }

    /** The verdict the fixed checks reach by themselves. */
    fun byRules(findings: List<Finding>, issues: Int): Verdict {
        if (findings.none { it.mark == Mark.FAIL }) return Verdict.GENUINE
        val contradicted = findings.sumOf { it.weight }
        // Half the issues or more plainly contradicted, and at least two: the report does not hold together.
        return if (contradicted >= 2 && contradicted * 2 >= issues) Verdict.FAKE else Verdict.DOUBTFUL
    }

    /**
     * The final review. [model] is the on-device model's own verdict and reason, or null when it did not
     * run. The model can make the verdict stricter than the checks, never more lenient, and it takes a
     * failed check as well as the model to call a report one that does not hold together.
     */
    fun decide(findings: List<Finding>, issues: Int, model: Pair<Verdict, String>?): Review {
        val rules = byRules(findings, issues)
        val verdict = when {
            model == null -> rules
            rules == Verdict.FAKE || (model.first == Verdict.FAKE && rules == Verdict.DOUBTFUL) -> Verdict.FAKE
            rules == Verdict.DOUBTFUL || model.first != Verdict.GENUINE -> Verdict.DOUBTFUL
            else -> Verdict.GENUINE
        }
        val ownReason = findings.firstOrNull { it.mark == Mark.FAIL }?.text
            ?: "The pictures match the notes, and nothing in the positions, times or readings contradicts them."
        // The model's sentence is used when it explains the verdict that stands; otherwise the checks speak.
        val reason = model?.takeIf { it.first == verdict && it.second.length > 10 }?.second ?: ownReason
        return Review(verdict, reason, findings, byModel = model != null)
    }

    /** The evidence as the on-device model is given it: what was checked, then one line per issue. */
    fun evidence(issues: List<Issue>, findings: List<Finding>): String {
        val checks = findings.joinToString("\n") { finding ->
            val mark = when (finding.mark) {
                Mark.PASS -> "OK"
                Mark.FAIL -> "PROBLEM"
                Mark.NOTE -> "NOTE"
            }
            "- [$mark] ${finding.text}"
        }
        val lines = issues.take(MAX_ISSUES_FOR_MODEL).mapIndexed { i, issue ->
            listOfNotNull(
                "Issue ${i + 1}: note \"${issue.note.take(160).ifBlank { "none" }}\"",
                issue.seen.takeIf { it.isNotBlank() }?.let { "the photo shows \"${it.take(120)}\"" },
                "picture check: " + when (issue.check) {
                    Check.MATCH -> "matches the note"
                    Check.CONFIRMED -> "doubted by the AI, confirmed by the inspector"
                    Check.UNSURE -> "unclear"
                    Check.MISMATCH -> "does not match the note"
                    Check.NONE -> "not checked"
                },
            ).joinToString("; ")
        }.joinToString("\n")
        return "What the app checked:\n$checks\nThe issues:\n$lines"
    }

    private const val MAX_ISSUES_FOR_MODEL = 12
}
