package com.techazsure.deskless.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.techazsure.deskless.AppState
import com.techazsure.deskless.Screen
import com.techazsure.deskless.account.DeskJob
import com.techazsure.deskless.capture.WavRecorder
import com.techazsure.deskless.data.Check
import com.techazsure.deskless.structure.DeskPlan
import com.techazsure.deskless.ui.theme.Chalk
import com.techazsure.deskless.ui.theme.Coal
import com.techazsure.deskless.ui.theme.Fog
import com.techazsure.deskless.ui.theme.GoGreen
import com.techazsure.deskless.ui.theme.Ink
import com.techazsure.deskless.ui.theme.Line
import com.techazsure.deskless.ui.theme.SafetyYellow
import com.techazsure.deskless.ui.theme.SeverityCritical
import com.techazsure.deskless.ui.theme.SeverityHigh
import com.techazsure.deskless.ui.theme.SeverityMedium
import com.techazsure.deskless.ui.theme.SignalRed
import com.techazsure.deskless.ui.theme.Steel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How long something said to the desk may be; the recording stops by itself after that. */
private const val SAID_SECONDS = 20

/** How often the desk looks at the office again while it is open, so a job given out elsewhere turns up by itself. */
private const val LOOK_AGAIN_MS = 20_000L

/**
 * The signed-in person's desk: what waits for them at the office, said aloud when it opens, and acted on
 * by voice. "Give the first one to Arjun by Friday", "tell the manager I will start today", "I fixed job
 * two": each is shown as the one thing it will do, and goes to the office only after Do it. The list comes
 * from the server; the briefing, the listening and the understanding are done on the phone.
 */
