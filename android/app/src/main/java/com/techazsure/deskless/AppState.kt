package com.techazsure.deskless

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.techazsure.deskless.capture.Haptics
import com.techazsure.deskless.capture.Locator
import com.techazsure.deskless.capture.SiteSensors
import com.techazsure.deskless.capture.TagBook
import com.techazsure.deskless.data.Check
import com.techazsure.deskless.data.Inspection
import com.techazsure.deskless.structure.Expiry
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.IssueStore
import com.techazsure.deskless.data.Reading
import com.techazsure.deskless.data.Readings
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.data.Stamp
import com.techazsure.deskless.data.Template
import com.techazsure.deskless.link.Link
import com.techazsure.deskless.link.Peer
import com.techazsure.deskless.link.Voice
import com.techazsure.deskless.models.ModelStore
import com.techazsure.deskless.report.ReportEntry
import com.techazsure.deskless.report.ReportLibrary
import com.techazsure.deskless.report.ReportPack
import com.techazsure.deskless.report.Review
import com.techazsure.deskless.report.Reviewer
import com.techazsure.deskless.speech.Transcriber
import com.techazsure.deskless.structure.Fields
import com.techazsure.deskless.structure.Rules
import com.techazsure.deskless.structure.Structurer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

sealed interface Screen {
    data object Home : Screen

    /**
     * The camera. With [retakeId] it works on that issue instead of adding a new one: it replaces the
     * picture, or with [fix] it adds the picture taken after the repair.
     */
    data class Capture(val retakeId: String? = null, val fix: Boolean = false) : Screen
    data object Models : Screen

    /** The phone's sensors, live: what DeskLess measures with and whether each one is working. */
    data object Sensors : Screen

    /** Every report built on this phone. */
    data object Reports : Screen

    /** The camera, looking for another phone's QR code to link with. */
    data object Scan : Screen
    data class Edit(val id: String) : Screen

    /** One built report. [fromLibrary] is where Back goes: the library, or the current walk. */
    data class Report(val path: String, val fromLibrary: Boolean = false) : Screen
}

/** Which issues the list shows. */
sealed interface Filter {
    data object All : Filter
    data class BySeverity(val severity: Severity) : Filter
    data class ByCategory(val category: String) : Filter

    /** Issues the AI doubted, or that still lack a title. */
    data object NeedsALook : Filter

    /** Issues still waiting for a repair, or already shown to be repaired. */
    data object Open : Filter
    data object Fixed : Filter
}

/** The order the list shows them in. */
enum class Sort(val label: String) {
    CAPTURED("In the order captured"),
    NEWEST("Newest first"),
    SEVERITY("Most severe first"),
    TYPE("Grouped by type"),
}

