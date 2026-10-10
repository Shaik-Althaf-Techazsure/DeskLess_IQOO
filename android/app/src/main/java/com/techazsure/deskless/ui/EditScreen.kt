package com.techazsure.deskless.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.material.icons.rounded.MyLocation
import com.techazsure.deskless.capture.Geo
import com.techazsure.deskless.structure.Expiry
import com.techazsure.deskless.ui.theme.GoGreen
import android.media.MediaPlayer
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CameraAlt
import com.techazsure.deskless.data.Check
import com.techazsure.deskless.report.Handoff
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.techazsure.deskless.AppState
import com.techazsure.deskless.Screen
import com.techazsure.deskless.capture.WavRecorder
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.ui.theme.Chalk
import com.techazsure.deskless.ui.theme.Coal
import com.techazsure.deskless.ui.theme.Fog
import com.techazsure.deskless.ui.theme.Ink
import com.techazsure.deskless.ui.theme.Line
import com.techazsure.deskless.ui.theme.SafetyYellow
import com.techazsure.deskless.ui.theme.SignalRed
import com.techazsure.deskless.ui.theme.Steel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

@Composable
fun EditScreen(state: AppState, issue: Issue) {
    var confirmDelete by remember { mutableStateOf(false) }
    val number = state.issues.indexOfFirst { it.id == issue.id } + 1
    BackHandler { state.screen = Screen.Home }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
    ) {
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(WindowInsets.navigationBars.add(WindowInsets(bottom = 88.dp)))
                .imePadding()
                .verticalScroll(scroll),
        ) {
            Box {
                Photo(
                    file = state.photoOf(issue),
                    maxEdge = 1024,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.55f),
                                0.35f to Color.Transparent,
                                0.6f to Color.Transparent,
                                1f to Ink,
                            )
                        )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = { state.screen = Screen.Home })
                    Spacer(Modifier.weight(1f))
                    RoundIconButton(Icons.Rounded.DeleteOutline, "Delete this issue", onClick = { confirmDelete = true })
                }
                issue.videoFile?.let { clip ->
                    // A filmed issue shows a still; the clip itself opens in the phone's player.
                    val context = LocalContext.current
                    RoundIconButton(
                        icon = Icons.Rounded.PlayArrow,
                        label = "Play the video",
                        container = SafetyYellow,
                        content = Ink,
                        size = 64.dp,
                        modifier = Modifier.align(Alignment.Center),
                        onClick = {
                            runCatching { context.startActivity(Handoff.playIntent(context, state.fileOf(clip))) }
                                .onFailure { Toast.makeText(context, "No app on this phone can play the clip.", Toast.LENGTH_LONG).show() }
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("ISSUE $number", style = MaterialTheme.typography.headlineMedium, color = Chalk)
                    SeverityPill(issue.severity)
                    if (issue.resolved) FixedTag()
                }
            }
            LocationStamp(issue)

            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                VoiceCard(state, issue)
                CheckCard(state, issue)
                ReadingsCard(state, issue)
                EntryCard(state, issue)
                FixCard(state, issue)
                Spacer(Modifier.height(8.dp))
            }
        }
            // The photo runs up under the status bar; once it has scrolled away, back the clock with ink.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                    .graphicsLayer { alpha = (scroll.value / 260f).coerceIn(0f, 1f) }
                    .background(Ink)
            )
        }

        Surface(color = Coal, border = BorderStroke(1.dp, Line.copy(alpha = 0.5f))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BrandButton(
                    text = "Done",
                    onClick = { state.screen = Screen.Home },
                    icon = Icons.Rounded.Check,
                    kind = ButtonKind.Secondary,
                    modifier = Modifier.weight(1f),
                )
                BrandButton(
                    text = "Next issue",
                    onClick = { state.screen = Screen.Capture() },
                    icon = Icons.Rounded.CameraAlt,
                    modifier = Modifier.weight(1.15f),
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this issue?") },
            text = { Text("The photo and voice note are removed from the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    state.screen = Screen.Home
                    state.delete(issue.id)
                }) { Text("Delete", color = SignalRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Coal,
        border = BorderStroke(1.dp, Line.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
private fun VoiceCard(state: AppState, issue: Issue) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val recorder = remember(issue.id) { WavRecorder() }
    var recording by remember(issue.id) { mutableStateOf(false) }
    var seconds by remember(issue.id) { mutableIntStateOf(0) }
    val levels = remember(issue.id) { mutableStateListOf<Float>() }
    var player by remember(issue.id) { mutableStateOf<MediaPlayer?>(null) }
    val fileName = "voice_${issue.id}.wav"

    fun stopPlayback() {
        player?.release()
        player = null
    }

    fun stopRecording() {
        if (!recording) return
        recorder.stop()
        recording = false
        val length = WavRecorder.seconds(state.fileOf(fileName))
        state.edit(issue.id) { it.copy(voiceFile = fileName, voiceSeconds = length) }
        state.transcribe(issue.id)
    }

    fun startRecording() {
        stopPlayback()
        if (recorder.start(state.fileOf(fileName))) {
            seconds = 0
            levels.clear()
            recording = true
        } else {
            Toast.makeText(context, "Could not open the microphone.", Toast.LENGTH_LONG).show()
        }
    }

    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            startRecording()
        } else {
            Toast.makeText(context, "Microphone access is needed for voice notes.", Toast.LENGTH_LONG).show()
        }
    }

    fun toggle() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (recording) {
            stopRecording()
        } else {
            val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (allowed) startRecording() else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        val started = SystemClock.elapsedRealtime()
        while (true) {
            seconds = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
            levels.add(recorder.level)
            if (levels.size > WAVE_BARS) levels.removeAt(0)
            delay(70)
        }
    }

    // Lift to talk: until the issue has a note, holding the phone to an ear starts the recording and
    // lowering it stops, with a buzz each time. The screen goes dark at the ear, as in a call, so a cheek
    // cannot press anything. Once there is a note it takes a tap to replace it, so a pocket cannot.
    val sensors = state.sensors
    var lifted by remember(issue.id) { mutableStateOf(false) }
    val liftable = sensors.hasProximity && (issue.voiceFile == null || lifted)
    LaunchedEffect(liftable) { sensors.holdScreenOffAtEar(liftable) }
    LaunchedEffect(liftable, sensors.near, sensors.upright, recording) {
        if (!liftable) return@LaunchedEffect
        if (sensors.near && sensors.upright && !recording) {
            val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (!allowed) return@LaunchedEffect
            delay(450)
            startRecording()
            if (recording) {
                lifted = true
                state.haptics.listening()
            }
        } else if (!sensors.near && recording && lifted) {
            delay(350)
            state.haptics.heard()
            stopRecording()
            lifted = false
        }
    }
    // A note spoken at the ear ends by itself after a minute, should the phone never notice being lowered.
    LaunchedEffect(lifted) {
        if (!lifted) return@LaunchedEffect
        delay(60_000)
        state.haptics.heard()
        stopRecording()
        lifted = false
    }
    DisposableEffect(issue.id) {
        sensors.watch()
        onDispose {
            stopRecording()
            stopPlayback()
            sensors.holdScreenOffAtEar(false)
            sensors.unwatch()
        }
    }

    Card {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "VOICE NOTE",
                style = MaterialTheme.typography.labelSmall,
                color = Fog,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            MicButton(recording = recording, level = levels.lastOrNull() ?: 0f, hasNote = issue.voiceFile != null, onClick = { toggle() })
            Spacer(Modifier.height(12.dp))

            AnimatedContent(
                targetState = when {
                    recording -> "recording"
                    issue.voiceFile != null -> "recorded"
                    else -> "idle"
                },
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "voice",
            ) { mode ->
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (mode) {
                        "recording" -> {
                            Text(
                                text = "%d:%02d".format(seconds / 60, seconds % 60),
                                style = MaterialTheme.typography.displaySmall,
                                color = Chalk,
                            )
                            Spacer(Modifier.height(8.dp))
                            Waveform(levels, live = true)
                            Spacer(Modifier.height(8.dp))
                            Text("Listening. Tap the button to stop.", style = MaterialTheme.typography.bodyMedium, color = Fog)
                        }
                        "recorded" -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RoundIconButton(
                                    icon = if (player != null) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                    label = if (player != null) "Stop" else "Play the voice note",
                                    container = Steel,
                                    content = SafetyYellow,
                                    onClick = {
                                        if (player != null) {
                                            stopPlayback()
                                        } else {
                                            runCatching {
                                                MediaPlayer().apply {
                                                    setDataSource(state.fileOf(fileName).absolutePath)
                                                    setOnCompletionListener { stopPlayback() }
                                                    prepare()
                                                    start()
                                                }
                                            }.onSuccess { player = it }
                                        }
                                    },
                                )
                                Text(
                                    text = "Voice note, ${issue.voiceSeconds} s. Tap the mic to record again.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Fog,
                                )
                            }
                        }
                        else -> {
                            Text("DESCRIBE IT ALOUD", style = MaterialTheme.typography.headlineSmall, color = Chalk)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Hindi, English or both. Say what is wrong, where it is, who should fix it and by when.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Fog,
                                textAlign = TextAlign.Center,
                            )
                            if (sensors.hasProximity) {
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
                                        .background(Steel, RoundedCornerShape(50))
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.PhoneInTalk, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(16.dp))
                                    Text("Or lift the phone to your ear and speak", style = MaterialTheme.typography.labelMedium, color = Chalk)
                                }
                            }
                        }
                    }
                }
            }

            if (issue.voiceFile != null && !recording) {
                Spacer(Modifier.height(16.dp))
                Pipeline(state, issue)
            }
        }
    }
}

