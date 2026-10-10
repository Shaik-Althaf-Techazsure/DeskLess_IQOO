package com.techazsure.deskless.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.techazsure.deskless.AppState
import com.techazsure.deskless.Screen
import com.techazsure.deskless.link.Stage
import com.techazsure.deskless.link.Talk
import com.techazsure.deskless.link.TalkMessage
import com.techazsure.deskless.ui.theme.Chalk
import com.techazsure.deskless.ui.theme.Coal
import com.techazsure.deskless.ui.theme.Fog
import com.techazsure.deskless.ui.theme.GoGreen
import com.techazsure.deskless.ui.theme.Ink
import com.techazsure.deskless.ui.theme.Line
import com.techazsure.deskless.ui.theme.SafetyYellow
import com.techazsure.deskless.ui.theme.SignalRed
import com.techazsure.deskless.ui.theme.Steel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Push to talk with the linked phones, with no internet and no mobile signal: hold the button, speak, let
 * go. The message crosses the same sealed link the reports use, is played on the other phone as it
 * arrives, and waits or is carried by a phone in between when that phone is out of reach.
 */
@Composable
fun RadioScreen(state: AppState) {
    val context = LocalContext.current
    var talking by remember { mutableStateOf(false) }
    var began by remember { mutableLongStateOf(0L) }
    var seconds by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val linked = state.link.peers.size
    val near = state.link.connected.size

    fun tell(words: String) = Toast.makeText(context, words, Toast.LENGTH_LONG).show()

    fun end() {
        if (!talking) return
        talking = false
        val spoken = ((SystemClock.elapsedRealtime() - began) / 1000).toInt()
        state.stopTalking(spoken)?.let(::tell)
    }

    fun begin() {
        if (talking) return
        if (state.startTalking()) {
            began = SystemClock.elapsedRealtime()
            seconds = 0
            talking = true
        } else {
            tell("Could not open the microphone.")
        }
    }

    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        tell(if (ok) "Now hold the button and speak." else "Microphone access is needed to talk.")
    }

    LaunchedEffect(talking) {
        while (talking) {
            seconds = ((SystemClock.elapsedRealtime() - began) / 1000).toInt()
            if (seconds >= Talk.MAX_SECONDS) end()
            delay(200)
        }
    }
    LaunchedEffect(state.talks.size) { scroll.animateScrollTo(scroll.maxValue) }
    DisposableEffect(Unit) { onDispose { state.cancelTalking() } }
    BackHandler { state.screen = Screen.Home }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "Back", onClick = { state.screen = Screen.Home }, container = Steel)
            Spacer(Modifier.width(14.dp))
            Text("WALKIE-TALKIE", style = MaterialTheme.typography.headlineSmall, color = Chalk)
        }

        Surface(
            shape = RoundedCornerShape(16.dp), color = Coal,
            border = BorderStroke(1.dp, (if (near > 0) GoGreen else SafetyYellow).copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = when {
                        linked == 0 -> "No phone is linked yet"
                        near > 0 -> "$near of $linked linked phone${if (linked == 1) "" else "s"} in reach now"
                        else -> "None of the $linked linked phone${if (linked == 1) "" else "s"} is in reach"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Chalk,
                )
                Text(
                    text = when {
                        linked == 0 -> "Link phones with the yellow button on the first screen. Then they can talk with no internet."
                        near > 0 -> "No internet or mobile signal is used: the phones talk over Bluetooth and Wi-Fi, sealed so only the phone it is for can hear it."
                        else -> "What you say waits, and goes when a linked phone comes into reach, or is carried by a DeskLess phone in between."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Fog,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.talks.isEmpty()) {
                Text(
                    text = "Nothing said yet. Hold the button below, speak, and let go.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Fog,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                )
            }
            state.talks.forEach { message -> Said(state, message) }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val beat = if (talking) pulse(0.94f, 1.08f, 500) else 1f
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .graphicsLayer {
                        scaleX = beat
                        scaleY = beat
                    }
                    .background(if (talking) SignalRed else SafetyYellow, CircleShape)
                    .semantics { contentDescription = "Hold to talk" }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                if (!allowed) {
                                    askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    begin()
                                    tryAwaitRelease()
                                    end()
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = null, tint = Ink, modifier = Modifier.size(54.dp))
            }
            Text(
                text = if (talking) "Talking… $seconds s. Let go to send." else "HOLD TO TALK",
                style = MaterialTheme.typography.titleMedium,
                color = if (talking) SignalRed else Chalk,
            )
        }
    }
}

private val clock = SimpleDateFormat("HH:mm", Locale.ENGLISH)

/** One message in the list: who said it and when, a button to hear it, and for one said here, how far it has got. */
@Composable
private fun Said(state: AppState, message: TalkMessage) {
    val playing = state.talkPlaying == message.id
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (message.mine) SafetyYellow.copy(alpha = 0.10f) else Coal,
            border = BorderStroke(1.dp, if (message.mine) SafetyYellow.copy(alpha = 0.5f) else Line),
            modifier = Modifier.fillMaxWidth(0.86f),
        ) {
            Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(
                    icon = if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    label = if (playing) "Stop" else "Play what ${message.name} said",
                    onClick = { if (playing) state.quietTalk() else state.playTalk(message) },
                    container = if (message.heard) Steel else GoGreen,
                    content = if (message.heard) Chalk else Ink,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${message.name} · ${clock.format(Date(message.at))} · ${message.seconds} s",
                        style = MaterialTheme.typography.titleSmall,
                        color = Chalk,
                    )
                    if (message.mine) {
                        // One line for each phone it was meant for, following it on its way.
                        message.to.forEach { id ->
                            val peer = state.link.peers.firstOrNull { it.id == id } ?: return@forEach
                            val stage = state.link.stage(message.report, id)?.stage
                            Text(
                                text = peer.name + ": " + when (stage) {
                                    Stage.DELIVERED -> "heard"
                                    Stage.HANDED -> "handed over"
                                    Stage.RELAYED -> "with a phone that carries it"
                                    else -> "waiting for a phone in reach"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (stage == Stage.DELIVERED || stage == Stage.HANDED) GoGreen else Fog,
                            )
                        }
                    } else if (!message.heard) {
                        Text("New", style = MaterialTheme.typography.bodySmall, color = GoGreen)
                    }
                }
            }
        }
    }
}
