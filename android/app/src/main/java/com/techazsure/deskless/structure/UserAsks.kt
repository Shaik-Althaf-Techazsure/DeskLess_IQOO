package com.techazsure.deskless.structure

import com.techazsure.deskless.data.Severity
import org.json.JSONObject
import java.util.Locale

/**
 * What someone who uses a place wants the place to have: something to put right, something that
 * happened, or a question. The model on the phone words it from what the person said; they hear it read
 * back, change it by saying so, and only then is it sent.
 */
data class Ask(
    /** "issue", "report" or "enquiry". */
    val kind: String,
    val title: String,
    /** What the person said, in a sentence or two, as it is read back to them and sent to the place. */
    val summary: String,
    val location: String,
    val severity: Severity,
    val category: String,
    /** Everything the person has said about it so far, in English, in the order they said it. */
    val said: String,
) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("title", title).put("summary", summary).put("location", location)
        .put("severity", severity.name).put("category", category).put("said", said)

    companion object {
        fun from(json: JSONObject): Ask = Ask(
            kind = json.optString("kind").takeIf { it in UserAsks.KINDS.keys } ?: "issue",
            title = json.optString("title"),
            summary = json.optString("summary"),
            location = json.optString("location"),
            severity = Severity.entries.firstOrNull { it.name == json.optString("severity") } ?: Severity.MEDIUM,
            category = json.optString("category"),
            said = json.optString("said"),
        )
    }
}

/**
 * The rules around what a user of a place sends in. The model words a title and a summary and proposes a
 * kind, a place and a severity; everything here is plain code: what the model is asked, which of its
 * answers are kept, what "send it" and "forget it" sound like, and the words the phone reads back.
 */
object UserAsks {
    /** The kinds, with what a person calls each. */
    val KINDS = linkedMapOf("issue" to "Issue", "report" to "Report", "enquiry" to "Question")

    /** What an issue at any kind of place can be sorted into. */
    val CATEGORIES = listOf(
        "Electrical", "Water and plumbing", "Cleanliness", "Safety and security", "Lifts and doors", "Building and fittings", "Noise and people", "Other",
    )

    private val SEVERITY_GUIDE = "Critical: someone can be hurt right now, such as fire, smoke, a smell of gas, a bare live wire, people stuck in a lift. " +
        "High: likely to hurt someone or to stop people using the place soon, such as water on stairs, a broken main door lock, no power, no water. " +
        "Medium: needs repair but nobody is at risk, such as a light that is out, a tap that drips, a broken seat. " +
        "Low: a small matter, a suggestion, or a question."

    fun kindWord(kind: String): String = when (kind) {
        "report" -> "a report"
        "enquiry" -> "a question"
        else -> "an issue"
    }

    // ---------------------------------------------------------------- what the model is asked