private const val WAVE_BARS = 36

@Composable
private fun MicButton(recording: Boolean, level: Float, hasNote: Boolean, onClick: () -> Unit) {
    val loud by animateFloatAsState(
        targetValue = if (recording) level.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "loud",
    )
    val breath = if (recording || hasNote) 0f else pulse(0f, 1f, 1100)
    val color by animateColorAsState(if (recording) SignalRed else SafetyYellow, label = "mic")
    val interaction = remember { MutableInteractionSource() }

    Box(modifier = Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = 44.dp.toPx()
            // Two rings that swell with the voice while recording, and breathe gently before the first note.
            val spread = if (recording) loud else breath * 0.35f
            drawCircle(color.copy(alpha = 0.10f + 0.10f * spread), radius = base + 20.dp.toPx() * (0.4f + spread))
            drawCircle(color.copy(alpha = 0.18f + 0.12f * spread), radius = base + 9.dp.toPx() * (0.5f + spread))
        }
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            shape = CircleShape,
            color = color,
            contentColor = if (recording) Color.White else Ink,
            modifier = Modifier
                .size(84.dp)
                .bouncy(interaction, pressedScale = 0.88f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = if (recording) "Stop recording" else "Record a voice note",
                    modifier = Modifier.size(38.dp),
                )
            }
        }
    }
}