/** Everything the screens show. Each change is written straight to disk. */
class AppState(
    context: Context,
    private val store: IssueStore,
    private val models: ModelStore,
    private val library: ReportLibrary,
    private val locator: Locator,
    val sensors: SiteSensors,
    val haptics: Haptics,
    private val tags: TagBook,
    val voice: Voice,
    private val cacheDir: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Both models are heavy; one worker thread keeps them from fighting over the phone's cores. */
    private val modelWork = Executors.newSingleThreadExecutor { Thread(it, "models") }.asCoroutineDispatcher()
    private val transcriber = Transcriber(models)
    private val structurer = Structurer(models, cacheDir)

    var inspection by mutableStateOf(Inspection())
        private set
    val issues = mutableStateListOf<Issue>()
    var screen by mutableStateOf<Screen>(Screen.Home)

    /** A one-off message for the user; the UI shows it and sets it back to null. */
    var notice by mutableStateOf<String?>(null)

    var speechReady by mutableStateOf(models.speechReady)
        private set
    var gemmaReady by mutableStateOf(models.gemma != null)
        private set

    /** Ids of issues whose voice note is being turned into text right now. */
    val transcribing = mutableStateListOf<String>()
    private val transcribeAgain = HashSet<String>()

    /** Ids of issues whose report fields are being filled from the note right now. */
    val structuring = mutableStateListOf<String>()

    /** "GPU" or "CPU" once Gemma has been loaded. */
    var gemmaBackend by mutableStateOf<String?>(null)
        private set

    var importing by mutableStateOf(false)
        private set
    var importProgress by mutableStateOf<ModelStore.Progress?>(null)
        private set
    var importResult by mutableStateOf<ModelStore.ImportResult?>(null)
        private set

    var filter by mutableStateOf<Filter>(Filter.All)
    var sort by mutableStateOf(Sort.CAPTURED)

    /** Reports built on this phone, newest first. */
    val reports = mutableStateListOf<ReportEntry>()

    init {
        val (savedInspection, savedIssues) = store.load()
        inspection = savedInspection
        issues.addAll(savedIssues)
        reports.addAll(library.load())
    }

    /** True for an issue the inspector should open before the report goes out. */
    fun needsALook(issue: Issue): Boolean =
        issue.check == Check.MISMATCH || issue.check == Check.UNSURE || issue.title.isBlank()

    /** The issues to list, after the chosen filter and in the chosen order. */
    fun shownIssues(): List<Issue> {
        val kept = issues.filter { issue ->
            when (val chosen = filter) {
                Filter.All -> true
                is Filter.BySeverity -> issue.severity == chosen.severity
                is Filter.ByCategory -> issue.category.ifBlank { UNSORTED } == chosen.category
                Filter.NeedsALook -> needsALook(issue)
                Filter.Open -> !issue.resolved
                Filter.Fixed -> issue.resolved
            }
        }
        return when (sort) {
            Sort.CAPTURED -> kept
            Sort.NEWEST -> kept.sortedByDescending { it.createdAt }
            Sort.SEVERITY -> kept.sortedByDescending { it.severity.ordinal }
            Sort.TYPE -> kept.sortedBy { issue ->
                // In the template's own order, with unsorted issues last.
                inspection.template.categories.indexOf(issue.category).let { if (it < 0) Int.MAX_VALUE else it }
            }
        }
    }

    fun changeTemplate(template: Template) {
        inspection = inspection.copy(template = template)
        filter = Filter.All
        persist()
    }

    fun reportFile(entry: ReportEntry): File = library.file(entry)

    /** Adds a freshly built PDF to the library, with what the review made of it. */
    fun recordReport(file: File, inspection: Inspection, issues: List<Issue>, review: Review? = null) {
        val entry = library.record(file, inspection, issues, flagged = issues.count(::needsALook), review = review)
        reports.removeAll { it.fileName == entry.fileName }
        reports.add(0, entry)
    }

    /**
     * Has the finished report's evidence checked against itself: the fixed checks first, then the
     * on-device model's own verdict on top where the model is on the phone. See [Reviewer].
     */
    suspend fun reviewReport(inspection: Inspection, issues: List<Issue>): Review {
        val findings = Reviewer.findings(inspection, issues)
        val model = if (!gemmaReady || issues.isEmpty()) {
            null
        } else {
            val evidence = Reviewer.evidence(issues, findings)
            withContext(modelWork) { runCatching { structurer.review(evidence, inspection.template) }.getOrNull() }
        }
        gemmaBackend = structurer.backend
        return Reviewer.decide(findings, issues.size, model)
    }

    /** The link to other phones running DeskLess; see [Link]. */
    val link = Link(context, voice, ::takeIn) { notice = it }

    init {
        if (inspection.inspector.isNotBlank()) link.rename(inspection.inspector)
    }

    /** Links with a phone whose QR code was scanned. */
    fun pairWith(peer: Peer) {
        if (link.pair(peer)) {
            haptics.click()
            link.start()
            notice = "Linking with ${peer.name}. Keep DeskLess open on both phones."
        } else {
            notice = "That is this phone's own code."
        }
    }

    /**
     * Sends a report and its working files to the linked phones, with nobody asked to accept. Returns a
     * line for the screen; the phone says "Report sent" when it has gone.
     */
    fun sendReport(pdf: File): String {
        val entry = reports.firstOrNull { it.fileName == pdf.name }
        val title = entry?.place?.takeIf { it.isNotBlank() }?.let { "The $it report" } ?: "The report"
        val pack = File(cacheDir, "send-${pdf.nameWithoutExtension}.zip")
        try {
            ReportPack.zip(pdf, entry?.let(library::describe) ?: JSONObject(), pack)
        } catch (e: Exception) {
            return "Could not pack the report to send it."
        }
        val now = link.send(pack, title)
        return when {
            now < 0 -> "No phone is linked yet."
            now == 0 -> "Waiting. It goes by itself when a linked phone is in range with DeskLess open."
            now == 1 -> "Sending to the linked phone…"
            else -> "Sending to $now linked phones…"
        }
    }

    /** Takes in a report that arrived over the link and lists it with the others. Runs off the main thread. */
    private fun takeIn(pack: File, from: Peer): String? {
        val (pdf, about) = ReportPack.unzip(pack, library.folder, from.id) ?: return null
        val entry = library.receive(pdf, about, from.name)
        scope.launch {
            reports.removeAll { it.fileName == entry.fileName }
            reports.add(0, entry)
            haptics.filled()
        }
        return entry.place.takeIf { it.isNotBlank() }?.let { "The $it report" } ?: "A report"
    }

    /** Keeps the phone finding its position while the camera is open, so a picture is stamped at once. */
    fun watchPosition(on: Boolean) = if (on) locator.warm() else locator.cool()

    fun fileOf(name: String): File = store.file(name)

    fun photoOf(issue: Issue): File = store.file(issue.photoFile)

    fun newIssueId(): String = UUID.randomUUID().toString().take(8)

    fun addIssue(id: String, photoFile: String, stamp: Stamp? = null): Issue {
        val issue = Issue(
            id = id,
            createdAt = System.currentTimeMillis(),
            photoFile = photoFile,
            stamp = forNewIssue(stamp),
            place = place,
            location = Readings.whereKnown(place, stamp, null, null),
        )
        issues.add(issue)
        persist()
        stampLocation(id)
        return issue
    }

    /** The first issue of a walk has no previous one to count steps from. */
    private fun forNewIssue(stamp: Stamp?): Stamp? = if (issues.isEmpty()) stamp?.copy(steps = null) else stamp

    /** Attaches a reading taken with one of the phone's instruments. */
    fun addReading(id: String, reading: Reading) {
        // One reading of each kind: a new one replaces the last, so the report never shows two slopes.
        edit(id) { it.copy(readings = it.readings.filter { old -> old.kind != reading.kind } + reading) }
        haptics.click()
    }

    fun removeReading(id: String, reading: Reading) = edit(id) { it.copy(readings = it.readings - reading) }

    /** The place whose NFC tag was tapped last; new issues are filed there. Blank when none. */
    var place by mutableStateOf("")
        private set

    /** The serial number of a tag the phone has not seen before, waiting for the user to name it. */
    var newTag by mutableStateOf<String?>(null)
        private set

    /** Text found written on that tag, offered as its name. */
    var newTagHint = ""
        private set

    val knownTags: Int
        get() = tags.size

    /** Called when the phone is tapped on an NFC tag. [written] is any text the tag itself carries. */
    fun onTag(id: String, written: String?) {
        haptics.click()
        val known = tags.nameOf(id)
        if (known == null) {
            newTagHint = written.orEmpty()
            newTag = id
        } else {
            arriveAt(known)
        }
    }

    /** Names the tag that was just tapped; a blank name forgets the tap. */
    fun nameNewTag(name: String) {
        val id = newTag ?: return
        newTag = null
        if (name.isBlank()) return
        tags.name(id, name.trim())
        arriveAt(name.trim())
    }

    /** Gives the current place a new name on every tag that carries it; a blank name forgets those tags. */
    fun renamePlace(name: String) {
        tags.rename(place, name.trim())
        place = name.trim()
    }

    fun leavePlace() {
        place = ""
    }

    private fun arriveAt(name: String) {
        place = name
        // On an issue's own screen the tap files that issue there; anywhere else it sets where the next ones go.
        val open = (screen as? Screen.Edit)?.id
        if (open != null) {
            edit(open) {
                it.copy(
                    place = name,
                    location = if (locationIsPhones(it)) {
                        Readings.whereKnown(name, it.stamp, it.lat, it.lon)
                    } else {
                        Readings.location(name, it.location, null)
                    },
                )
            }
            notice = "Filed at $name."
        } else {
            notice = "At $name. New issues are filed here."
        }
    }

    /** Whether the user has let the app read the phone's position. */
    val locationAllowed: Boolean
        get() = locator.allowed

    /** Records where the phone is on the issue, when a position arrives; it may take a few seconds. */
    private fun stampLocation(id: String) {
        locator.locate { fix ->
            if (fix != null) edit(id) {
                it.copy(
                    lat = fix.lat, lon = fix.lon, accuracy = fix.accuracy,
                    // A location nobody has said or typed is the phone's own, so the position joins it.
                    location = if (locationIsPhones(it)) Readings.whereKnown(it.place, it.stamp, fix.lat, fix.lon) else it.location,
                )
            }
        }
    }

    /** True while the Location field holds nothing, or only what the phone filled in by itself. */
    private fun locationIsPhones(issue: Issue): Boolean =
        issue.location.isBlank() ||
            issue.location == Readings.whereKnown(issue.place, issue.stamp, issue.lat, issue.lon) ||
            issue.location == Readings.whereKnown(issue.place, issue.stamp, null, null)

    /** Ids of issues whose repair picture is being compared with the original right now. */
    val verifying = mutableStateListOf<String>()

    /**
     * Stores the picture taken after the repair and has the model compare it with the original. Without
     * the model the picture is kept and the inspector decides.
     */
    fun addFixPhoto(id: String, photoFile: String) {
        val issue = issues.firstOrNull { it.id == id } ?: return
        issue.fixPhoto?.let { store.file(it).delete() }
        edit(id) { it.copy(fixPhoto = photoFile, fixedAt = System.currentTimeMillis(), fixCheck = Check.NONE, fixSeen = "") }
        if (!gemmaReady || id in verifying) return
        verifying.add(id)
        val problem = listOf(issue.title, issue.note).filter { it.isNotBlank() }.joinToString(". ")
        val before = photoOf(issue)
        val after = store.file(photoFile)
        scope.launch {
            val result = withContext(modelWork) { runCatching { structurer.verifyFix(problem, before, after) } }
            gemmaBackend = structurer.backend
            verifying.remove(id)
            result
                .onSuccess { (check, seen) -> edit(id) { it.copy(fixCheck = check, fixSeen = seen) } }
                .onFailure { notice = "The on-device model could not compare the two pictures: ${it.message}" }
        }
    }

    /** The inspector's own word on the repair: fixed, or not after all. */
    fun confirmFixed(id: String) = edit(id) { it.copy(fixCheck = Check.CONFIRMED) }

    fun reopen(id: String) {
        issues.firstOrNull { it.id == id }?.fixPhoto?.let { store.file(it).delete() }
        edit(id) { it.copy(fixPhoto = null, fixedAt = 0, fixCheck = Check.NONE, fixSeen = "") }
    }

    /**
     * Has the model write the short briefing that opens a report. Null without the model or if it fails;
     * the report is then built without one.
     */
    suspend fun writeBriefing(inspection: Inspection, issues: List<Issue>): String? {
        if (!gemmaReady || issues.isEmpty()) return null
        // What is urgent is decided here, not by the model: open issues only, the most severe first, each
        // keeping its number from the walk.
        val open = issues.withIndex().filter { !it.value.resolved }.sortedByDescending { it.value.severity.ordinal }
        val fixed = issues.size - open.size
        if (open.isEmpty()) return "All ${issues.size} issues found on this walk have been fixed and confirmed."
        val lines = open.joinToString("\n") { (i, issue) ->
            listOfNotNull(
                "Issue ${i + 1}: ${issue.title.ifBlank { issue.note.take(60).ifBlank { "not described yet" } }}",
                "severity ${issue.severity.label}",
                issue.category.takeIf { it.isNotBlank() }?.let { "type $it" },
                // Only a place somebody named: compass points and coordinates do not help a briefing.
                issue.location.takeIf { it.isNotBlank() && !locationIsPhones(issue) }?.let { "at $it" },
                issue.owner.takeIf { it.isNotBlank() }?.let { "${inspection.template.ownerLabel.lowercase()} $it" },
                issue.dueDate.takeIf { it.isNotBlank() }?.let { "due $it" },
            ).joinToString("; ")
        } + if (fixed > 0) "\nAlready fixed and not listed: $fixed." else ""
        val first = open.take(3).map { it.index + 1 }
        val result = withContext(modelWork) { runCatching { structurer.brief(lines, first, inspection.template) } }
        gemmaBackend = structurer.backend
        return result.getOrNull()
    }

    /** The average time the model took per issue in this walk, or null when it has not run. */
    fun averageAiSeconds(): Float? =
        issues.map { it.aiSeconds }.filter { it > 0f }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    /**
     * Adds an issue that was filmed: the clip, stills from it (the middle one goes in the report) and the
     * voice recorded while filming, which is turned into the note straight away.
     */
    fun addVideoIssue(
        id: String,
        videoFile: String,
        frames: List<String>,
        voiceFile: String,
        voiceSeconds: Int,
        stamp: Stamp? = null,
    ) {
        issues.add(
            Issue(
                id = id,
                createdAt = System.currentTimeMillis(),
                photoFile = frames[frames.size / 2],
                videoFile = videoFile,
                frames = frames,
                voiceFile = voiceFile,
                voiceSeconds = voiceSeconds,
                stamp = forNewIssue(stamp),
                place = place,
                location = Readings.whereKnown(place, stamp, null, null),
            )
        )
        persist()
        stampLocation(id)
        transcribe(id)
    }

    /** The pictures the model should look at: the stills of a video issue, otherwise the one photo. */
    fun picturesOf(issue: Issue): List<File> =
        if (issue.frames.isEmpty()) listOf(photoOf(issue)) else issue.frames.map(store::file)

    /** Swaps in a retaken photo, drops any video the issue had, and has the model look again. */
    fun replacePhoto(id: String, photoFile: String, stamp: Stamp? = null) {
        val old = issues.firstOrNull { it.id == id } ?: return
        (old.frames + old.photoFile + listOfNotNull(old.videoFile)).distinct().forEach { store.file(it).delete() }
        edit(id) {
            it.copy(
                photoFile = photoFile, videoFile = null, frames = emptyList(), check = Check.NONE, seen = "",
                // The new picture brings its own readings; the walk to the spot was counted the first time.
                stamp = stamp?.copy(steps = it.stamp?.steps) ?: it.stamp,
            )
        }
        fillFields(id)
    }

    /** The inspector overrules the model's doubt about the picture. */
    fun confirmPicture(id: String) = edit(id) { it.copy(check = Check.CONFIRMED) }

    /** Applies [change] to the current copy of the issue, so a slow caller cannot overwrite newer edits. */
    fun edit(id: String, change: (Issue) -> Issue) {
        val at = issues.indexOfFirst { it.id == id }
        if (at < 0) return
        issues[at] = change(issues[at])
        persist()
    }

    fun delete(id: String) {
        val at = issues.indexOfFirst { it.id == id }
        if (at < 0) return
        deleteFiles(issues.removeAt(at))
        persist()
    }

    private fun deleteFiles(issue: Issue) {
        (issue.frames + issue.photoFile + listOfNotNull(issue.voiceFile, issue.videoFile, issue.fixPhoto)).distinct()
            .forEach { store.file(it).delete() }
    }

    fun updateInspection(site: String, inspector: String) {
        inspection = inspection.copy(site = site, inspector = inspector)
        // Other phones know this one by the inspector's name.
        if (inspector.isNotBlank()) link.rename(inspector)
        persist()
    }

    /** Clears the issues with their photos and voice notes. Reports already built stay on the phone. */
    fun startNewInspection() {
        issues.forEach(::deleteFiles)
        issues.clear()
        inspection = Inspection(inspector = inspection.inspector, template = inspection.template)
        filter = Filter.All
        place = ""
        persist()
    }

    /**
     * Turns the issue's voice note into English text and puts it in the note. Runs on in the background if
     * the user moves to another screen. A note re-recorded meanwhile is transcribed again afterwards.
     */
    fun transcribe(id: String) {
        val voice = issues.firstOrNull { it.id == id }?.voiceFile ?: return
        if (!speechReady) return
        if (id in transcribing) {
            transcribeAgain.add(id)
            return
        }
        transcribing.add(id)
        scope.launch {
            val result = withContext(modelWork) {
                runCatching { transcriber.toEnglish(store.file(voice)) }
            }
            transcribing.remove(id)
            if (transcribeAgain.remove(id)) {
                transcribe(id)
                return@launch
            }
            result
                .onSuccess { text ->
                    if (text.isBlank()) {
                        notice = "No speech was heard in that voice note."
                    } else {
                        edit(id) { it.copy(note = text) }
                        fillFields(id)
                    }
                }
                .onFailure { notice = "Could not turn the voice note into text: ${it.message}" }
        }
    }

    /**
     * Fills issue, location, severity, owner and due date from the note (and the photo, with Gemma).
     * Without the Gemma file it falls back to simple rules, which only give a title and a severity.
     */
    fun fillFields(id: String) {
        val issue = issues.firstOrNull { it.id == id } ?: return
        val note = issue.note.trim()
        if (note.isEmpty() || id in structuring) return
        if (!gemmaReady) {
            applyFields(id, Rules.structure(note))
            return
        }
        structuring.add(id)
        val pictures = picturesOf(issue)
        val template = inspection.template
        val readings = Readings.forModel(issue)
        scope.launch {
            val result = withContext(modelWork) { runCatching { structurer.structure(note, pictures, template, readings) } }
            gemmaBackend = structurer.backend
            structuring.remove(id)
            result
                .onSuccess {
                    applyFields(id, it, structurer.lastSeconds, structurer.backend.orEmpty())
                    // The motor says how it came out, so the inspector need not look: serious, or just done.
                    val rated = issues.firstOrNull { issue -> issue.id == id }?.severity ?: Severity.MEDIUM
                    if (rated.ordinal >= Severity.HIGH.ordinal) haptics.alert() else haptics.filled()
                }
                .onFailure {
                    applyFields(id, Rules.structure(note))
                    notice = "The on-device model could not fill the fields (${it.message}); used simple rules."
                }
        }
    }

    private fun applyFields(id: String, fields: Fields, seconds: Float = 0f, backend: String = "") = edit(id) {
        // An expiry date the model read off the pack and that has passed makes the issue expired stock,
        // rated High at least, whatever the note said.
        val expired = Expiry.parse(fields.expiry)?.let(Expiry::isExpired) == true
        val expiredType = inspection.template.categories.firstOrNull { type -> type.startsWith("Expired") }
        val severity = fields.severity ?: it.severity
        it.copy(
            title = fields.title.ifBlank { it.title },
            // The spoken location is joined to the tagged place and to where the camera was aimed.
            location = if (fields.location.isBlank()) {
                it.location
            } else {
                Readings.location(it.place, fields.location, Readings.aim(it.stamp?.pitch))
            },
            severity = if (expired && severity.ordinal < Severity.HIGH.ordinal) Severity.HIGH else severity,
            owner = fields.owner.ifBlank { it.owner },
            dueDate = fields.dueDate.ifBlank { it.dueDate },
            category = if (expired && expiredType != null) expiredType else fields.category.ifBlank { it.category },
            check = fields.check,
            seen = fields.seen,
            aiSeconds = seconds,
            aiBackend = backend,
            expiry = fields.expiry,
        )
    }

    fun importModelFolder(folder: Uri) = importModels { models.importFrom(folder, it) }

    fun importModelFiles(files: List<Uri>) = importModels { models.importFiles(files, it) }

    private fun importModels(copyModels: ((ModelStore.Progress) -> Unit) -> ModelStore.ImportResult) {
        if (importing) return
        importing = true
        importResult = null
        importProgress = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                copyModels { importProgress = it }
            }
            withContext(modelWork) {
                transcriber.release()
                structurer.release()
            }
            gemmaBackend = null
            speechReady = models.speechReady
            gemmaReady = models.gemma != null
            importResult = result
            importProgress = null
            importing = false
        }
    }

    private fun persist() = store.save(inspection, issues.toList())

    companion object {
        /** How issues with no type yet are named in filters and group headings. */
        const val UNSORTED = "Not sorted yet"
    }
}