@Composable
fun DeskScreen(state: AppState) {
    val context = LocalContext.current
    val recorder = remember { WavRecorder() }
    var recording by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    // The wording of the briefing does not keep anyone waiting: speaking or typing cuts it short.
    val busy = state.deskBusy.takeUnless { state.deskWording }
    val desk = state.desk

    fun stop() {
        if (!recording) return
        recorder.stop()
        recording = false
        state.deskHearing = false
        state.tellDeskAloud()
    }

    fun start() {
        state.voice.hush()
        if (recorder.start(state.deskSaidFile())) {
            recording = true
            state.deskHearing = true
        } else Toast.makeText(context, "Could not open the microphone.", Toast.LENGTH_LONG).show()
    }

    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else Toast.makeText(context, "Microphone access is needed to speak to the desk. You can still type.", Toast.LENGTH_LONG).show()
    }

    fun toggle() {
        if (recording) return stop()
        if (busy != null) return
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (allowed) start() else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun send() {
        if (typed.isBlank() || busy != null) return
        state.tellDesk(typed)
        typed = ""
    }

    LaunchedEffect(recording) {
        if (recording) {
            delay(SAID_SECONDS * 1000L)
            stop()
        }
    }
    // While the desk is open it looks at the office again now and then, unless something is in hand.
    LaunchedEffect(Unit) {
        while (true) {
            delay(LOOK_AGAIN_MS)
            if (!recording && state.deskListens && state.deskPlan == null && state.repair == null) state.refreshDesk(aloud = false)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (recording) recorder.stop()
            state.deskHearing = false
            state.voice.hush()
        }
    }
    BackHandler { state.screen = Screen.Home }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundIconButton(icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "Back", onClick = { state.screen = Screen.Home }, container = Steel)
            Column(Modifier.weight(1f)) {
                Text("MY DESK", style = MaterialTheme.typography.headlineSmall, color = Chalk)
                Text(
                    text = listOfNotNull(
                        desk?.roleName?.takeIf { it.isNotBlank() },
                        desk?.let { "as of " + SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date(it.at)) },
                    ).joinToString(" · ").ifBlank { "Fetching from the office…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Fog,
                )
            }
            RoundIconButton(
                icon = if (state.deskSpeaks) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                label = if (state.deskSpeaks) "The desk speaks: tap to silence it" else "The desk is silent: tap to let it speak",
                onClick = { state.letDeskSpeak(!state.deskSpeaks) },
                container = Steel,
                content = if (state.deskSpeaks) SafetyYellow else Fog,
            )
            RoundIconButton(icon = Icons.Rounded.Refresh, label = "Fetch the desk again and say it", onClick = { state.refreshDesk(aloud = true) }, container = Steel)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // One phone, several people: a tap hands the desk to another of them.
            AccountSwitcher(state)

            state.deskTrouble?.let { trouble ->
                Text(
                    text = trouble + if (desk != null) " This is the desk as it was last fetched." else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SeverityMedium,
                )
            }

            // The briefing: what the phone says when the desk opens.
            if (state.deskBriefing.isNotBlank()) {
                Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.dp, SafetyYellow.copy(alpha = 0.45f)), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("BRIEFING", style = MaterialTheme.typography.labelSmall, color = Fog, modifier = Modifier.weight(1f))
                            RoundIconButton(
                                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                                label = "Say it again",
                                onClick = {
                                    state.voice.hush()
                                    state.voice.say(state.deskBriefing)
                                },
                                container = Steel,
                                size = 34.dp,
                            )
                        }
                        Text(state.deskBriefing, style = MaterialTheme.typography.titleMedium, color = Chalk)
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val beat = if (recording) pulse(0.9f, 1.08f, 600) else 1f
                RoundIconButton(
                    icon = if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    label = if (recording) "Stop and send" else "Speak to the desk",
                    onClick = { toggle() },
                    container = if (recording) SignalRed else SafetyYellow,
                    content = Ink,
                    size = 96.dp,
                    modifier = Modifier.graphicsLayer {
                        scaleX = beat
                        scaleY = beat
                        alpha = if (busy != null && !recording) 0.4f else 1f
                    },
                )
                Text(
                    text = when {
                        recording -> "Listening… tap when you have said it."
                        state.deskWording -> "Wording your briefing… you can speak already."
                        busy != null -> busy
                        !state.speechReady -> "Speech is not on this phone yet: type below, or tap what is proposed."
                        desk?.mayDirect == true -> "Tap and say it: “Give the first one to … by Friday”, “Do job 2”."
                        else -> "Tap and say it: “I fixed the first one”, “Tell the manager …”."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (recording) SignalRed else Chalk,
                    textAlign = TextAlign.Center,
                )
            }

            state.repair?.let { RepairCard(state, it) }

            if (state.deskSaid.isNotBlank() && state.repair == null) {
                Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.dp, GoGreen.copy(alpha = 0.45f)), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("YOU SAID", style = MaterialTheme.typography.labelSmall, color = Fog)
                        Text(state.deskSaid, style = MaterialTheme.typography.bodyLarge, color = Chalk)
                        Text("DESKLESS", style = MaterialTheme.typography.labelSmall, color = Fog)
                        Text(
                            text = if (busy != null) busy else state.deskAnswer,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (busy != null) Fog else Chalk,
                        )
                        state.deskPlan?.takeIf { busy == null }?.let { ActCard(state, it) }
                        if (busy == null && state.deskSeconds > 0f) {
                            Text(
                                text = "Understood in %.1f s on this phone".format(state.deskSeconds) + (state.gemmaBackend?.let { "'s $it" } ?: "") + ". The office checks every action again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Fog,
                            )
                        }
                    }
                }
            }

            if (desk != null) {
                val mine = desk.waiting
                if (mine.isNotEmpty()) Text("NEEDS YOU", style = MaterialTheme.typography.labelSmall, color = Fog)
                desk.jobs.forEachIndexed { at, job ->
                    if (!job.needsMe && (at == 0 || desk.jobs[at - 1].needsMe)) {
                        Text("ALSO IN YOUR VIEW, WAITING ON OTHERS", style = MaterialTheme.typography.labelSmall, color = Fog)
                    }
                    JobCard(at + 1, job, enabled = busy == null && !recording) { state.proposeFor(at + 1) }
                }
                if (desk.jobs.isEmpty()) {
                    Text("Nothing is on your desk. Issues you report, are given, or are sent turn up here.", style = MaterialTheme.typography.bodyLarge, color = Fog)
                }
            }
            Spacer(Modifier.size(4.dp))
        }

        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.take(240) },
                placeholder = { Text("Or type it", color = Fog) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Chalk, unfocusedTextColor = Chalk, cursorColor = SafetyYellow,
                    focusedBorderColor = SafetyYellow, unfocusedBorderColor = Line,
                    focusedContainerColor = Steel, unfocusedContainerColor = Steel,
                ),
                modifier = Modifier.weight(1f),
            )
            RoundIconButton(icon = Icons.AutoMirrored.Rounded.Send, label = "Send", onClick = { send() }, container = SafetyYellow, content = Ink, size = 52.dp)
        }
    }
}

private fun bandColor(band: String): Color = when (band) {
    "now" -> SeverityCritical
    "soon" -> SeverityHigh
    else -> Fog
}