@Composable
private fun Waveform(levels: List<Float>, live: Boolean) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        val gap = size.width / WAVE_BARS
        val bar = gap * 0.55f
        for (i in 0 until WAVE_BARS) {
            val level = levels.getOrNull(levels.size - WAVE_BARS + i) ?: 0f
            val h = max(bar, size.height * (0.08f + 0.92f * level.coerceIn(0f, 1f)))
            drawRoundRect(
                color = if (live) SignalRed.copy(alpha = 0.45f + 0.55f * level.coerceIn(0f, 1f)) else Fog,
                topLeft = Offset(i * gap + (gap - bar) / 2, (size.height - h) / 2),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

private enum class Step { Waiting, Working, Done }

/** Heard → English text → report fields: where this note is in the on-device chain. */
@Composable
private fun Pipeline(state: AppState, issue: Issue) {
    val transcribing = issue.id in state.transcribing
    val structuring = issue.id in state.structuring
    val text = when {
        transcribing -> Step.Working
        issue.note.isNotBlank() -> Step.Done
        else -> Step.Waiting
    }
    val fields = when {
        structuring -> Step.Working
        issue.title.isNotBlank() && !transcribing -> Step.Done
        else -> Step.Waiting
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepDot(Icons.Rounded.Mic, "Heard", Step.Done)
            Link(text != Step.Waiting)
            StepDot(Icons.Rounded.Translate, "English text", text)
            Link(fields != Step.Waiting)
            StepDot(Icons.Rounded.AutoAwesome, "Report fields", fields)
        }
        Spacer(Modifier.height(10.dp))
        when {
            transcribing -> Status("Turning your speech into English, on this phone…")
            structuring -> Status("Reading the note and the photo to fill the report fields…")
            !state.speechReady -> Text(
                text = "The voice note is saved. Add the AI models from the first screen and it becomes text.",
                style = MaterialTheme.typography.bodySmall,
                color = Fog,
                textAlign = TextAlign.Center,
            )
            else -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallAction(Icons.Rounded.Refresh, "Redo the text") { state.transcribe(issue.id) }
                if (issue.note.isNotBlank()) {
                    SmallAction(Icons.Rounded.AutoAwesome, "Refill the fields") { state.fillFields(issue.id) }
                }
            }
        }
    }
}

