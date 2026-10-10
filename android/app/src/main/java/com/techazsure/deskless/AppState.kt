package com.techazsure.deskless

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.techazsure.deskless.account.AccountStore
import com.techazsure.deskless.account.Api
import com.techazsure.deskless.account.ApiError
import com.techazsure.deskless.account.LiveWalk
import com.techazsure.deskless.account.Outbox
import com.techazsure.deskless.account.PendingSignUp
import com.techazsure.deskless.account.Profile
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
import com.techazsure.deskless.models.ModelDownloads
import com.techazsure.deskless.models.ModelFetch
import com.techazsure.deskless.models.ModelStore
import com.techazsure.deskless.report.ReportEntry
import com.techazsure.deskless.report.ReportLibrary
import com.techazsure.deskless.report.ReportPack
import com.techazsure.deskless.report.Review
import com.techazsure.deskless.report.Reviewer
import com.techazsure.deskless.report.SignOff
import com.techazsure.deskless.speech.Transcriber
import com.techazsure.deskless.structure.Fields
import com.techazsure.deskless.structure.Rules
import com.techazsure.deskless.structure.Structurer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Adds a freshly built PDF to the library, with what the review made of it, and queues it for the office. */
    fun recordReport(file: File, inspection: Inspection, issues: List<Issue>, review: Review? = null) {
        val entry = library.record(file, inspection, issues, flagged = issues.count(::needsALook), review = review)
        reports.removeAll { it.fileName == entry.fileName }
        reports.add(0, entry)
        queueForOffice(file, inspection, issues, review)
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
     * Sends a report and its working files to the chosen linked phones ([to] holds their codes), with
     * nobody asked to accept. Each copy is sealed for the phone it is for, so a phone that carries it on
     * the way cannot open it. Returns a line for the screen when it could not be sent, otherwise null:
     * the report screen then follows each copy on its way.
     */
    fun sendReport(pdf: File, to: List<String>): String? {
        if (to.isEmpty()) return "Choose at least one phone."
        val entry = reports.firstOrNull { it.fileName == pdf.name }
        val title = entry?.place?.takeIf { it.isNotBlank() }?.let { "The $it report" } ?: "The report"
        val pack = File(cacheDir, "send-${pdf.nameWithoutExtension}.zip")
        try {
            ReportPack.zip(pdf, entry?.let(library::describe) ?: JSONObject(), pack)
        } catch (e: Exception) {
            return "Could not pack the report to send it."
        }
        val sealed = link.send(pack, pdf.name, title, to)
        pack.delete()
        return when {
            sealed == 0 -> "That phone has not been met since it was linked. Open DeskLess on both phones side by side once."
            sealed < to.size -> "Sent to $sealed of ${to.size}. The others have not been met since they were linked."
            else -> null
        }
    }

    /**
     * Takes in a report that arrived over the link, already opened, and lists it with the others. If the
     * user is not in the middle of something, the report is put in front of them straight away. Runs off
     * the main thread.
     */
    private fun takeIn(pack: File, from: Peer, via: String?): String? {
        val (pdf, about) = ReportPack.unzip(pack, library.folder, from.id) ?: return null
        val entry = library.receive(pdf, about, from.name)
        scope.launch {
            reports.removeAll { it.fileName == entry.fileName }
            reports.add(0, entry)
            haptics.filled()
            if (screen == Screen.Home || screen == Screen.Reports || screen == Screen.Sensors) {
                screen = Screen.Report(pdf.absolutePath, fromLibrary = true)
            }
        }
        return entry.place.takeIf { it.isNotBlank() }?.let { "The $it report" } ?: "A report"
    }

    /** Reports that came from other phones and have not been opened here yet, newest first. */
    val unread: List<ReportEntry>
        get() = reports.filter { it.unread }

    /** Notes that a received report has been looked at. */
    fun markRead(fileName: String) {
        val at = reports.indexOfFirst { it.fileName == fileName && it.unread }
        if (at < 0) return
        reports[at] = library.markRead(reports[at])
    }

    /** Keeps the phone finding its position while the camera is open, so a picture is stamped at once. */
    fun watchPosition(on: Boolean) = if (on) locator.warm() else locator.cool()

    // ---------------------------------------------------------------- the account
    //
    // The server is spoken to while signing up and signing in, and to send finished reports to the office.
    // Once signed in, the phone holds the sign-in and the person's details: capturing, the models and
    // building a report work with no network, and a report built offline is sent when the network is back.

    private val account = AccountStore(context)
    private val api = Api { account.server }

    /** The signed-in person; null when nobody is signed in. */
    var profile by mutableStateOf(account.profile)
        private set

    /** A sign-up that is waiting for its emailed code or for the administrator. */
    var pending by mutableStateOf(account.pending)
        private set

    /** The address of the account server. */
    var server by mutableStateOf(account.server)
        private set

    /** True once the app has been unlocked in this run, by signing in or with the fingerprint. */
    var unlocked by mutableStateOf(false)

    /** The signed-in person's photo, and a number that changes when the file does. */
    val accountPhoto: File get() = account.photo
    var photoVersion by mutableStateOf(0)
        private set

    /** How a sign-up is doing, as the server last said. [approver] is who decides it, in words. */
    data class SignUpStatus(val status: String, val uniqueId: String, val reason: String, val approver: String)

    fun useServer(address: String) {
        account.useServer(address)
        server = account.server
    }

    private suspend fun <T> online(call: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(call) }

    private fun why(error: Throwable): String = (error as? ApiError)?.message ?: "Something went wrong. Try again."

    /** Sends the sign-up form with the photo. Null when it went through; otherwise what to tell the person. */
    suspend fun signUp(fields: Map<String, String>, photo: File): String? =
        online { api.signUp(fields, photo) }.fold(
            onSuccess = { answer ->
                account.waitingFor(PendingSignUp(answer.getString("email"), answer.getString("ticket"), fields["name"].orEmpty()))
                pending = account.pending
                null
            },
            onFailure = ::why,
        )

    /**
     * Asks whose code this is, for someone signing up as [role]. The answer is a sentence for the form:
     * which company they are about to join and who will approve them, or why the code will not do.
     */
    suspend fun whoseCode(code: String, role: String): Result<String> =
        online { api.invite(code, role) }.map {
            "You are joining ${it.optString("company")}. ${it.optString("lead")} (${it.optString("lead_role")}) approves your sign-up."
        }

    /** Confirms the email address with the code that was emailed. */
    suspend fun verifyCode(code: String): String? {
        val waiting = pending ?: return "Sign up again."
        return online { api.verify(waiting.email, code) }.fold({ null }, ::why)
    }

    suspend fun resendCode(): String? {
        val waiting = pending ?: return "Sign up again."
        return online { api.resend(waiting.email) }.fold({ null }, ::why)
    }

    /** Asks the server how the sign-up is doing. */
    suspend fun signUpStatus(): Result<SignUpStatus> {
        val waiting = pending ?: return Result.failure(ApiError("Sign up again."))
        return online { api.status(waiting.email, waiting.ticket) }
            .map { SignUpStatus(it.optString("status"), it.optString("unique_id"), it.optString("reason"), it.optString("approver")) }
    }

    /** Drops a sign-up that was rejected or abandoned, so a new one can be started. */
    fun forgetSignUp() {
        account.waitingFor(null)
        pending = null
    }

    /**
     * Signs in with the unique ID and password. On success the sign-in and the person's details are kept on
     * the phone, their photo is fetched, and their name goes on the reports and to linked phones.
     */
    suspend fun signIn(uniqueId: String, password: String): String? =
        online {
            val answer = api.login(uniqueId.trim(), password)
            val token = answer.getString("token")
            api.photo(token, account.photo)
            token to Profile.from(answer.getJSONObject("profile"))
        }.fold(
            onSuccess = { (token, person) ->
                account.signedIn(token, person)
                pending = null
                profile = person
                unlocked = true
                photoVersion++
                if (inspection.inspector.isBlank()) updateInspection(inspection.site, person.name) else link.rename(person.name)
                toldToSignIn = false
                sendWalk()
                sendToOffice()
                null
            },
            onFailure = ::why,
        )

    suspend fun forgotPassword(uniqueId: String): String? = online { api.forgot(uniqueId.trim()) }.fold({ null }, ::why)

    suspend fun resetPassword(uniqueId: String, code: String, password: String): String? =
        online { api.reset(uniqueId.trim(), code.trim(), password) }.fold({ null }, ::why)

    // ---------------------------------------------------------------- reports to the office
    //
    // The walk goes to the company's server as it is made: each issue moments after it is raised, and
    // the report when it is built. The manager and the managing director see both on their dashboards.
    // Nobody has to press anything: with a network it goes at once, without one it waits on the phone and
    // goes when the network is back.

    private val outbox = Outbox(context)

    /** File names of the reports that wait to be sent to the office, and of those the office has. */
    val waitingForOffice = mutableStateListOf<String>().apply { addAll(outbox.waitingNames()) }
    val sentToOffice = mutableStateListOf<String>().apply { addAll(outbox.sent()) }
    private var sending = false
    private var toldToSignIn = false
    private val liveWalk = LiveWalk(context)
    private var walkSettling: Job? = null

    /** One thing at a time goes to the office, so a walk and its built report never cross on the way. */
    private val officeLine = Mutex()

    private fun queueForOffice(file: File, inspection: Inspection, issues: List<Issue>, review: Review?) {
        val listed = issues.toList()
        scope.launch {
            // Copying and shrinking the pictures takes a moment; the screen is not kept waiting for it.
            withContext(Dispatchers.IO) {
                runCatching { outbox.add(file, inspection, listed, review, SignOff.fileFor(file).isFile, store::file) }
            }
            showOutbox()
            sendToOffice()
        }
    }

    private fun showOutbox() {
        waitingForOffice.clear()
        waitingForOffice.addAll(outbox.waitingNames())
        sentToOffice.clear()
        sentToOffice.addAll(outbox.sent())
    }

    /**
     * Sends whatever waits in the outbox, oldest first. Called when a report is built, when the app
     * comes to the front and when the phone finds a network. With no network it simply stops and the
     * reports go on waiting.
     */
    fun sendToOffice() {
        if (sending || profile == null || account.token.isBlank()) return
        sending = true
        scope.launch {
            val trouble = withContext(Dispatchers.IO) {
                officeLine.withLock { sendWaitingReports() }
            }
            showOutbox()
            sending = false
            trouble?.let { notice = it }
        }
    }

    /** Sends each waiting report in turn. Returns what the person should be told, if anything. */
    private fun sendWaitingReports(): String? {
        var said: String? = null
        for (report in outbox.waiting { File(library.folder, it) }) {
            val error = runCatching { api.sendReport(account.token, report.about, report.pdf, report.pictures) }.exceptionOrNull()
            if (error == null) {
                outbox.done(report)
                // The office now has this walk as the report tells it; it need not go again as a walk.
                liveWalk.remember(report.about)
                continue
            }
            val refusal = error as? ApiError
            if (refusal?.status == 401) {
                // Said once in a run of the app, not at every try.
                if (!toldToSignIn) said = "Sign in again to send your reports to the office. They are kept on the phone until then."
                toldToSignIn = true
            } else if (refusal?.refused == true && (refusal.status == 413 || refusal.status == 422)) {
                // The DeskLess server itself says it will never take this one; the others still can go.
                outbox.drop(report)
                said = "A report could not be sent to the office: ${error.message}"
                continue
            }
            // Anything else, such as no network, a wrong address or a server that is not up yet,
            // leaves the report waiting on the phone for the next try.
            break
        }
        return said
    }

    /** Notes that the walk has changed. A few seconds later, when the typing has stopped, it is sent on. */
    private fun walkChanged() {
        if (profile == null) return
        walkSettling?.cancel()
        walkSettling = scope.launch {
            delay(WALK_SETTLES_MS)
            sendWalk()
        }
    }

    /**
     * Sends the walk in progress to the office, so an issue is on the dashboards moments after it is
     * raised, before any report is built. Only what has changed travels: the words, and the pictures the
     * office does not have yet. Called when the walk changes, when the app comes to the front and when
     * the phone finds a network; with no network it does nothing and the next call sends the walk as it
     * then stands.
     */
    fun sendWalk() {
        if (profile == null || account.token.isBlank()) return
        // A note still being turned into text or into fields would go out half done; it goes when it is ready.
        if (transcribing.isNotEmpty() || structuring.isNotEmpty()) {
            walkChanged()
            return
        }
        val walk = inspection
        val listed = issues.toList()
        // A walk with nothing in it is not told of, unless the office has issues of it that are now gone.
        if (listed.isEmpty() && !liveWalk.knows(walk)) return
        scope.launch {
            withContext(Dispatchers.IO) {
                officeLine.withLock {
                    runCatching {
                        val (about, pictures) = liveWalk.prepare(walk, listed, store::file)
                        if (pictures.isNotEmpty() || !liveWalk.unchanged(about)) {
                            api.sendReport(account.token, about, null, pictures)
                            liveWalk.done(walk, listed, about)
                        }
                    }
                }
            }
        }
    }

    /**
     * What the app does whenever it comes to the front or finds a network: says it is here, sends what
     * waits, and goes on fetching its model files if the phone still lacks any.
     */
    fun comeOnline() {
        checkIn()
        sendWalk()
        sendToOffice()
        fetchModelsIfMissing()
    }

    /**
     * Tells the server this person is using DeskLess now, so the dashboards of those above show them
     * online, and takes up any change to their own details (a new code, a new manager). Called when the
     * app comes to the front and when the phone finds a network; with no network it does nothing.
     */
    fun checkIn() {
        if (profile == null || account.token.isBlank()) return
        scope.launch {
            online { Profile.from(api.me(account.token)) }.onSuccess { person ->
                if (person != profile) {
                    account.signedIn(account.token, person)
                    profile = person
                }
            }
        }
    }

    /**
     * The address that opens this person's dashboard in a browser, already signed in: the link in it
     * works once and for two minutes. Needs the network.
     */
    suspend fun dashboardAddress(): Result<String> =
        online { account.server.trimEnd('/') + "/dashboard#code=" + api.webLink(account.token) }
            .recoverCatching { throw ApiError(if ((it as? ApiError)?.status == 401) "Sign in again to open your dashboard." else why(it)) }

    /** Signs out of this phone. The inspection in progress and the reports stay where they are. */
    fun signOut() {
        account.signOut()
        profile = null
        unlocked = false
        screen = Screen.Home
    }

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

    // ---------------------------------------------------------------- fetching the models
    //
    // A newly installed app fetches its model files by itself, over Wi-Fi, from where they are published;
    // nothing has to be sent to the phone by hand. Android's own download service does the fetching, so
    // it goes on while the app is closed. Importing from a folder stays, for a phone with no internet.

    private val downloads = ModelDownloads(context, models)

    /** Where the fetching of the model files stands. */
    var modelFetch by mutableStateOf(
        ModelFetch(
            phase = if (models.speechReady && models.gemma != null) ModelFetch.Phase.COMPLETE else ModelFetch.Phase.IDLE,
            missingBytes = downloads.missing().sumOf { it.bytes },
        )
    )
        private set
    private var modelsWatch: Job? = null
    private var modelProblem: String? = null

    /** A try failed in this run: the fetching is not started again by itself, only when the person asks. */
    private var modelsHeld = false

    /** Fetches whatever model files the phone lacks, unless the person stopped it or a try has just failed. */
    private fun fetchModelsIfMissing() {
        scope.launch {
            if (!importing && !modelsHeld) {
                val trouble = withContext(Dispatchers.IO) {
                    if (downloads.stopped || downloads.missing().isEmpty()) null else downloads.start(downloads.mobileData)
                }
                if (trouble != null) {
                    modelProblem = trouble
                    modelsHeld = true
                }
            }
            watchModels()
        }
    }

    /** The person asks for the models: over Wi-Fi only, or allowing mobile data as well. */
    fun fetchModels(mobileData: Boolean) {
        modelsHeld = false
        scope.launch {
            modelProblem = withContext(Dispatchers.IO) { if (mobileData) downloads.useMobileData() else downloads.start(false) }
            modelsHeld = modelProblem != null
            watchModels()
        }
    }

    fun stopFetchingModels() {
        scope.launch {
            withContext(Dispatchers.IO) { downloads.stop() }
            modelProblem = null
            watchModels()
        }
    }

    /** Follows the fetching once a second while there is something to follow, and takes in what has arrived. */
    private fun watchModels() {
        if (modelsWatch?.isActive == true) return
        modelsWatch = scope.launch {
            while (true) {
                val look = withContext(Dispatchers.IO) { downloads.look(modelProblem) }
                if (look.failed) modelsHeld = true
                modelProblem = look.fetch.problem
                modelFetch = look.fetch
                if (look.toCheck) {
                    val problems = withContext(Dispatchers.IO) { downloads.settle() }
                    if (problems.isNotEmpty()) {
                        modelProblem = problems.joinToString(" ")
                        modelsHeld = true
                    }
                    modelsChanged()
                    continue
                }
                if (!look.fetch.busy) break
                delay(1000)
            }
        }
    }

    /** Stops following the fetching while nobody is looking; the fetching itself goes on. */
    fun restModelsWatch() {
        modelsWatch?.cancel()
    }

    /** Model files have arrived or gone: whatever was loaded is let go, and the screens are told what is ready now. */
    private suspend fun modelsChanged() {
        val speech = models.speechReady
        val gemma = models.gemma != null
        if (speech == speechReady && gemma == gemmaReady) return
        withContext(modelWork) {
            transcriber.release()
            structurer.release()
        }
        gemmaBackend = null
        val first = !speechReady && speech && !gemma
        speechReady = speech
        gemmaReady = gemma
        if (speech && gemma) notice = "The AI models are on this phone now."
        else if (first) notice = "Speech to English is ready. The large model is still on its way."
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
            // What came by import is no longer fetched.
            watchModels()
        }
    }

    private fun persist() {
        store.save(inspection, issues.toList())
        walkChanged()
    }

    companion object {
        /** How long the walk is left to settle after a change before it is sent to the office. */
        private const val WALK_SETTLES_MS = 5_000L

        /** How issues with no type yet are named in filters and group headings. */
        const val UNSORTED = "Not sorted yet"
    }
}
