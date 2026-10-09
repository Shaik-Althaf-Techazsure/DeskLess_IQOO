package com.techazsure.deskless

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.techazsure.deskless.capture.Locator
import com.techazsure.deskless.data.Check
import com.techazsure.deskless.data.Inspection
import com.techazsure.deskless.structure.Expiry
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.IssueStore
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.data.Template
import com.techazsure.deskless.models.ModelStore
import com.techazsure.deskless.report.ReportEntry
import com.techazsure.deskless.report.ReportLibrary
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

    /** Every report built on this phone. */
    data object Reports : Screen
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
    private val store: IssueStore,
    private val models: ModelStore,
    private val library: ReportLibrary,
    private val locator: Locator,
    cacheDir: File,
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

    /** Adds a freshly built PDF to the library. */
    fun recordReport(file: File, inspection: Inspection, issues: List<Issue>) {
        val entry = library.record(file, inspection, issues, flagged = issues.count(::needsALook))
        reports.removeAll { it.fileName == entry.fileName }
        reports.add(0, entry)
    }

    fun fileOf(name: String): File = store.file(name)

    fun photoOf(issue: Issue): File = store.file(issue.photoFile)

    fun newIssueId(): String = UUID.randomUUID().toString().take(8)

    fun addIssue(id: String, photoFile: String): Issue {
        val issue = Issue(id = id, createdAt = System.currentTimeMillis(), photoFile = photoFile)
        issues.add(issue)
        persist()
        stampLocation(id)
        return issue
    }

    /** Whether the user has let the app read the phone's position. */
    val locationAllowed: Boolean
        get() = locator.allowed

    /** Records where the phone is on the issue, when a position arrives; it may take a few seconds. */
    private fun stampLocation(id: String) {
        locator.locate { fix ->
            if (fix != null) edit(id) { it.copy(lat = fix.lat, lon = fix.lon, accuracy = fix.accuracy) }
        }
    }

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
                issue.location.takeIf { it.isNotBlank() }?.let { "at $it" },
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
    fun addVideoIssue(id: String, videoFile: String, frames: List<String>, voiceFile: String, voiceSeconds: Int) {
        issues.add(
            Issue(
                id = id,
                createdAt = System.currentTimeMillis(),
                photoFile = frames[frames.size / 2],
                videoFile = videoFile,
                frames = frames,
                voiceFile = voiceFile,
                voiceSeconds = voiceSeconds,
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
    fun replacePhoto(id: String, photoFile: String) {
        val old = issues.firstOrNull { it.id == id } ?: return
        (old.frames + old.photoFile + listOfNotNull(old.videoFile)).distinct().forEach { store.file(it).delete() }
        edit(id) {
            it.copy(photoFile = photoFile, videoFile = null, frames = emptyList(), check = Check.NONE, seen = "")
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
        persist()
    }

    /** Clears the issues with their photos and voice notes. Reports already built stay on the phone. */
    fun startNewInspection() {
        issues.forEach(::deleteFiles)
        issues.clear()
        inspection = Inspection(inspector = inspection.inspector, template = inspection.template)
        filter = Filter.All
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
        scope.launch {
            val result = withContext(modelWork) { runCatching { structurer.structure(note, pictures, template) } }
            gemmaBackend = structurer.backend
            structuring.remove(id)
            result
                .onSuccess { applyFields(id, it, structurer.lastSeconds, structurer.backend.orEmpty()) }
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
            location = fields.location.ifBlank { it.location },
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