@Composable
private fun StepDot(icon: ImageVector, label: String, step: Step) {
    val beat = if (step == Step.Working) pulse(0.35f, 1f, 600) else 1f
    val fill by animateColorAsState(if (step == Step.Done) SafetyYellow else Steel, label = "step")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .graphicsLayer { alpha = beat }
                .background(fill, CircleShape)
                .border(1.5.dp, if (step == Step.Waiting) Line else SafetyYellow, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (step == Step.Done) Icons.Rounded.Check else icon,
                contentDescription = null,
                tint = when (step) {
                    Step.Done -> Ink
                    Step.Working -> SafetyYellow
                    Step.Waiting -> Fog
                },
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (step == Step.Waiting) Fog else Chalk,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Link(reached: Boolean) {
    val color by animateColorAsState(if (reached) SafetyYellow else Line, label = "link")
    Box(
        Modifier
            .padding(bottom = 22.dp)
            .width(22.dp)
            .height(2.dp)
            .background(color, RoundedCornerShape(1.dp))
    )
}

@Composable
private fun Status(text: String) {
    val beat = pulse(0.45f, 1f, 650)
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = SafetyYellow,
        textAlign = TextAlign.Center,
        modifier = Modifier.graphicsLayer { alpha = beat },
    )
}

@Composable
private fun SmallAction(icon: ImageVector, text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        contentColor = Fog,
        modifier = Modifier.bouncy(interaction),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * The model's verdict on whether the picture shows what the note describes, with what it saw and how fast
 * it ran. When it has doubts the inspector can retake the picture or overrule it.
 */
@Composable
private fun CheckCard(state: AppState, issue: Issue) {
    val (tone, icon, _) = checkLook(issue.check) ?: return
    val doubtful = issue.check == Check.UNSURE || issue.check == Check.MISMATCH
    val appear = remember(issue.id, issue.check) { Animatable(0f) }
    LaunchedEffect(issue.id, issue.check) { appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 220f)) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Coal,
        border = BorderStroke(1.5.dp, tone.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value.coerceIn(0f, 1f)
                scaleX = 0.94f + 0.06f * appear.value
                scaleY = 0.94f + 0.06f * appear.value
            },
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(tone.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(24.dp))
                }
                Column {
                    Text("AI CHECK", style = MaterialTheme.typography.labelSmall, color = Fog)
                    Text(
                        text = when (issue.check) {
                            Check.MATCH -> "The picture matches your note"
                            Check.CONFIRMED -> "You confirmed the picture"
                            Check.UNSURE -> "Not sure the picture shows this"
                            else -> "The picture may not show this"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = tone,
                    )
                }
            }
            if (issue.seen.isNotBlank()) {
                Text(
                    text = "The picture shows: ${issue.seen}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Chalk,
                )
            }
            // In a medication audit the model also reads the expiry date printed on the pack.
            Expiry.parse(issue.expiry)?.let { read ->
                val expired = Expiry.isExpired(read)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background((if (expired) SignalRed else GoGreen).copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Event, contentDescription = null, tint = if (expired) SignalRed else GoGreen, modifier = Modifier.size(20.dp))
                    Column {
                        Text(
                            text = "Expiry read off the pack: ${issue.expiry} (${Expiry.words(read)})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Chalk,
                        )
                        Text(
                            text = if (expired) "That date has passed. Check the pack before acting on it." else "Still in date. Check the pack to be sure.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (expired) SignalRed else Fog,
                        )
                    }
                }
            }
            if (doubtful) {
                Text(
                    text = "This is the AI's opinion, to catch a wrong or unclear picture before the report goes out. You decide.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Fog,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrandButton(
                        text = "Retake",
                        onClick = { state.screen = Screen.Capture(retakeId = issue.id) },
                        icon = Icons.Rounded.CameraAlt,
                        modifier = Modifier.weight(1f),
                        height = 48.dp,
                    )
                    BrandButton(
                        text = "It is right",
                        onClick = { state.confirmPicture(issue.id) },
                        icon = Icons.Rounded.Check,
                        kind = ButtonKind.Secondary,
                        modifier = Modifier.weight(1f),
                        height = 48.dp,
                    )
                }
            }
            if (issue.aiSeconds > 0f) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(15.dp))
                    Text(
                        text = "Checked and filled in %.1f s on this phone".format(issue.aiSeconds) +
                            if (issue.aiBackend.isNotBlank()) " · ${issue.aiBackend}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Fog,
                    )
                }
            }
        }
    }
}

