package com.techazsure.deskless

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.techazsure.deskless.account.AccountStore
import com.techazsure.deskless.account.Api
import com.techazsure.deskless.account.ApiError
import com.techazsure.deskless.account.Desk
import com.techazsure.deskless.account.DeskJob
import com.techazsure.deskless.account.Person
import com.techazsure.deskless.account.LiveWalk
import com.techazsure.deskless.account.Outbox
import com.techazsure.deskless.account.PendingSignUp
import com.techazsure.deskless.account.Profile
import com.techazsure.deskless.bench.Bench
import com.techazsure.deskless.bench.Outcome
import com.techazsure.deskless.bench.Scorecard
import com.techazsure.deskless.bench.Wav
import com.techazsure.deskless.capture.Haptics
import com.techazsure.deskless.capture.Locator
import com.techazsure.deskless.capture.SiteSensors
import com.techazsure.deskless.capture.TagBook
import com.techazsure.deskless.capture.WavRecorder
import com.techazsure.deskless.data.Check
import com.techazsure.deskless.data.Inspection
import com.techazsure.deskless.structure.Expiry
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.IssueStore
import com.techazsure.deskless.data.Reading
import com.techazsure.deskless.data.Readings
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.data.OwnTypes
import com.techazsure.deskless.data.Stamp
import com.techazsure.deskless.data.Template
import com.techazsure.deskless.link.Link
import com.techazsure.deskless.link.Peer
import com.techazsure.deskless.link.Talk
import com.techazsure.deskless.link.TalkMessage
import com.techazsure.deskless.link.VOICE_TITLE
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
import com.techazsure.deskless.speech.Spoken
import com.techazsure.deskless.speech.Transcriber
import com.techazsure.deskless.structure.Asking
import com.techazsure.deskless.structure.Commands
import com.techazsure.deskless.structure.DeskAsk
import com.techazsure.deskless.structure.DeskCommands
import com.techazsure.deskless.structure.DeskPlan
import com.techazsure.deskless.structure.DueDates
import com.techazsure.deskless.structure.Field
import com.techazsure.deskless.structure.Fields
import com.techazsure.deskless.structure.Gap
import com.techazsure.deskless.structure.Guard
import com.techazsure.deskless.structure.Memory
import com.techazsure.deskless.structure.Plan
import com.techazsure.deskless.structure.Rules
import com.techazsure.deskless.structure.Source
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
     * picture, or with [fix] it adds the picture taken after the repair. With [jobId] it photographs the
     * repair of a job that came from the office, and goes back to the desk.
     */
    data class Capture(val retakeId: String? = null, val fix: Boolean = false, val jobId: String? = null) : Screen
    data object Models : Screen

    /** The phone's sensors, live: what DeskLess measures with and whether each one is working. */
    data object Sensors : Screen

    /** Every report built on this phone. */
    data object Reports : Screen

    /** The camera, looking for another phone's QR code to link with. */
    data object Scan : Screen

    /** A question about the walk, asked aloud or typed, answered on the phone. */
    data object Ask : Screen

    /** Push to talk with the linked phones, with no internet. */
    data object Radio : Screen

    /** The app's own measurements of its models on this phone: how right, how fast, how much battery. */
    data object Scorecard : Screen

    /** What waits for the signed-in person at the office, said aloud and acted on by voice. */
    data object MyDesk : Screen
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
    private val app: Context = context.applicationContext
    private val settings = context.getSharedPreferences("walk-settings", Context.MODE_PRIVATE)

    /** The language notes are spoken in; the speech model is told, and writes them down in English. */
    var spoken by mutableStateOf(Spoken.named(settings.getString("spoken", null)))
        private set

    fun speakIn(choice: Spoken) {
        spoken = choice
        settings.edit().putString("spoken", choice.name).apply()
    }

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
        issues.forEach { learn(it.id) }
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
        // A spoken message, not a report: it joins the others and is played at once.
        val talkId = UUID.randomUUID().toString().take(8)
        talk.unwrap(pack, talkId)?.let { seconds ->
            val message = TalkMessage(talkId, from.id, from.name, System.currentTimeMillis(), seconds, heard = false)
            scope.launch {
                talks.add(message)
                keepTalks()
                haptics.filled()
                notice = "${from.name} is talking" + if (via != null) " (carried by $via)." else "."
                playTalk(message)
            }
            return VOICE_TITLE
        }
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

    // ---------------------------------------------------------------- walkie-talkie
    //
    // Push to talk with the linked phones, with no internet: what is said while the button is held goes
    // through the same sealed link as the reports, and is played on the other phone as it arrives.

    private val talk = Talk(context)

    /** What has been said and heard, oldest first. */
    val talks = mutableStateListOf<TalkMessage>().apply { addAll(talk.load()) }

    /** The message being played right now, by its id. */
    var talkPlaying by mutableStateOf<String?>(null)
        private set

    fun startTalking(): Boolean = talk.start()

    fun cancelTalking() {
        talk.stop()
    }

    /**
     * The button was let go after [seconds]: the message is sealed for every linked phone and sent.
     * Returns what to tell the person when it could not be, otherwise nothing.
     */
    fun stopTalking(seconds: Int): String? {
        if (!talk.stop() || seconds < 1) return "Hold the button while you speak, then let go."
        val to = link.peers.map { it.id }
        if (to.isEmpty()) return "Link a phone first: the yellow button on the first screen."
        val id = UUID.randomUUID().toString().take(8)
        if (link.send(talk.wrap(seconds), "talk-$id", VOICE_TITLE, to) == 0) {
            return "The linked phones have not met this one since linking. Open DeskLess on both, side by side, once."
        }
        talk.keepOwn(id)
        talks.add(TalkMessage(id, "", "You", System.currentTimeMillis(), seconds, to))
        keepTalks()
        haptics.click()
        return null
    }

    fun playTalk(message: TalkMessage) {
        val at = talks.indexOfFirst { it.id == message.id }
        if (at >= 0 && !talks[at].heard) {
            talks[at] = talks[at].copy(heard = true)
            keepTalks()
        }
        talkPlaying = message.id
        talk.play(message) { if (talkPlaying == message.id) talkPlaying = null }
    }

    fun quietTalk() {
        talk.quiet()
        talkPlaying = null
    }

    private fun keepTalks() {
        val kept = talk.save(talks.toList())
        if (kept.size != talks.size) {
            talks.clear()
            talks.addAll(kept)
        }
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

    /** True in the edition handed to the people who use a place: it shows their side and nothing else. */
    val userOnly = Edition.userOnly(context)
    private val account = AccountStore(context).also { if (userOnly) it.keepOnlyUsers() }
    private val filesDir: File = context.filesDir
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

    /** The app as someone who uses a place has it: telling the place what they noticed, and hearing what comes back. */
    val user = UserSide(
        context, scope, modelWork, transcriber, structurer, voice, haptics, api,
        token = { account.token }, who = { profile }, language = { spoken.code },
        speechReady = { speechReady }, gemmaReady = { gemmaReady }, cacheDir = cacheDir,
    )

    /** The other people signed in on this phone; it changes to one of them with a tap. */
    val otherAccounts = mutableStateListOf<Profile>().apply { addAll(account.others) }

    /** True while another person is signing in beside the one in use. */
    var addingAccount by mutableStateOf(false)

    /** Changes to another person signed in on this phone: their desk, their name on what is sent. */
    fun switchAccount(uniqueId: String) {
        val leaving = profile
        if (account.switchTo(uniqueId)) becomeWhoIsSignedIn(leaving)
    }

    /**
     * The phone has changed hands: everything that belongs to a person follows whoever is signed in now.
     * A walk in hand stays with the one who began it, and is not sent to the office under another name.
     */
    private fun becomeWhoIsSignedIn(leaving: Profile?) {
        if (leaving != null && issues.isNotEmpty() && settings.getString(WALK_OWNER, "").isNullOrBlank()) {
            settings.edit().putString(WALK_OWNER, leaving.uniqueId).apply()
        }
        val person = account.profile
        profile = person
        otherAccounts.clear()
        otherAccounts.addAll(account.others)
        addingAccount = false
        toldToSignIn = false
        photoVersion++
        voice.hush()
        desk = null
        deskBriefing = ""
        deskSaid = ""
        deskAnswer = ""
        deskPlan = null
        deskTrouble = null
        repair = null
        deskFile.delete()
        user.changedHands()
        if (person == null) return
        // Someone who uses a place has one first screen: wherever the phone was, it goes there.
        if (person.usesAPlace) screen = Screen.Home
        link.rename(person.name)
        notice = "This phone is now ${person.name}, ${person.roleName}."
        scope.launch {
            online { api.photo(account.token, account.photo) }
            photoVersion++
        }
        checkIn()
        refreshDesk(aloud = screen == Screen.MyDesk)
    }

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

    /** The short sign-up of someone who uses a place: a name, an email address and a password. Null when it went through. */
    suspend fun signUpUser(name: String, email: String, phone: String, password: String): String? =
        online { api.signUpUser(name, email, phone, password) }.fold(
            onSuccess = { answer ->
                account.waitingFor(PendingSignUp(answer.getString("email"), answer.getString("ticket"), name))
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
            val person = Profile.from(answer.getJSONObject("profile"))
            // The edition for people who use a place has nothing of an organisation's own work in it.
            if (userOnly && !person.usesAPlace) {
                throw ApiError("This is DeskLess User, for the people who use a place. As ${person.roleName}, sign in on the DeskLess app of your organisation.")
            }
            api.photo(token, account.photo)
            token to person
        }.fold(
            onSuccess = { (token, person) ->
                val leaving = profile
                account.signedIn(token, person)
                pending = null
                unlocked = true
                // Someone signing in beside another takes the phone over; the other stays signed in.
                becomeWhoIsSignedIn(leaving)
                if (inspection.inspector.isBlank()) updateInspection(inspection.site, person.name)
                // The company's own report types, and the one it starts its walks with, come with the first check-in.
                checkIn()
                sendWalk()
                sendToOffice()
                null
            },
            onFailure = ::why,
        )

    suspend fun forgotPassword(uniqueId: String): String? = online { api.forgot(uniqueId.trim()) }.fold({ null }, ::why)

    /**
     * Checks the code that was emailed for a forgotten password. Gives the pass to choose a new password
     * with, or what to tell the person.
     */
    suspend fun checkResetCode(uniqueId: String, code: String): Pair<String?, String?> =
        online { api.verifyReset(uniqueId.trim(), code.trim()).getString("ticket") }.fold({ it to null }, { null to why(it) })

    suspend fun resetPassword(ticket: String, password: String): String? =
        online { api.reset(ticket, password) }.fold({ null }, ::why)

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
        // A walk goes to the office under the name of the person who began it, whoever holds the phone now.
        val owner = settings.getString(WALK_OWNER, "")
        if (!owner.isNullOrBlank() && owner != profile?.uniqueId) return
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
        // What has newly arrived at the person's desk is announced; the briefing waits until the desk is opened.
        refreshDesk(aloud = false)
        user.comeOnline()
    }

    /**
     * Tells the server this person is using DeskLess now, so the dashboards of those above show them
     * online, and takes up any change to their own details (a new code, a new manager). Called when the
     * app comes to the front and when the phone finds a network; with no network it does nothing.
     */
    fun checkIn() {
        if (profile == null || account.token.isBlank()) return
        scope.launch {
            online { api.me(account.token) }.onSuccess { said ->
                val person = Profile.from(said)
                if (person != profile) {
                    account.signedIn(account.token, person)
                    profile = person
                }
                // With the person's details come the report types their company made for itself, and
                // the one it usually starts with. A walk nobody has begun yet starts with that one.
                OwnTypes.heard(filesDir, said)?.let { usual -> if (issues.isEmpty()) changeTemplate(usual) }
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
        val leaving = profile
        account.signOut()
        // Whoever else is signed in on this phone takes over; with nobody left, it is back at the welcome.
        becomeWhoIsSignedIn(leaving)
        if (profile == null) {
            unlocked = false
            screen = Screen.Home
        }
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

    /** The first issue of a walk has no previous one to count steps from. It also makes the walk its reporter's. */
    private fun forNewIssue(stamp: Stamp?): Stamp? {
        if (issues.isNotEmpty()) return stamp
        settings.edit().putString(WALK_OWNER, profile?.uniqueId.orEmpty()).apply()
        return stamp?.copy(steps = null)
    }

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

    // ---------------------------------------------------------------- asking about the walk
    //
    // A question, spoken or typed, is answered by the model on the phone from the issues of this walk
    // alone, and the answer is read out. Nothing of it touches the network.

    /** What is being done about a question right now, in words; null when nothing is. */
    var askBusy by mutableStateOf<String?>(null)
        private set
    var askQuestion by mutableStateOf("")
        private set
    var askAnswer by mutableStateOf("")
        private set

    /** How long the model took over the last answer. */
    var askSeconds by mutableStateOf(0f)
        private set

    /** Where a spoken question is recorded to. */
    fun askFile(): File = File(cacheDir, "question.wav")

    /** A question has been recorded to [askFile]: it is turned into text, then answered. */
    fun askAloud() {
        if (askBusy != null) return
        if (!speechReady) {
            askAnswer = "Speech is not on this phone yet. Type the question instead."
            return
        }
        askBusy = "Turning your question into text…"
        scope.launch {
            // A speech model handed silence makes a sentence up. A recording with no voice in it is not
            // given to it at all.
            val heard = if (withContext(Dispatchers.IO) { loudness(askFile()) } < QUIET) "" else
                spoken.code.let { language -> withContext(modelWork) { runCatching { transcriber.toEnglish(askFile(), language) } } }.getOrNull().orEmpty().trim()
            if (heard.isBlank()) {
                askQuestion = "(nothing was heard)"
                askAnswer = "No question was heard. Try again, a little closer to the phone."
                askBusy = null
            } else {
                answer(heard)
            }
        }
    }

    fun ask(question: String) {
        if (askBusy != null || question.isBlank()) return
        scope.launch { answer(question.trim()) }
    }

    private suspend fun answer(question: String) {
        askQuestion = question
        askAnswer = ""
        askSeconds = 0f
        plan = null
        canUndo = false
        // "New issue" and "take a picture" need no model to understand.
        if (Commands.wantsCapture(question)) {
            askBusy = null
            act(Plan.Capture)
            return
        }
        if (issues.isEmpty()) {
            askAnswer = "There are no issues on this walk yet, so there is nothing to answer from."
            askBusy = null
            return
        }
        if (!gemmaReady) {
            askAnswer = "The large model is not on this phone yet, so questions cannot be answered."
            askBusy = null
            return
        }
        val lines = walkLines()
        // Something to do, not something to answer: the model says which action and with what values,
        // and Commands checks every part of that before anything is shown as about to change.
        if (Commands.looksLikeOne(question)) {
            askBusy = "Working out what to do, on this phone…"
            val listed = issues.toList()
            val template = inspection.template
            val command = withContext(modelWork) { runCatching { structurer.command(question, lines, template) }.getOrNull() }
            gemmaBackend = structurer.backend
            val made = command?.let { Commands.plan(it, question, listed, template, memory, inspection.site) }
            if (made != null && made != Plan.Question) {
                askSeconds = structurer.lastSeconds
                askBusy = null
                act(made)
                return
            }
        }
        askBusy = "Thinking, on this phone…"
        val said = withContext(modelWork) { runCatching { structurer.answer(question, lines, inspection.template) }.getOrNull() }
        gemmaBackend = structurer.backend
        askSeconds = if (said != null) structurer.lastSeconds else 0f
        askAnswer = said ?: "The model could not answer that. Try asking it another way."
        askBusy = null
        if (said != null) voice.say(said)
    }

    /** How loud a recording is at its loudest half second, as the root mean square of its samples (0 to 32768). */
    private fun loudness(wav: File): Double {
        val bytes = runCatching { wav.readBytes() }.getOrNull() ?: return 0.0
        val window = 8000 * 2                      // half a second of 16 kHz, 16-bit, one channel
        var loudest = 0.0
        var at = 44                                // past the WAV header
        while (at + window <= bytes.size) {
            var sum = 0.0
            for (i in at until at + window step 2) {
                val sample = (bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)
                sum += sample.toDouble() * sample
            }
            loudest = maxOf(loudest, kotlin.math.sqrt(sum / (window / 2)))
            at += window
        }
        return loudest
    }

    /** The whole walk as the model is shown it for a question: the site, then one line per issue, fixed ones included. */
    private fun walkLines(): String {
        val open = issues.count { !it.resolved }
        val head = listOfNotNull(
            inspection.site.takeIf { it.isNotBlank() }?.let { "Place: $it." },
            "${issues.size} issues found, $open still open, ${issues.size - open} fixed.",
        ).joinToString(" ")
        return head + "\n" + issues.withIndex().joinToString("\n") { (i, issue) ->
            listOfNotNull(
                "Issue ${i + 1}: ${issue.title.ifBlank { issue.note.take(60).ifBlank { "not described yet" } }}",
                "severity ${issue.severity.label}",
                issue.category.takeIf { it.isNotBlank() }?.let { "type $it" },
                issue.location.takeIf { it.isNotBlank() && !locationIsPhones(issue) }?.let { "at $it" },
                issue.owner.takeIf { it.isNotBlank() }?.let { "${inspection.template.ownerLabel.lowercase()} $it" },
                issue.dueDate.takeIf { it.isNotBlank() }?.let { "due $it" },
                if (issue.resolved) "status fixed" else "status open",
            ).joinToString("; ")
        }
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
        memory.unlearn(id)
        memoryChanged++
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
        inspection = Inspection(inspector = profile?.name ?: inspection.inspector, template = inspection.template)
        filter = Filter.All
        place = ""
        settings.edit().remove(WALK_OWNER).apply()
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
        val language = spoken.code
        scope.launch {
            val result = withContext(modelWork) {
                runCatching { transcriber.toEnglish(store.file(voice), language) }
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
                    // What the note left out, the phone asks for aloud, once.
                    askBackIfWanted(id)
                }
                .onFailure {
                    applyFields(id, Rules.structure(note))
                    notice = "The on-device model could not fill the fields (${it.message}); used simple rules."
                }
        }
    }

    private fun applyFields(id: String, fields: Fields, seconds: Float = 0f, backend: String = "") {
        val note = issues.firstOrNull { it.id == id }?.note ?: return
        // Nothing the model wrote reaches the report before it has been held against what was said.
        val checked = Guard.check(fields, note, memory, inspection.site)
        val kept = checked.fields
        edit(id) {
            // An expiry date the model read off the pack and that has passed makes the issue expired stock,
            // rated High at least, whatever the note said.
            val expired = Expiry.parse(kept.expiry)?.let(Expiry::isExpired) == true
            val expiredType = inspection.template.categories.firstOrNull { type -> type.startsWith("Expired") }
            val sources = HashMap(it.sources)
            // What a person typed, said in answer or set by voice is theirs: the model does not write over it.
            fun mine(field: String) = Source.of(it.sources[field]) in OWN_WORDS
            fun take(field: String, old: String, new: String): String =
                if (new.isBlank() || mine(field)) old else new.also { checked.sources[field]?.let { from -> sources[field] = from.key } }
            val severity = if (kept.severity == null || mine(Field.SEVERITY)) it.severity else kept.severity.also {
                checked.sources[Field.SEVERITY]?.let { from -> sources[Field.SEVERITY] = from.key }
            }
            val owner = take(Field.OWNER, it.owner, kept.owner)
            val due = take(Field.DUE, it.dueDate, kept.dueDate)
            val placed = kept.location.isNotBlank() && !mine(Field.LOCATION)
            if (placed) checked.sources[Field.LOCATION]?.let { from -> sources[Field.LOCATION] = from.key }
            // A suggestion is kept aside only for a field that is still empty: a filled one needs none.
            val held = checked.held.filter { (field, _) ->
                when (field) {
                    Field.OWNER -> owner.isBlank()
                    Field.DUE -> due.isBlank()
                    else -> !placed && locationIsPhones(it)
                }
            }
            it.copy(
                title = take(Field.TITLE, it.title, kept.title),
                // The spoken location is joined to the tagged place and to where the camera was aimed.
                location = if (placed) Readings.location(it.place, kept.location, Readings.aim(it.stamp?.pitch)) else it.location,
                severity = if (expired && severity.ordinal < Severity.HIGH.ordinal) Severity.HIGH else severity,
                owner = owner,
                dueDate = due,
                category = if (expired && expiredType != null) expiredType else take(Field.CATEGORY, it.category, kept.category),
                check = kept.check,
                seen = kept.seen,
                aiSeconds = seconds,
                aiBackend = backend,
                expiry = kept.expiry,
                sources = sources,
                held = held,
            )
        }
        // What the model wrote is remembered beside the issue, so a correction made to it can be learned from.
        issues.firstOrNull { it.id == id }?.let { now -> wrote[id] = now.owner to placeSaid(now) }
        learn(id)
    }

    // ---------------------------------------------------------------- where each field came from, and what the phone has learned
    //
    // Every field of an issue carries where its value came from: heard in the note, seen in the picture,
    // from the phone itself, asked for, set by voice, typed, or the model's own judgement. A name, a place
    // or a date the model offers that nobody said is kept out of the report (see Guard). And the phone
    // learns who gets given work and what the places are called, from the fields as the inspector leaves
    // them (see Memory).

    /** Who gets given work and what the places are called, learned on this phone. */
    val memory = Memory(File(context.filesDir, "memory.json"))

    /** Changes whenever [memory] does, so a screen showing it draws again. */
    var memoryChanged by mutableStateOf(0)
        private set

    /** The owner and the place the model wrote for an issue, by its id, until the app closes. */
    private val wrote = HashMap<String, Pair<String, String>>()

    /** Where a field's value came from; null when the field is empty or nothing is known of it. */
    fun sourceOf(issue: Issue, field: String): Source? = when {
        field == Field.LOCATION && issue.location.isNotBlank() && locationIsPhones(issue) -> Source.PHONE
        else -> Source.of(issue.sources[field])
    }

    /** A field changed by the person's own hand: it is marked as theirs, and the model will not write over it. */
    fun typed(id: String, field: String, change: (Issue) -> Issue) = edit(id) {
        val next = change(it)
        val empty = when (field) {
            Field.TITLE -> next.title
            Field.LOCATION -> next.location
            Field.OWNER -> next.owner
            Field.DUE -> next.dueDate
            Field.CATEGORY -> next.category
            else -> "set"
        }.isBlank()
        next.copy(sources = if (empty) next.sources - field else next.sources + (field to Source.TYPED.key), held = next.held - field)
    }

    /** Takes a value the guard kept out: the inspector says it is right after all. */
    fun acceptHeld(id: String, field: String) {
        typed(id, field) {
            val value = it.held[field].orEmpty()
            when (field) {
                Field.OWNER -> it.copy(owner = value)
                Field.DUE -> it.copy(dueDate = value)
                else -> it.copy(location = Readings.location(it.place, value, Readings.aim(it.stamp?.pitch)))
            }
        }
        learn(id)
    }

    fun dropHeld(id: String, field: String) = edit(id) { it.copy(held = it.held - field) }

    /** The place a person named for an issue, without the tagged place and the camera's aim that the phone adds. */
    private fun placeSaid(issue: Issue): String =
        if (locationIsPhones(issue)) "" else Memory.placeIn(issue.location.removePrefix(issue.place).trimStart(',', ' '))

    /**
     * Learns from an issue as it now stands: who it was given to and what its place is called. Where the
     * inspector changed a name the model had written, the change itself is remembered, so the same
     * mishearing is put right by the phone the next time. Called when the model has filled the fields,
     * when the inspector leaves the issue, and when a report is built.
     */
    fun learn(id: String) {
        val issue = issues.firstOrNull { it.id == id } ?: return
        val place = placeSaid(issue)
        memory.learn(id, inspection.site, issue.owner, place)
        wrote[id]?.let { (owner, spot) ->
            if (issue.owner.isNotBlank() && Source.of(issue.sources[Field.OWNER]) == Source.TYPED) memory.correctedPerson(owner, issue.owner)
            if (place.isNotBlank() && Source.of(issue.sources[Field.LOCATION]) == Source.TYPED) memory.correctedPlace(spot, place, inspection.site)
        }
        memoryChanged++
    }

    /** People and teams to offer for an issue that has nobody yet: the ones given work most often. */
    fun peopleToOffer(): List<String> = memory.people(4)

    /** Places on this site to offer for an issue nobody has placed. */
    fun placesToOffer(): List<String> = memory.places(inspection.site, 4)

    fun forgetPerson(name: String) {
        memory.forgetPerson(name)
        memoryChanged++
    }

    fun forgetPlace(name: String, site: String) {
        memory.forgetPlace(name, site)
        memoryChanged++
    }

    // ---------------------------------------------------------------- asking back
    //
    // A note often leaves out where the problem is, who should deal with it or by when. The phone then
    // asks, aloud, one question for whatever is missing, listens for the answer, and fills the fields
    // from it. Nothing the answer does not say is written.

    enum class AskPhase { SPEAKING, LISTENING, THINKING, DONE, UNHEARD }

    /** A question the phone is asking about one issue, and how far it has got. */
    data class AskBack(
        val id: String,
        val phase: AskPhase,
        val question: String,
        val gaps: List<Gap>,
        /** What the answer was heard as, in English. */
        val heard: String = "",
        /** The fields the answer filled, as a person calls them. */
        val filled: List<String> = emptyList(),
        /** How loud the microphone is right now, 0 to 1. */
        val level: Float = 0f,
    )

    var askBack by mutableStateOf<AskBack?>(null)
        private set

    /** Whether the phone asks by itself when a note leaves something out; otherwise only when told to. */
    var asksBack by mutableStateOf(settings.getBoolean("asksBack", true))
        private set

    fun letAskBack(on: Boolean) {
        asksBack = on
        settings.edit().putBoolean("asksBack", on).apply()
        if (!on) cancelAskBack()
    }

    private var askBackJob: Job? = null
    private val answerRecorder = WavRecorder()
    private var answerEnded = false

    /** In a test build, an answer handed over from a computer, standing in for the microphone. */
    private var answerGiven: String? = null

    private fun answerFile(): File = File(cacheDir, "answer.wav")

    /** What a report needs that this issue does not have yet. A tagged place counts as knowing where. */
    fun gapsOf(issue: Issue): List<Gap> =
        Asking.gaps(placed = issue.place.isNotBlank() || !locationIsPhones(issue), owner = issue.owner, due = issue.dueDate)

    /** After the model has filled an issue: asks for what is missing, if the issue is the one on screen and has not been asked before. */
    private fun askBackIfWanted(id: String) {
        if (!asksBack || askBack != null || (screen as? Screen.Edit)?.id != id) return
        val issue = issues.firstOrNull { it.id == id } ?: return
        if (!issue.asked && gapsOf(issue).isNotEmpty()) askBack(id)
    }

    /** Asks aloud for what issue [id] lacks, listens, and fills the fields from the answer. */
    fun askBack(id: String) {
        val issue = issues.firstOrNull { it.id == id } ?: return
        val gaps = gapsOf(issue)
        if (gaps.isEmpty() || !speechReady) return
        cancelAskBack()
        val question = Asking.question(gaps)
        edit(id) { it.copy(asked = true) }
        askBack = AskBack(id, AskPhase.SPEAKING, question, gaps)
        answerEnded = false
        answerGiven = null
        askBackJob = scope.launch {
            try {
                voice.sayAndWait(question)
                // A moment for the room to go quiet, so the phone does not hear its own question.
                delay(250)
                val allowed = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!allowed || !answerRecorder.start(answerFile())) {
                    askBack = null
                    notice = "The microphone could not be opened for your answer. Type it in the fields below."
                    return@launch
                }
                haptics.listening()
                askBack = askBack?.copy(phase = AskPhase.LISTENING)
                listenForAnswer()
                answerRecorder.stop()
                haptics.heard()
                askBack = askBack?.copy(phase = AskPhase.THINKING, level = 0f)
                val language = spoken.code
                val heard = answerGiven ?: if (withContext(Dispatchers.IO) { loudness(answerFile()) } < QUIET) "" else
                    withContext(modelWork) { runCatching { transcriber.toEnglish(answerFile(), language) } }.getOrNull().orEmpty().trim()
                if (heard.isBlank()) {
                    askBack = askBack?.copy(phase = AskPhase.UNHEARD)
                    return@launch
                }
                val filled = takeAnswer(id, question, gaps, heard)
                askBack = askBack?.copy(phase = AskPhase.DONE, heard = heard, filled = filled)
                if (filled.isNotEmpty()) {
                    haptics.filled()
                    voice.say("Noted.")
                }
            } finally {
                answerRecorder.stop()
            }
        }
    }

    /** Waits while the person answers: until they have spoken and then gone quiet, or nobody speaks, or it has gone on too long. */
    private suspend fun listenForAnswer() {
        val started = SystemClock.elapsedRealtime()
        var spokeAt = 0L
        var lastLoud = 0L
        var floor = 1f
        while (!answerEnded) {
            delay(80)
            val now = SystemClock.elapsedRealtime()
            val level = answerRecorder.level
            askBack = askBack?.copy(level = level)
            // The room's own noise is the quietest the microphone has been; a voice stands well above it.
            if (now - started > 200) floor = minOf(floor, level)
            if (level > maxOf(ANSWER_LEVEL, floor * 3f)) {
                if (spokeAt == 0L) spokeAt = now
                lastLoud = now
            }
            val waited = now - started
            if (spokeAt == 0L && waited > ANSWER_WAIT_MS) break
            if (spokeAt != 0L && now - lastLoud > ANSWER_PAUSE_MS) break
            if (waited > ANSWER_LIMIT_MS) break
        }
    }

    /** The person says they have finished answering, without waiting for the pause to be noticed. */
    fun endAnswer() {
        answerEnded = true
    }

    /** For a test build on an emulator, which has no voice to answer with: [words] are taken as what was answered. */
    fun pretendAnswer(words: String) {
        if (askBack?.phase != AskPhase.LISTENING) return
        answerGiven = words
        answerEnded = true
    }

    /** Stops the question, the listening and the card, as when the person starts doing something else. */
    fun cancelAskBack() {
        askBackJob?.cancel()
        askBackJob = null
        if (askBack?.phase == AskPhase.SPEAKING) voice.hush()
        answerRecorder.stop()
        askBack = null
    }

    /** Puts the card away once it has said what it filled. */
    fun closeAskBack() {
        if (askBack?.phase == AskPhase.DONE || askBack?.phase == AskPhase.UNHEARD) askBack = null
    }

    /**
     * Fills what was asked for from the spoken answer and gives back the names of the fields filled. The
     * model reads the answer; each value is then held against the answer's own words, and only fields
     * that were asked about are touched. The answer is added to the note, where the report shows it.
     */
    private suspend fun takeAnswer(id: String, question: String, gaps: List<Gap>, heard: String): List<String> {
        val template = inspection.template
        val read = if (!gemmaReady) null else
            withContext(modelWork) { runCatching { structurer.followUp(question, heard, template) }.getOrNull() }
        gemmaBackend = structurer.backend
        val checked = read?.let { Guard.check(it, heard, memory, inspection.site).fields }
        // With no model to read it, the answer to a single question is taken as it stands.
        val whole = if (checked == null && gaps.size == 1) Asking.direct(heard) else ""
        val filled = ArrayList<String>()
        edit(id) { issue ->
            var next = issue
            val sources = HashMap(issue.sources)
            for (gap in gaps) {
                val value = when (gap) {
                    Gap.WHERE -> checked?.location ?: whole
                    Gap.WHO -> checked?.owner ?: whole.let { memory.person(it) ?: it }
                    Gap.WHEN -> checked?.dueDate ?: DueDates.resolve(whole)
                }
                if (value.isBlank()) continue
                next = when (gap) {
                    Gap.WHERE -> next.copy(location = Readings.location(next.place, value, Readings.aim(next.stamp?.pitch)))
                    Gap.WHO -> next.copy(owner = value)
                    Gap.WHEN -> next.copy(dueDate = value)
                }
                sources[gap.field] = Source.ASKED.key
                filled.add(if (gap == Gap.WHO) template.ownerLabel else if (gap == Gap.WHERE) "Location" else "Due")
            }
            if (filled.isEmpty()) issue else next.copy(
                sources = sources,
                held = issue.held - gaps.map { it.field }.toSet(),
                note = issue.note.trimEnd().let { if (it.isEmpty()) heard else "$it $heard" },
            )
        }
        if (filled.isNotEmpty()) learn(id)
        return filled
    }

    // ---------------------------------------------------------------- telling the app what to do
    //
    // "Mark issue 3 critical, give it to Ravi, due Friday." The model on the phone reads the instruction
    // and says which of a few actions it is and with what values; plain code checks every part of that
    // (see Commands) and lists exactly what will change. Nothing changes until the person taps Do it,
    // and one tap puts it back.

    /** Changes a spoken instruction asks for, waiting for the person's yes. */
    var plan by mutableStateOf<Plan.Changes?>(null)
        private set

    /** True when the last changes made by voice can still be put back. */
    var canUndo by mutableStateOf(false)
        private set
    private var beforePlan: List<Issue> = emptyList()

    private fun act(made: Plan) {
        when (made) {
            is Plan.Changes -> {
                plan = made
                val count = made.list.size
                askAnswer = "I will make $count change${if (count == 1) "" else "s"}. Nothing is changed until you tap Do it."
                voice.say(Commands.spoken(made) + " Tap Do it to make the change${if (count == 1) "" else "s"}.")
            }
            is Plan.Show -> {
                filter = made.filter
                askAnswer = "Showing ${made.words}."
                voice.say(askAnswer)
                screen = Screen.Home
            }
            is Plan.Open -> {
                askAnswer = "Opening issue ${made.number}."
                voice.say(askAnswer)
                screen = Screen.Edit(made.issueId)
            }
            is Plan.Refused -> {
                askAnswer = made.why
                voice.say(made.why)
            }
            Plan.Capture -> {
                askAnswer = "Opening the camera for a new issue."
                voice.say(askAnswer)
                screen = Screen.Capture()
            }
            Plan.Question -> Unit
        }
    }

    /** Makes the changes the person has just agreed to. */
    fun doPlan() {
        val made = plan ?: return
        val touched = made.list.map { it.issueId }.distinct()
        beforePlan = issues.filter { it.id in touched }
        made.list.forEach { change ->
            edit(change.issueId) { issue ->
                when (change.field) {
                    Field.SEVERITY -> issue.copy(severity = Severity.entries.firstOrNull { it.label == change.to } ?: issue.severity)
                    Field.OWNER -> issue.copy(owner = change.to)
                    Field.DUE -> issue.copy(dueDate = change.to)
                    Field.LOCATION -> issue.copy(location = change.to)
                    else -> issue.copy(category = change.to)
                }.let { it.copy(sources = it.sources + (change.field to Source.TOLD.key), held = it.held - change.field) }
            }
        }
        touched.forEach(::learn)
        plan = null
        canUndo = true
        askAnswer = "Done: " + Commands.spoken(made)
        haptics.filled()
        voice.say("Done.")
    }

    fun dropPlan() {
        plan = null
        askAnswer = "Nothing was changed."
    }

    /** Puts the issues back as they were before the last changes made by voice. */
    fun undoPlan() {
        if (!canUndo) return
        beforePlan.forEach { old -> edit(old.id) { old } }
        beforePlan.forEach { learn(it.id) }
        beforePlan = emptyList()
        canUndo = false
        askAnswer = "Put back as it was."
    }

    // ---------------------------------------------------------------- measuring the models on this phone
    //
    // A fixed set of notes (see Bench) is run through the same models and the same guard as a real note,
    // on the phone, and marked: how many report fields came out right, whether anything was made up, how
    // long each step took and what it cost the battery. The last run is kept and shown.

    private val scoreFile = File(context.filesDir, "scorecard.json")

    /** The last measurements taken on this phone; while a run is going, the notes done so far. */
    var scorecard by mutableStateOf(runCatching { Scorecard.from(JSONObject(scoreFile.readText())) }.getOrNull())
        private set

    /** What the run is doing now, in words; null when none is going. */
    var measuring by mutableStateOf<String?>(null)
        private set
    private var measureJob: Job? = null

    /**
     * Runs the fixed notes: five of them when [quick], otherwise all twenty. With [aloud] each note is
     * first said by the phone's own voice into a sound file and listened to by the speech model, so the
     * whole chain is measured; otherwise the text goes straight to the large model.
     */
    fun measure(quick: Boolean, aloud: Boolean) {
        if (measureJob?.isActive == true || !gemmaReady) return
        val picked = if (quick) Bench.quick else Bench.cases.indices.toList()
        measureJob = scope.launch {
            val battery = app.getSystemService(BatteryManager::class.java)
            val before = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
            val charging = battery?.isCharging == true
            val language = spoken
            var listened = aloud && speechReady && voice.ready
            val outcomes = ArrayList<Outcome>()
            val raw = File(cacheDir, "measure-said.wav")
            val wav = File(cacheDir, "measure.wav")
            fun card(used: Float?) = Scorecard(
                at = System.currentTimeMillis(),
                phone = listOf(Build.BRAND, Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
                processor = structurer.backend.orEmpty(),
                language = language.label,
                spoken = listened,
                outcomes = outcomes.toList(),
                usedMah = used,
                charging = charging,
            )
            try {
                for ((done, index) in picked.withIndex()) {
                    val case = Bench.cases[index]
                    val step = "Note ${done + 1} of ${picked.size}"
                    var heard: String? = null
                    var speechSeconds = 0f
                    if (listened) {
                        measuring = "$step: the phone reads it aloud to itself…"
                        val made = voice.toFile(case.note, raw) && withContext(Dispatchers.IO) { Wav.to16k(raw, wav) }
                        // A phone whose voice cannot be recorded this way is measured from the text alone.
                        if (!made && outcomes.isEmpty()) listened = false
                        if (made) {
                            measuring = "$step: speech to English…"
                            heard = withContext(modelWork) { runCatching { transcriber.toEnglish(wav, language.code) }.getOrNull() }.orEmpty()
                            speechSeconds = transcriber.lastSeconds
                        }
                    }
                    val note = if (listened) heard.orEmpty() else case.note
                    measuring = "$step: note to report fields…"
                    val result = if (note.isBlank()) Result.failure(IllegalStateException("Nothing was heard.")) else
                        withContext(modelWork) { runCatching { structurer.structure(note, emptyList(), Template.SITE) } }
                    gemmaBackend = structurer.backend
                    // The same guard as a real note goes through, with no memory, so every run is alike.
                    val checked = result.getOrNull()?.let { Guard.check(it, note) }
                    outcomes.add(
                        Outcome(
                            number = index + 1,
                            note = case.note,
                            heard = if (listened) heard.orEmpty() else null,
                            wordsRight = if (listened) Bench.wordsRight(case.note, heard.orEmpty()) else null,
                            // A note that could not be run has every field marked wrong.
                            marks = Bench.mark(case, checked?.fields ?: Fields("", "", null, "", "")).let { marks ->
                                if (checked == null) marks.map { it.copy(right = false, madeUp = false) } else marks
                            },
                            speechSeconds = speechSeconds,
                            fieldSeconds = if (checked == null) 0f else structurer.lastSeconds,
                            keptOut = checked?.held?.size ?: 0,
                            failed = result.exceptionOrNull()?.message,
                        )
                    )
                    scorecard = card(null)
                }
            } finally {
                val after = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
                // The gauge counts in millionths of an amp-hour; some phones do not give it at all.
                val used = if (before > 0 && after > 0) (before - after) / 1000f else null
                if (outcomes.isNotEmpty()) {
                    val last = card(used)
                    scorecard = last
                    runCatching { scoreFile.writeText(last.toJson().toString()) }
                }
                raw.delete()
                wav.delete()
                measuring = null
            }
        }
    }

    fun stopMeasuring() {
        measureJob?.cancel()
    }

    // ---------------------------------------------------------------- my desk
    //
    // What waits for the signed-in person at the office: the issues the server's assistant says need
    // them, each with the step it proposes. The phone fetches the list, the model on the phone words a
    // short briefing, and the phone says it. The person answers by voice: "give the first one to Arjun by
    // Friday", "tell the manager I will start today", "I fixed job two". The model fills a form,
    // DeskCommands checks it, the action is shown, and only after Do it does it go to the office, which
    // checks it again. The list is kept on the phone and can be read with no network; acting on it
    // needs one.

    private val deskFile = File(context.filesDir, "desk.json")

    var desk by mutableStateOf(runCatching { Desk.kept(deskFile.readText()) }.getOrNull())
        private set

    /** What the desk is doing right now, in words; null when nothing. */
    var deskBusy by mutableStateOf<String?>(null)
        private set

    /** True while what keeps the desk busy is only the wording of its briefing, which anything said to it cuts short. */
    var deskWording by mutableStateOf(false)
        private set

    /** True while the desk's microphone is open, so that nothing is said over the person speaking. */
    var deskHearing by mutableStateOf(false)

    /** Whether the desk can be spoken or typed to right now. */
    val deskListens: Boolean get() = deskBusy == null || deskWording

    private fun stopWording() {
        if (!deskWording) return
        deskWork?.cancel()
        deskWording = false
        deskBusy = null
    }

    /** The briefing as it is shown and said. */
    var deskBriefing by mutableStateOf(desk?.plain().orEmpty())
        private set

    /** What was last said to the desk, and what it answered. */
    var deskSaid by mutableStateOf("")
        private set
    var deskAnswer by mutableStateOf("")
        private set

    /** How long the model on the phone took over the last thing. */
    var deskSeconds by mutableStateOf(0f)
        private set

    /** An action waiting for the person's yes. */
    var deskPlan by mutableStateOf<DeskPlan.Act?>(null)
        private set

    /** Why the office could not be reached, when it could not; the desk shown is then the one last fetched. */
    var deskTrouble by mutableStateOf<String?>(null)
        private set

    /** Whether the desk speaks: the briefing when it is opened, and a line when something new arrives. */
    var deskSpeaks by mutableStateOf(settings.getBoolean("deskSpeaks", true))
        private set

    fun letDeskSpeak(on: Boolean) {
        deskSpeaks = on
        settings.edit().putBoolean("deskSpeaks", on).apply()
        if (!on) voice.hush()
    }

    /**
     * A picture of a repair, taken for a job the person says they fixed, on its way to the office. The
     * model on the phone first compares it with the picture the issue was reported with.
     */
    data class Repair(
        val number: Int,
        val job: DeskJob,
        val photo: File,
        val note: String,
        /** The picture the issue was reported with, once fetched from the office. */
        val before: File? = null,
        val checking: Boolean = false,
        val check: Check = Check.NONE,
        val seen: String = "",
    )

    var repair by mutableStateOf<Repair?>(null)
        private set
    private var repairPlan: DeskPlan.Act? = null
    private var deskFetching = false
    private var deskWork: Job? = null

    /**
     * Fetches the desk from the office. With [aloud] the briefing is then worded and said; without, only
     * something newly arrived is announced, in a line.
     */
    fun refreshDesk(aloud: Boolean) {
        if (profile == null || account.token.isBlank()) return
        // Someone who uses a place has no desk at it: what the place answers them is said by their own screen.
        if (profile?.usesAPlace == true) return
        if (deskFetching) return
        deskFetching = true
        scope.launch {
            val before = desk
            val fetched = online { api.desk(account.token) }
            deskFetching = false
            fetched.onFailure { error ->
                deskTrouble = if ((error as? ApiError)?.status == 401) "Sign in again to see your desk." else why(error)
                // With no network the desk as it was last fetched is still read out.
                if (aloud && before != null) briefDesk()
            }.onSuccess { said ->
                val at = System.currentTimeMillis()
                val now = Desk.from(said, at)
                desk = now
                deskTrouble = null
                withContext(Dispatchers.IO) { runCatching { deskFile.writeText(Desk.keep(said, at).toString()) } }
                val known = before?.waiting?.map { it.id }?.toSet()
                val arrived = if (known == null) emptyList() else now.waiting.filter { it.id !in known }
                when {
                    aloud -> briefDesk()
                    arrived.isNotEmpty() -> {
                        deskBriefing = now.plain()
                        val line = now.announce(arrived.first()) + if (arrived.size > 1) " And ${arrived.size - 1} more." else ""
                        notice = line
                        haptics.filled()
                        // Said aloud only where nothing is being recorded, or the phone would hear itself.
                        val quiet = deskHearing || screen is Screen.Edit || screen is Screen.Capture || screen == Screen.Ask || screen == Screen.Radio || screen == Screen.Scan
                        if (deskSpeaks && !quiet) voice.say(line)
                    }
                    before == null || before.jobs.map { it.id to it.next.label } != now.jobs.map { it.id to it.next.label } -> deskBriefing = now.plain()
                }
            }
        }
    }

    private var deskWatch: Job? = null

    /**
     * While the app is in front, the phone waits on the server: one question that the server answers the
     * moment this person's desk changes, asked again when it has. So a job given out on another phone or
     * on a dashboard is announced here within a second or two. With an older server, which has no such
     * door, the desk looks again by the clock instead.
     */
    fun watchDesk(on: Boolean) {
        deskWatch?.cancel()
        deskWatch = null
        if (!on) return
        deskWatch = scope.launch {
            while (true) {
                val token = account.token
                val mark = desk?.mark.orEmpty()
                if (profile == null || token.isBlank() || mark.isBlank()) {
                    delay(5_000)
                    continue
                }
                val answer = online { api.deskWait(token, mark) }
                val refused = (answer.exceptionOrNull() as? ApiError)?.status
                when {
                    // Asked for the person who has since handed the phone on: the answer is nobody's.
                    token != account.token -> Unit
                    answer.getOrNull()?.optBoolean("changed") == true -> {
                        refreshDesk(aloud = false)
                        delay(1_500)
                    }
                    answer.isSuccess -> Unit
                    refused == 404 -> return@launch
                    else -> delay(10_000)
                }
            }
        }
    }

    /** Opens the desk, showing at once what was last fetched, then fetching it anew and saying the briefing. */
    fun openDesk() {
        screen = Screen.MyDesk
        refreshDesk(aloud = true)
    }

    /**
     * Words the briefing and says it. The plain one, made by code, is shown at once; the model's wording
     * replaces it when it is ready and holds nothing the list does not.
     */
    fun briefDesk() {
        val now = desk ?: return
        deskBusy = null
        deskWording = false
        deskBriefing = now.plain()
        deskWork?.cancel()
        deskWork = scope.launch {
            // With nothing that needs the person, the plain words say so exactly; the model is given only
            // the jobs that do need them, so it cannot urge them to deal with somebody else's.
            if (gemmaReady && now.waiting.isNotEmpty()) {
                deskBusy = "Wording your briefing, on this phone…"
                deskWording = true
                val worded = withContext(modelWork) { runCatching { structurer.deskBrief(now.lines(mine = true), now.firstName, now.roleName, now.waiting.size) }.getOrNull() }
                gemmaBackend = structurer.backend
                deskWording = false
                deskBusy = null
                // A briefing that names a job the desk does not have is not used.
                val numbers = Regex("""\b[Jj]ob (\d+)""").findAll(worded.orEmpty()).mapNotNull { it.groupValues[1].toIntOrNull() }
                if (worded != null && numbers.all { it in 1..now.jobs.size }) {
                    deskBriefing = worded
                    deskSeconds = structurer.lastSeconds
                }
            }
            if (deskSpeaks) {
                voice.hush()
                voice.say(deskBriefing)
            }
        }
    }

    /** Where something said to the desk is recorded to. */
    fun deskSaidFile(): File = File(cacheDir, "desk-said.wav")

    /** Something has been recorded to [deskSaidFile]: it is turned into text, then acted on. */
    fun tellDeskAloud() {
        if (!deskListens) return
        stopWording()
        if (!speechReady) {
            deskAnswer = "Speech is not on this phone yet. Type it instead."
            return
        }
        voice.hush()
        deskBusy = "Turning what you said into text…"
        val language = spoken.code
        deskWork = scope.launch {
            val heard = if (withContext(Dispatchers.IO) { loudness(deskSaidFile()) } < QUIET) "" else
                withContext(modelWork) { runCatching { transcriber.toEnglish(deskSaidFile(), language) } }.getOrNull().orEmpty().trim()
            if (heard.isBlank()) {
                deskSaid = "(nothing was heard)"
                deskAnswer = "Nothing was heard. Try again, a little closer to the phone."
                deskBusy = null
            } else {
                hearDesk(heard)
            }
        }
    }

    fun tellDesk(said: String) {
        if (!deskListens || said.isBlank()) return
        stopWording()
        voice.hush()
        deskWork = scope.launch { hearDesk(said.trim()) }
    }

    /** A tap on what the office proposes for a job: the same as saying "do job [number]", with no model needed. */
    fun proposeFor(number: Int) {
        if (!deskListens) return
        stopWording()
        voice.hush()
        deskWork = scope.launch { hearDesk("Do job $number", DeskAsk("do", listOf(number))) }
    }

    private fun deskReplies(words: String) {
        deskBusy = null
        deskAnswer = words
        if (deskSpeaks) voice.say(words)
    }

    private suspend fun hearDesk(said: String, given: DeskAsk? = null) {
        deskSaid = said
        deskAnswer = ""
        deskPlan = null
        deskSeconds = 0f
        val now = desk ?: return deskReplies("Your desk has not been fetched yet. It needs the network once.")
        if (given == null && DeskCommands.wantsBrief(said)) return briefDesk()
        if (now.jobs.isEmpty()) return deskReplies(now.plain())
        val ask = given ?: run {
            if (!gemmaReady) return deskReplies("The large model is not on this phone yet, so the desk cannot take instructions. Tap what is proposed for a job to do it.")
            deskBusy = "Working out what you mean, on this phone…"
            val lines = now.lines()
            val read = withContext(modelWork) { runCatching { structurer.deskAsk(said, lines, now.roleName) }.getOrNull() }
            gemmaBackend = structurer.backend
            deskSeconds = structurer.lastSeconds
            read ?: return deskReplies("The model could not make that out. Try saying it another way.")
        }
        if (ask.action.startsWith("brief")) {
            deskBusy = null
            return briefDesk()
        }
        if (ask.action.isBlank() || ask.action.startsWith("question")) return answerDesk(said, now)
        val (picked, why) = DeskCommands.pick(ask, said, now)
        val (number, job) = picked ?: return deskReplies(why ?: "Say which one, by its number.")
        // Who this job may go to is the office's to say, not guessed: its technicians, and the people it
        // lets this person pass the job to. A newer server sends both along with the desk; an older one
        // is asked now.
        val token = account.token
        val giving = ask.action.startsWith("assign") || ask.action.startsWith("do")
        val reading = ask.action.startsWith("read")
        var technicians = now.technicians
        var sendTo = job.sendTo
        var detail: JSONObject? = null
        if ((technicians == null && giving && now.mayDirect) || sendTo == null || reading) {
            deskBusy = "Asking the office about job $number…"
            if (technicians == null && giving && now.mayDirect) technicians = online { api.technicians(token) }.getOrNull()?.let(Desk::people)
            detail = online { api.issue(token, job.id) }.getOrNull()
            if (sendTo == null) sendTo = detail?.optJSONArray("send_to")?.let(Desk::people)
        }
        deskBusy = null
        when (val plan = DeskCommands.plan(ask, said, now, number, job, technicians.orEmpty(), sendTo.orEmpty())) {
            is DeskPlan.Act -> {
                deskPlan = plan
                val fixed = plan.kind == "fixed"
                deskAnswer = plan.words + if (fixed) ". Tap Take the photo; it is sent only after you have seen it." else ". Nothing is sent until you tap Do it."
                if (deskSpeaks) voice.say(plan.words + if (fixed) ". Tap Take the photo." else ". Tap Do it.")
            }
            is DeskPlan.Read -> {
                // More about one job, in the office's own words where it could be asked, else from the list.
                val brief = detail?.optJSONObject("brief")
                val more = listOfNotNull(
                    brief?.optString("summary")?.takeIf { it.isNotBlank() }
                        ?: (listOf(job.title, job.site, job.location).filter { it.isNotBlank() }.joinToString(", at ") + ". " + job.note).trim(),
                    job.due.takeIf { it.isNotBlank() }?.let { "Due $it." },
                    brief?.optString("standing")?.takeIf { it.isNotBlank() },
                    job.letter?.let { "${it.from} wrote: ${it.text}" },
                    "Next: ${job.next.label}",
                ).joinToString(" ")
                deskReplies("Job $number. $more")
            }
            DeskPlan.Brief -> briefDesk()
            DeskPlan.Question -> answerDesk(said, now)
            is DeskPlan.Refused -> deskReplies(plan.why)
        }
    }

    private suspend fun answerDesk(question: String, now: Desk) {
        deskBusy = "Thinking, on this phone…"
        val lines = now.lines()
        val said = withContext(modelWork) { runCatching { structurer.deskAnswer(question, lines, now.roleName) }.getOrNull() }
        gemmaBackend = structurer.backend
        if (said != null) deskSeconds = structurer.lastSeconds
        deskReplies(said ?: "The model could not answer that. Try asking it another way.")
    }

    /** Carries out the action the person has just agreed to: it goes to the office, which checks it again. */
    fun doDeskPlan() {
        val plan = deskPlan ?: return
        if (plan.kind == "fixed") {
            // The office takes a repair only with its picture: the camera comes first.
            deskPlan = null
            repairPlan = plan
            voice.hush()
            screen = Screen.Capture(jobId = plan.job.id)
            return
        }
        if (deskBusy != null) return
        deskBusy = "Sending it to the office…"
        val token = account.token
        scope.launch {
            val done = online {
                when (plan.kind) {
                    "assign" -> api.assign(token, plan.job.id, plan.to?.id.orEmpty(), plan.dueOn, plan.text)
                    "send" -> api.send(token, plan.job.id, plan.to?.id.orEmpty(), plan.text)
                    else -> api.settle(token, plan.job.id, back = plan.kind == "back", note = plan.text)
                }
            }
            deskBusy = null
            done.onSuccess {
                deskPlan = null
                haptics.filled()
                deskReplies("Done: ${plan.words}.")
                refreshDesk(aloud = false)
            }.onFailure { deskReplies("The office did not take it: ${why(it)}") }
        }
    }

    fun dropDeskPlan() {
        deskPlan = null
        deskAnswer = "Nothing was sent."
    }

    /** Where the camera puts the picture of a repair for one job. */
    fun repairFile(jobId: String): File = File(cacheDir, "repair-$jobId.jpg")

    /**
     * The camera has taken the picture of a repair. Before anything is sent, the picture the issue was
     * reported with is fetched from the office and the model on this phone compares the two.
     */
    fun repairShown(jobId: String) {
        val plan = repairPlan?.takeIf { it.job.id == jobId } ?: return
        val photo = repairFile(jobId)
        repair = Repair(plan.number, plan.job, photo, plan.text, checking = gemmaReady)
        deskAnswer = ""
        if (!gemmaReady) return
        scope.launch {
            val before = File(cacheDir, "repair-$jobId-before.jpg")
            val fetched = withContext(Dispatchers.IO) { api.issuePhoto(account.token, jobId, before) }
            if (!fetched) {
                repair = repair?.takeIf { it.job.id == jobId }?.copy(checking = false)
                return@launch
            }
            repair = repair?.takeIf { it.job.id == jobId }?.copy(before = before)
            val result = withContext(modelWork) { runCatching { structurer.verifyFix(plan.job.title, before, photo) }.getOrNull() }
            gemmaBackend = structurer.backend
            val checked = repair?.takeIf { it.job.id == jobId && it.photo == photo } ?: return@launch
            repair = checked.copy(checking = false, check = result?.first ?: Check.NONE, seen = result?.second.orEmpty())
            if (deskSpeaks) voice.say(
                when (result?.first) {
                    Check.MATCH -> "The two pictures were compared: the problem is no longer visible. Tap Send to the office."
                    Check.MISMATCH -> "The two pictures were compared: the problem may still be there. Take another picture, or send it as it is."
                    else -> "The two pictures could not be told apart for certain. Send it if the repair is done."
                }
            )
        }
    }

    /** Takes the repair picture again. */
    fun retakeRepair() {
        val waiting = repair ?: return
        repair = null
        screen = Screen.Capture(jobId = waiting.job.id)
    }

    fun dropRepair() {
        repair = null
        repairPlan = null
        deskAnswer = "Nothing was sent."
    }

    /** Sends the repair to the office with what the phone's model made of the two pictures, for whoever checks it. */
    fun sendRepair() {
        val shown = repair ?: return
        if (deskBusy != null) return
        deskBusy = "Sending the repair to the office…"
        val verdict = when (shown.check) {
            Check.MATCH -> "Checked on the phone: the problem is no longer visible in the second picture."
            Check.MISMATCH -> "Checked on the phone: the problem may still be visible in the second picture."
            Check.UNSURE -> "Checked on the phone: the two pictures could not be told apart for certain."
            else -> ""
        }
        val note = listOf(shown.note, verdict).filter { it.isNotBlank() }.joinToString(" ")
        val token = account.token
        scope.launch {
            val done = online { api.fixed(token, shown.job.id, shown.photo, note) }
            deskBusy = null
            done.onSuccess {
                repair = null
                repairPlan = null
                haptics.filled()
                deskReplies("Done: job ${shown.number} is marked fixed and waits for its check.")
                refreshDesk(aloud = false)
            }.onFailure { deskReplies("The office did not take it: ${why(it)}") }
        }
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

        /** Below this loudness (of 32768) a recording holds no voice. A silent microphone read 5; speech at arm's length reads some hundreds. */
        private const val QUIET = 80.0

        /** The setting that holds whose walk the one in hand is, by their DeskLess ID. */
        private const val WALK_OWNER = "walkOwner"

        /** Where a field's value is a person's own, which the model does not write over. */
        private val OWN_WORDS = setOf(Source.TYPED, Source.ASKED, Source.TOLD)

        /** A spoken answer: the loudness (0 to 1) that counts as a voice, how long to wait for one, the pause that ends it, and the longest it may run. */
        private const val ANSWER_LEVEL = 0.03f
        private const val ANSWER_WAIT_MS = 5_000L
        private const val ANSWER_PAUSE_MS = 1_500L
        private const val ANSWER_LIMIT_MS = 12_000L

        /** How issues with no type yet are named in filters and group headings. */
        const val UNSORTED = "Not sorted yet"
    }
}
