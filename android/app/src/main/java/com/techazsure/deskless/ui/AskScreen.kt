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
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.techazsure.deskless.AppState
import com.techazsure.deskless.Screen
import com.techazsure.deskless.capture.WavRecorder
import com.techazsure.deskless.structure.Plan
import com.techazsure.deskless.ui.theme.Chalk
import com.techazsure.deskless.ui.theme.Coal
import com.techazsure.deskless.ui.theme.Fog
import com.techazsure.deskless.ui.theme.GoGreen
import com.techazsure.deskless.ui.theme.Ink
import com.techazsure.deskless.ui.theme.Line
import com.techazsure.deskless.ui.theme.SafetyYellow
import com.techazsure.deskless.ui.theme.SeverityMedium
import com.techazsure.deskless.ui.theme.SignalRed
import com.techazsure.deskless.ui.theme.Steel
import kotlinx.coroutines.delay

/** How long a spoken question may be; the recording stops by itself after that. */
private const val QUESTION_SECONDS = 15

/**
 * Ask about the walk or tell the app what to do with it, aloud or in writing. A question is answered by
 * the models on this phone from the issues of this walk alone. An instruction ("mark issue 3 critical,
 * give it to Ravi, due Friday") is turned into a list of changes that is shown first and made only when
 * the person agrees. No network takes part in either.
 */
@Composable
fun AskScreen(state: AppState) {
    val context = LocalContext.current
    val recorder = remember { WavRecorder() }
    var recording by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val busy = state.askBusy

    fun stop() {
        if (!recording) return
        recorder.stop()
        recording = false
        state.askAloud()
    }

    fun start() {
        if (recorder.start(state.askFile())) recording = true
        else Toast.makeText(context, "Could not open the microphone.", Toast.LENGTH_LONG).show()
    }

    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else Toast.makeText(context, "Microphone access is needed to ask aloud. You can still type.", Toast.LENGTH_LONG).show()
    }

    fun toggle() {
        if (recording) return stop()
        if (busy != null) return
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (allowed) start() else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun send() {
        if (typed.isBlank() || busy != null) return
        state.ask(typed)
        typed = ""
    }

    LaunchedEffect(recording) {
        if (recording) {
            delay(QUESTION_SECONDS * 1000L)
            stop()
        }
    }
    DisposableEffect(Unit) { onDispose { if (recording) recorder.stop() } }
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
        ) {
            RoundIconButton(icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "Back", onClick = { state.screen = Screen.Home }, container = Steel)
            Spacer(Modifier.width(14.dp))
            Text("ASK OR TELL", style = MaterialTheme.typography.headlineSmall, color = Chalk, modifier = Modifier.weight(1f))
            LanguageChip(state)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Ask about this walk, or tell DeskLess what to change, aloud or in writing. It is worked out on this " +
                    "phone from the ${state.issues.size} issue${if (state.issues.size == 1) "" else "s"} of the walk, with no " +
                    "network, and nothing is changed before you have seen it.",
                style = MaterialTheme.typography.bodyLarge,
                color = Fog,
                modifier = Modifier.fillMaxWidth(),
            )

            val beat = if (recording) pulse(0.9f, 1.08f, 600) else 1f
            RoundIconButton(
                icon = if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                label = if (recording) "Stop and send" else "Speak",
                onClick = { toggle() },
                container = if (recording) SignalRed else SafetyYellow,
                content = Ink,
                size = 112.dp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .graphicsLayer {
                        scaleX = beat
                        scaleY = beat
                        alpha = if (busy != null && !recording) 0.4f else 1f
                    },
            )
            Text(
                text = when {
                    recording -> "Listening… tap when you have said it."
                    busy != null -> busy
                    !state.speechReady -> "Speech is not on this phone yet: type the question below."
                    else -> "Tap and speak: ${state.spoken.hint}."
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (recording) SignalRed else Chalk,
                textAlign = TextAlign.Center,
            )

            if (state.askQuestion.isNotBlank()) {
                Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.dp, GoGreen.copy(alpha = 0.45f)), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("YOU SAID", style = MaterialTheme.typography.labelSmall, color = Fog)
                        Text(state.askQuestion, style = MaterialTheme.typography.bodyLarge, color = Chalk)
                        Spacer(Modifier.size(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("DESKLESS", style = MaterialTheme.typography.labelSmall, color = Fog, modifier = Modifier.weight(1f))
                            if (state.askAnswer.isNotBlank() && busy == null) {
                                RoundIconButton(
                                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                                    label = "Say it again",
                                    onClick = { state.voice.say(state.askAnswer) },
                                    container = Steel,
                                    size = 34.dp,
                                )
                            }
                        }
                        Text(
                            text = if (busy != null) busy else state.askAnswer,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (busy != null) Fog else Chalk,
                        )
                        state.plan?.takeIf { busy == null }?.let { PlanCard(state, it) }
                        if (busy == null && state.canUndo) {
                            BrandButton(
                                text = "Put it back",
                                onClick = { state.undoPlan() },
                                icon = Icons.AutoMirrored.Rounded.Undo,
                                kind = ButtonKind.Secondary,
                                height = 44.dp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (busy == null && state.askSeconds > 0f) {
                            Text(
                                text = "Worked out in %.1f s on this phone".format(state.askSeconds) + (state.gemmaBackend?.let { "'s $it" } ?: "") + ". The model can be wrong: check against the issues.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Fog,
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TRY", style = MaterialTheme.typography.labelSmall, color = Fog)
                listOf(
                    "What should be fixed first?",
                    "Which issues are still open, and who has them?",
                    "Mark issue 1 critical and due tomorrow",
                    "Show the open ones",
                ).forEach { question ->
                    Surface(
                        onClick = { if (busy == null && !recording) state.ask(question) },
                        shape = RoundedCornerShape(14.dp),
                        color = Steel,
                        border = BorderStroke(1.dp, Line.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(question, style = MaterialTheme.typography.bodyMedium, color = Chalk, modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp))
                    }
                }
            }
            Spacer(Modifier.size(4.dp))
        }

        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.take(200) },
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
            RoundIconButton(
                icon = Icons.AutoMirrored.Rounded.Send,
                label = "Send",
                onClick = { send() },
                container = SafetyYellow,
                content = Ink,
                size = 52.dp,
            )
        }
    }
}

/**
 * What a spoken instruction will change, before it changes anything: each change with what stands now and
 * what it will become, what was asked for and left out, and the two buttons.
 */
@Composable
private fun PlanCard(state: AppState, plan: Plan.Changes) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plan.list.forEach { change ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Steel, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${change.number}", style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.background(SafetyYellow, CircleShape).padding(horizontal = 9.dp, vertical = 2.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(change.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fog)
                    Text(
                        text = (change.from.ifBlank { "not set" }) + "  →  " + change.to,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Chalk,
                    )
                }
            }
        }
        plan.leftOut.forEach { why ->
            Text("Left out: $why", style = MaterialTheme.typography.bodySmall, color = SeverityMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrandButton(text = "Do it", onClick = { state.doPlan() }, icon = Icons.Rounded.Check, height = 48.dp, modifier = Modifier.weight(1f))
            BrandButton(text = "Cancel", onClick = { state.dropPlan() }, kind = ButtonKind.Secondary, height = 48.dp, modifier = Modifier.weight(1f))
        }
    }
}