@Composable
private fun FixedTag() {
    Row(
        modifier = Modifier
            .background(GoGreen.copy(alpha = 0.18f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Check, contentDescription = null, tint = GoGreen, modifier = Modifier.size(13.dp))
        Text("FIXED", style = MaterialTheme.typography.labelSmall, color = GoGreen)
    }
}

/** Where the issue was captured, from the phone's own position; a tap opens it in a maps app. */
@Composable
private fun LocationStamp(issue: Issue) {
    val lat = issue.lat ?: return
    val lon = issue.lon ?: return
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")))
            }.onFailure { Toast.makeText(context, "No maps app on this phone to open it in.", Toast.LENGTH_LONG).show() }
        },
        interactionSource = interaction,
        shape = RoundedCornerShape(50),
        color = Steel,
        modifier = Modifier
            .padding(start = 16.dp, top = 4.dp)
            .bouncy(interaction),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(15.dp))
            Text(
                text = Geo.label(lat, lon) + if (issue.accuracy > 0f) "  ±${issue.accuracy.toInt()} m" else "",
                style = MaterialTheme.typography.labelMedium,
                color = Chalk,
            )
        }
    }
}

/**
 * Closing an issue out: a second picture after the repair, which the model compares with the first. The
 * inspector has the last word either way.
 */
@Composable
private fun FixCard(state: AppState, issue: Issue) {
    val after = issue.fixPhoto
    Card {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("CLOSE IT OUT", style = MaterialTheme.typography.labelSmall, color = Fog)
            if (after == null) {
                Text(
                    text = "Once it has been repaired, photograph the same spot. The AI compares the two pictures and the report shows both.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Fog,
                )
                BrandButton(
                    text = "Photograph the fix",
                    onClick = { state.screen = Screen.Capture(retakeId = issue.id, fix = true) },
                    icon = Icons.Rounded.CameraAlt,
                    kind = ButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                    height = 50.dp,
                )
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("BEFORE" to state.photoOf(issue), "AFTER" to state.fileOf(after)).forEach { (label, file) ->
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Photo(
                            file = file,
                            maxEdge = 512,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(14.dp)),
                        )
                        Text(label, style = MaterialTheme.typography.labelSmall, color = Fog)
                    }
                }
            }

            val (tone, words) = when {
                issue.id in state.verifying -> SafetyYellow to "Comparing before and after, on this phone…"
                issue.fixCheck == Check.MATCH -> GoGreen to "The AI says it looks fixed"
                issue.fixCheck == Check.CONFIRMED -> GoGreen to "Marked fixed by you"
                issue.fixCheck == Check.MISMATCH -> SignalRed to "The AI says the problem is still there"
                issue.fixCheck == Check.UNSURE -> SafetyYellow to "The AI cannot tell from these pictures"
                else -> Fog to "Kept. Mark it fixed if you are satisfied."
            }
            val beat = if (issue.id in state.verifying) pulse(0.45f, 1f, 650) else 1f
            Text(
                text = words,
                style = MaterialTheme.typography.titleMedium,
                color = tone,
                modifier = Modifier.graphicsLayer { alpha = beat },
            )
            if (issue.fixSeen.isNotBlank()) {
                Text("The second picture shows: ${issue.fixSeen}", style = MaterialTheme.typography.bodyMedium, color = Chalk)
            }
            if (issue.id !in state.verifying) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!issue.resolved) {
                        BrandButton(
                            text = "Mark fixed",
                            onClick = { state.confirmFixed(issue.id) },
                            icon = Icons.Rounded.Check,
                            modifier = Modifier.weight(1f),
                            height = 48.dp,
                        )
                    }
                    BrandButton(
                        text = if (issue.resolved) "Reopen" else "Not fixed",
                        onClick = { state.reopen(issue.id) },
                        kind = ButtonKind.Secondary,
                        modifier = Modifier.weight(1f),
                        height = 48.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryCard(state: AppState, issue: Issue) {
    Card {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("REPORT ENTRY", style = MaterialTheme.typography.labelSmall, color = Fog)
                    Spacer(Modifier.weight(1f))
                    // A note typed by hand, with no recording, can be sent to the AI from here.
                    if (issue.voiceFile == null && issue.note.isNotBlank()) {
                        if (issue.id in state.structuring) {
                            Status("Filling…")
                        } else {
                            SmallAction(Icons.Rounded.AutoAwesome, "Fill with AI") { state.fillFields(issue.id) }
                        }
                    }
                }
                Text(
                    text = "The AI fills these from your note and photo. Tap any field to correct it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Fog,
                )
            }
            EntryField(Icons.Rounded.ReportProblem, "Issue", issue.title, "What is wrong") { text ->
                state.edit(issue.id) { it.copy(title = text) }
            }
            // The type the issue is sorted and filtered by; the AI picks one and a tap changes it.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("TYPE", style = MaterialTheme.typography.labelSmall, color = Fog)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.inspection.template.categories.forEach { category ->
                        Pill(
                            text = category,
                            selected = issue.category == category,
                            onClick = {
                                state.edit(issue.id) { it.copy(category = if (it.category == category) "" else category) }
                            },
                        )
                    }
                }
            }
            EntryField(Icons.Rounded.Place, "Location", issue.location, "Where on the site") { text ->
                state.edit(issue.id) { it.copy(location = text) }
            }
            SeverityPicker(issue.severity) { severity -> state.edit(issue.id) { it.copy(severity = severity) } }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EntryField(Icons.Rounded.Person, state.inspection.template.ownerLabel, issue.owner, "Who fixes it", Modifier.weight(1f)) { text ->
                    state.edit(issue.id) { it.copy(owner = text) }
                }
                EntryField(Icons.Rounded.Event, "Due", issue.dueDate, "By when", Modifier.weight(1f)) { text ->
                    state.edit(issue.id) { it.copy(dueDate = text) }
                }
            }
            EntryField(Icons.Rounded.EditNote, "Note", issue.note, "What you said, in English", singleLine = false) { text ->
                state.edit(issue.id) { it.copy(note = text) }
            }
        }
    }
}