/** One job on the desk: its number, what and where it is, why it presses, and what is proposed, which a tap carries out. */
@Composable
private fun JobCard(number: Int, job: DeskJob, enabled: Boolean, onPropose: () -> Unit) {
    val tone = bandColor(job.band)
    Surface(shape = RoundedCornerShape(18.dp), color = Coal, border = BorderStroke(1.dp, if (job.needsMe) tone.copy(alpha = 0.55f) else Line.copy(alpha = 0.6f)), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(if (job.needsMe) SafetyYellow else Steel, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", style = MaterialTheme.typography.titleMedium, color = if (job.needsMe) Ink else Chalk)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(job.title, style = MaterialTheme.typography.titleMedium, color = Chalk)
                Text(
                    text = listOfNotNull(
                        job.site.takeIf { it.isNotBlank() },
                        job.severity.takeIf { it.isNotBlank() },
                        when (job.band) {
                            "now" -> "act now"
                            "soon" -> "soon"
                            else -> null
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = tone,
                )
                job.why.firstOrNull()?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fog) }
                job.letter?.let { Text("${it.from} (${it.fromRole}): “${it.text}”", style = MaterialTheme.typography.bodyMedium, color = Chalk) }
                // What the office proposes. For a job that needs this person, a tap carries it out, after showing it.
                if (job.needsMe && job.next.kind != "check") {
                    Surface(onClick = onPropose, enabled = enabled, shape = RoundedCornerShape(12.dp), color = Steel, border = BorderStroke(1.dp, SafetyYellow.copy(alpha = 0.5f))) {
                        Text(job.next.label, style = MaterialTheme.typography.labelLarge, color = SafetyYellow, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                } else {
                    Text(job.next.label, style = MaterialTheme.typography.bodySmall, color = Fog)
                }
            }
        }
    }
}

/** The one thing a spoken instruction will do at the office, before it does it. */
@Composable
private fun ActCard(state: AppState, plan: DeskPlan.Act) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Steel, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PlanLine("JOB ${plan.number}", plan.job.title + if (plan.job.site.isNotBlank()) " · ${plan.job.site}" else "")
            plan.to?.let { PlanLine(if (plan.kind == "assign") "GIVE IT TO" else "SEND IT TO", it.name + if (it.role.isNotBlank()) " (${it.role})" else "") }
            if (plan.dueWords.isNotBlank()) PlanLine("DUE", plan.dueWords)
            if (plan.text.isNotBlank()) PlanLine(if (plan.kind == "fixed" || plan.kind == "close" || plan.kind == "back") "WITH THE NOTE" else "WITH THE WORDS", plan.text)
        }
        plan.leftOut.forEach { Text("Left out: $it", style = MaterialTheme.typography.bodySmall, color = SeverityMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrandButton(
                text = if (plan.kind == "fixed") "Take the photo" else "Do it",
                onClick = { state.doDeskPlan() },
                icon = if (plan.kind == "fixed") Icons.Rounded.CameraAlt else Icons.Rounded.Check,
                height = 48.dp,
                modifier = Modifier.weight(1.3f),
            )
            BrandButton(text = "Cancel", onClick = { state.dropDeskPlan() }, kind = ButtonKind.Secondary, height = 48.dp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlanLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Fog)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = Chalk)
    }
}

/**
 * A repair on its way to the office: the picture the issue was reported with beside the one just taken,
 * and what the model on this phone makes of the two, before the person sends it.
 */
@Composable
private fun RepairCard(state: AppState, repair: AppState.Repair) {
    val tone = when (repair.check) {
        Check.MATCH -> GoGreen
        Check.MISMATCH -> SignalRed
        else -> SafetyYellow
    }
    Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.dp, tone.copy(alpha = 0.6f)), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("THE REPAIR OF JOB ${repair.number}", style = MaterialTheme.typography.labelSmall, color = Fog)
            Text(repair.job.title, style = MaterialTheme.typography.titleMedium, color = Chalk)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repair.before?.let { before ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Photo(before, maxEdge = 480, modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)))
                        Text("AS REPORTED", style = MaterialTheme.typography.labelSmall, color = Fog)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Photo(repair.photo, maxEdge = 480, modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)))
                    Text("NOW", style = MaterialTheme.typography.labelSmall, color = Fog)
                }
            }
            val beat = if (repair.checking) pulse(0.45f, 1f, 650) else 1f
            Text(
                text = when {
                    repair.checking -> "Comparing the two pictures, on this phone…"
                    repair.check == Check.MATCH -> "The phone's AI compared the two: the problem is no longer visible."
                    repair.check == Check.MISMATCH -> "The phone's AI compared the two: the problem may still be there."
                    repair.check == Check.UNSURE -> "The phone's AI could not tell from the two pictures."
                    else -> "The pictures were not compared on the phone. Whoever checks the repair will see both."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = tone,
                modifier = Modifier.graphicsLayer { alpha = beat },
            )
            if (repair.seen.isNotBlank()) Text("It sees: ${repair.seen}", style = MaterialTheme.typography.bodySmall, color = Fog)
            if (repair.note.isNotBlank()) Text("Your note: ${repair.note}", style = MaterialTheme.typography.bodySmall, color = Fog)
            BrandButton(
                text = "Send to the office",
                onClick = { state.sendRepair() },
                icon = Icons.AutoMirrored.Rounded.Send,
                enabled = state.deskBusy == null,
                height = 50.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandButton(text = "Retake", onClick = { state.retakeRepair() }, icon = Icons.Rounded.CameraAlt, kind = ButtonKind.Secondary, height = 46.dp, modifier = Modifier.weight(1f))
                BrandButton(text = "Cancel", onClick = { state.dropRepair() }, kind = ButtonKind.Secondary, height = 46.dp, modifier = Modifier.weight(1f))
            }
            state.deskBusy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fog) }
        }
    }
}