    /** The model's brief for wording what the person said. [placeKind] and [place] say where it is for. */
    fun brief(placeKind: String, place: String): String = listOf(
        "You help a person who uses a place tell the people who look after it what they noticed or want to know. The place is $place ($placeKind). You are given what the person said.",
        "Reply with a single JSON object and nothing else, with exactly these keys:",
        "\"kind\": \"issue\" if something is broken, unsafe, dirty or not working and needs putting right; \"report\" if the person tells of something that happened or that somebody did; \"enquiry\" if the person asks a question.",
        "\"title\": a short title, at most 8 words. For an enquiry, the question itself in at most 14 words, ending in a question mark.",
        "\"summary\": what the person said, in one or two plain sentences in their own voice, as they would say it themselves: \"The lift is stuck\", never \"The person says the lift is stuck\". Add nothing they did not say.",
        "\"location\": where in the place it is, as the person said, or \"\".",
        "\"severity\": one of \"Low\", \"Medium\", \"High\", \"Critical\".",
        "\"category\": the one that fits best of ${CATEGORIES.joinToString(", ") { "\"$it\"" }}.",
        "Use only what the person said. Never invent a name, a place or a time, and give no advice.",
        "Severity guide: $SEVERITY_GUIDE",
    ).joinToString("\n")

    /** The model's brief for changing a draft the person has heard read back. */
    fun changeBrief(): String = listOf(
        "A person is telling the people who look after a place about something. You are given the draft as it stands and what the person now says should change.",
        "Reply with a single JSON object and nothing else, with exactly the keys \"kind\", \"title\", \"summary\", \"location\", \"severity\" and \"category\": the whole draft after the change.",
        "\"kind\" is one of \"issue\", \"report\", \"enquiry\". \"severity\" is one of \"Low\", \"Medium\", \"High\", \"Critical\". \"category\" is one of ${CATEGORIES.joinToString(", ") { "\"$it\"" }}.",
        "Change only what the person asks to change, and keep every other key exactly as it is in the draft. When the person adds something, work it into the summary. Never invent a name, a place or a time.",
    ).joinToString("\n")

    /** A draft as the model is shown it. */
    fun lines(ask: Ask): String = listOf(
        "kind: ${ask.kind}", "title: ${ask.title}", "summary: ${ask.summary}", "location: ${ask.location}",
        "severity: ${ask.severity.label}", "category: ${ask.category}",
    ).joinToString("\n")

    // ---------------------------------------------------------------- what is kept of its answer

    /**
     * The draft made from the model's reply; [reply] gives what it wrote under a key, blank when nothing.
     * [said] is everything the person has said; [before] the draft that was being changed, if any. A kind,
     * severity or category the app does not know is not taken; a place is kept only when its words are in
     * what the person said; a question is never more than Low.
     */
    fun fromModel(reply: (String) -> String, said: String, before: Ask? = null): Ask {
        val fallback = before ?: plain(said)
        fun text(key: String): String = reply(key).trim().takeUnless { it.lowercase(Locale.ENGLISH) in EMPTY }.orEmpty()
        val kind = text("kind").lowercase(Locale.ENGLISH).let { if (it == "question") "enquiry" else it }.takeIf { it in KINDS.keys } ?: fallback.kind
        val where = text("location").takeIf { it.isNotBlank() && Grounding.heard(it, said) } ?: fallback.location
        val named = text("category")
        val category = CATEGORIES.firstOrNull { it.equals(named, ignoreCase = true) }
            ?: CATEGORIES.firstOrNull { named.isNotEmpty() && (it.contains(named, ignoreCase = true) || named.contains(it, ignoreCase = true)) }
            ?: fallback.category.ifBlank { "Other" }
        val severity = Severity.entries.firstOrNull { it.label.equals(text("severity"), ignoreCase = true) } ?: fallback.severity
        return Ask(
            kind = kind,
            title = text("title").take(120).replaceFirstChar { it.uppercase(Locale.ENGLISH) }.ifBlank { fallback.title },
            // Asked for the person's own voice, the model still sometimes writes about them: then their own words stand.
            summary = text("summary").take(500).takeUnless { ABOUT_THEM.containsMatchIn(it) }.orEmpty().ifBlank { fallback.summary },
            location = where.replaceFirstChar { it.uppercase(Locale.ENGLISH) },
            severity = if (kind == "enquiry") Severity.LOW else severity,
            category = if (kind == "enquiry") "" else category,
            said = said,
        )
    }

    /** With no model on the phone: the person's own words, a title from the first of them, and a kind and severity from plain rules. */
    fun plain(said: String): Ask {
        val words = said.trim()
        val rules = Rules.structure(words)
        val asks = words.endsWith("?") || QUESTION.containsMatchIn(words)
        return Ask(
            kind = if (asks) "enquiry" else "issue",
            title = rules.title.replaceFirstChar { it.uppercase(Locale.ENGLISH) } + if (asks && !rules.title.endsWith("?") && !rules.title.endsWith("…")) "?" else "",
            summary = words,
            location = "",
            severity = if (asks) Severity.LOW else rules.severity ?: Severity.MEDIUM,
            category = if (asks) "" else "Other",
            said = words,
        )
    }

    // ---------------------------------------------------------------- what the person says back

    /**
     * True when what was said after the read-back means "yes, send it": a few words that are all words of
     * agreement, or a short sentence with "send it" in it. "It is on the right side" is not one.
     */
    fun wantsSend(said: String): Boolean {
        val words = Grounding.words(said)
        if (words.isEmpty() || words.size > 8 || words.any { it in HOLDS }) return false
        if (words.all { it in AGREES || it in FILLS } && words.any { it in AGREES }) return true
        return said.lowercase(Locale.ENGLISH).let { text -> SEND_PHRASES.any { it in text } }
    }

    /** True when it means "drop it": a few words that are all words of giving up. "The class was cancelled" is not one. */
    fun wantsCancel(said: String): Boolean {
        val words = Grounding.words(said)
        if (words.isEmpty() || words.size > 6 || !words.all { it in DROPS || it in FILLS || it in REFUSES || it == "send" }) return false
        return words.any { it in DROPS } || ("never" in words && "mind" in words) || (words.any { it in REFUSES } && "send" in words)
    }

    /** True for a bare "no" or "wait": the person does not want it sent yet and has not said what to change. */
    fun onlyRefuses(said: String): Boolean =
        Grounding.words(said).let { words -> words.isNotEmpty() && words.size <= 3 && words.all { it in setOf("no", "nope", "wait", "not", "yet", "hold", "on") } }

    /** True when the person asks to add a picture. */
    fun wantsPhoto(said: String): Boolean {
        val text = said.lowercase(Locale.ENGLISH)
        return Grounding.words(said).size <= 10 && PHOTO.any { it in text } && ("add" in text || "take" in text || "attach" in text || "with a" in text)
    }

    /**
     * A change plain enough to need no model: "make it high", "it is urgent", "it is a question". Null
     * when what was said is not one of those, and the model is to read it.
     */
    fun quickChange(ask: Ask, said: String): Ask? {
        val words = Grounding.words(said)
        // Only a sentence made of nothing but such words: "the water pressure is low" is about the water.
        if (words.isEmpty() || words.size > 7 || !words.all { it in CHANGE_WORDS }) return null
        val severity = when {
            "critical" in words || "emergency" in words -> Severity.CRITICAL
            "high" in words || "urgent" in words || "serious" in words -> Severity.HIGH
            "medium" in words || "normal" in words -> Severity.MEDIUM
            "low" in words || "minor" in words -> Severity.LOW
            else -> null
        }
        val kind = when {
            "question" in words || "enquiry" in words || "inquiry" in words -> "enquiry"
            "report" in words -> "report"
            "issue" in words || "problem" in words || "complaint" in words -> "issue"
            else -> null
        }
        if (severity == null && kind == null) return null
        val next = kind ?: ask.kind
        return ask.copy(
            kind = next,
            severity = if (next == "enquiry") Severity.LOW else severity ?: ask.severity,
            category = if (next == "enquiry") "" else ask.category.ifBlank { "Other" },
            said = ask.said + " " + said.trim(),
        )
    }

    // ---------------------------------------------------------------- what the phone says

    /** The draft as the phone reads it back, ending in what the person can say next. */
    fun readBack(ask: Ask, place: String): String {
        val title = ask.title.trimEnd('.')
        val head = "I have this as ${kindWord(ask.kind)} for $place: $title" + if (title.endsWith("?")) "" else "."
        val where = ask.location.takeIf { it.isNotBlank() }?.let { " Where: $it." }.orEmpty()
        val bad = if (ask.kind == "enquiry") "" else " Marked ${ask.severity.label.lowercase(Locale.ENGLISH)}."
        val more = ask.summary.takeIf { it.isNotBlank() && !sameWords(it, ask.title) }?.let { " " + it.trimEnd('.') + "." }.orEmpty()
        return "$head$where$bad$more Say send it, or tell me what to change."
    }

    /** The draft as shown on one line, for lists. */
    fun shortLine(ask: Ask): String = listOfNotNull(KINDS[ask.kind], ask.location.takeIf { it.isNotBlank() }, ask.severity.label.takeIf { ask.kind != "enquiry" }).joinToString(" · ")

    private fun sameWords(a: String, b: String): Boolean = Grounding.keyWords(a).toSet() == Grounding.keyWords(b).toSet()

    private val ABOUT_THEM = Regex("""^(the|this) (person|user|resident|speaker|caller|individual)\b""", RegexOption.IGNORE_CASE)
    private val EMPTY = setOf("null", "none", "unknown", "n/a", "not specified", "not mentioned", "not said")
    private val QUESTION = Regex("""^(is|are|was|were|when|where|what|who|whom|whose|how|can|could|does|do|did|will|would|which|why|may|shall|should)\b""", RegexOption.IGNORE_CASE)
    private val AGREES = setOf("send", "submit", "yes", "yeah", "yep", "ok", "okay", "correct", "right", "fine", "sure", "perfect", "good")

    /** Words that go along with agreeing or giving up and mean nothing by themselves. */
    private val FILLS = setOf("please", "it", "this", "that", "s", "is", "now", "all", "go", "ahead", "looks", "sounds", "you", "can", "just", "thanks", "thank")
    private val SEND_PHRASES = listOf("send it", "send this", "send that", "send now", "go ahead", "submit it")

    /** Words that mean the person is still changing it, whatever else they say. */
    private val HOLDS = setOf("change", "not", "no", "don", "dont", "wait", "instead", "add", "make", "but", "wrong", "cancel", "forget", "delete", "stop", "never")
    private val DROPS = setOf("cancel", "forget", "discard", "delete", "drop", "nevermind")
    private val REFUSES = setOf("don", "dont", "do", "not", "no", "t", "never", "mind")
    private val CHANGE_WORDS = setOf(
        "make", "mark", "set", "change", "it", "is", "its", "s", "as", "to", "a", "an", "this", "that", "the", "severity", "priority", "please", "not", "very",
        "critical", "emergency", "high", "urgent", "serious", "medium", "normal", "low", "minor",
        "question", "enquiry", "inquiry", "report", "issue", "problem", "complaint",
    )
    private val PHOTO = listOf("photo", "picture", "image", "pic")
}