/**
 * A labelled field. Its border lights up while you type in it, and flashes with an "AI" tag when the
 * on-device model writes into it, so you can see what was filled for you.
 */
@Composable
private fun EntryField(
    icon: ImageVector,
    label: String,
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf(value) }
    val filled = remember { Animatable(0f) }
    LaunchedEffect(value) {
        if (value != typed) {
            typed = value
            filled.snapTo(1f)
            filled.animateTo(0f, tween(1800))
        }
    }
    val focus by animateFloatAsState(if (focused) 1f else 0f, label = "focus")
    val glow = max(focus, filled.value)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Fog, modifier = Modifier.size(15.dp))
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fog)
            if (filled.value > 0.02f) {
                Text(
                    text = "AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink,
                    modifier = Modifier
                        .graphicsLayer { alpha = filled.value }
                        .background(SafetyYellow, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        BasicTextField(
            value = value,
            onValueChange = {
                typed = it
                onValueChange(it)
            },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Chalk),
            cursorBrush = SolidColor(SafetyYellow),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { field ->
                Box(
                    modifier = Modifier
                        .background(lerp(Steel, SafetyYellow, 0.10f * filled.value), RoundedCornerShape(14.dp))
                        .border(1.5.dp, lerp(Line, SafetyYellow, glow), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                ) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Fog.copy(alpha = 0.5f))
                    }
                    field()
                }
            },
        )
    }
}

@Composable
private fun SeverityPicker(selected: Severity, onSelect: (Severity) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("SEVERITY", style = MaterialTheme.typography.labelSmall, color = Fog)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Severity.entries.forEach { severity ->
                val on = severity == selected
                val tone = severityColor(severity)
                val fill by animateColorAsState(if (on) tone else Steel, label = "severity")
                val interaction = remember { MutableInteractionSource() }
                Surface(
                    onClick = { onSelect(severity) },
                    interactionSource = interaction,
                    shape = RoundedCornerShape(14.dp),
                    color = fill,
                    border = BorderStroke(1.5.dp, if (on) tone else Line),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .bouncy(interaction, pressedScale = 0.92f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = severity.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) Ink else tone,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
