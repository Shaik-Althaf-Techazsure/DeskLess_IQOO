package com.techazsure.deskless.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.techazsure.deskless.AppState
import com.techazsure.deskless.Screen
import com.techazsure.deskless.bench.Bench
import com.techazsure.deskless.bench.Outcome
import com.techazsure.deskless.bench.Scorecard
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app measuring itself on the phone it runs on: a fixed set of notes goes through the same models and
 * the same guard as a real note, and the screen shows how many report fields came out right, whether
 * anything was made up, how long each step took and what the run cost the battery.
 */
@Composable
fun ScorecardScreen(state: AppState) {
    val context = LocalContext.current
    val view = LocalView.current
    val running = state.measuring
    val card = state.scorecard
    var aloud by rememberSaveable { mutableStateOf(true) }
    val ready = state.gemmaReady
    val canSpeak = state.speechReady && state.voice.ready
    val date = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH)

    BackHandler { state.screen = Screen.Models }
    // The screen stays on while a run is going: a phone that dozes would spoil the timings.
    DisposableEffect(running != null) {
        view.keepScreenOn = running != null
        onDispose { view.keepScreenOn = false }
    }

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
            RoundIconButton(icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "Back", onClick = { state.screen = Screen.Models }, container = Steel)
            Spacer(Modifier.width(14.dp))
            Text("MEASURED HERE", style = MaterialTheme.typography.headlineSmall, color = Chalk)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Twenty fixed notes of the kind an inspector speaks, each with the answers a careful reader would give, " +
                    "are run through the same models and the same guard as a real note, on this phone. It is our own small " +
                    "test, not a published benchmark: it shows how this build does here, and the same again after every change.",
                style = MaterialTheme.typography.bodyLarge,
                color = Fog,
            )

            if (running != null) {
                Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.dp, SafetyYellow.copy(alpha = 0.6f)), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val beat = pulse(0.5f, 1f, 650)
                        Text("MEASURING", style = MaterialTheme.typography.labelSmall, color = SafetyYellow.copy(alpha = beat))
                        Text(running, style = MaterialTheme.typography.titleMedium, color = Chalk)
                        Text("Keep DeskLess open. Other notes wait until this is done.", style = MaterialTheme.typography.bodySmall, color = Fog)
                    }
                }
            }

            if (card != null && card.outcomes.isNotEmpty()) {
                Results(card, date.format(Date(card.at)), partial = running != null)
            } else if (running == null) {
                Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.dp, Line.copy(alpha = 0.6f)), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (ready) "Nothing has been measured on this phone yet. A full run takes a minute or two on a phone with a fast graphics chip."
                        else "The large model is not on this phone yet, so there is nothing to measure.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Chalk,
                        modifier = Modifier.padding(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running != null) {
                BrandButton(text = "Stop", onClick = { state.stopMeasuring() }, kind = ButtonKind.Danger, modifier = Modifier.fillMaxWidth())
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(text = "Spoken aloud first", selected = aloud && canSpeak, onClick = { aloud = !aloud })
                    Text(
                        text = when {
                            !canSpeak -> "This phone's voice or speech model is not ready: the notes go in as text."
                            aloud -> "The phone's own voice reads each note and the speech model listens."
                            else -> "The notes go in as text; speech is not measured."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Fog,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrandButton(
                        text = "Run all 20",
                        onClick = { state.measure(quick = false, aloud = aloud) },
                        icon = Icons.Rounded.PlayArrow,
                        enabled = ready,
                        modifier = Modifier.weight(1.3f),
                    )
                    BrandButton(
                        text = "Quick: 5",
                        onClick = { state.measure(quick = true, aloud = aloud) },
                        kind = ButtonKind.Secondary,
                        enabled = ready,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (card != null && card.notes > 0) {
                    BrandButton(
                        text = "Share the numbers",
                        onClick = {
                            val words = Bench.summary(card, date.format(Date(card.at)))
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, words)
                            runCatching { context.startActivity(Intent.createChooser(send, "DeskLess measurements")) }
                        },
                        icon = Icons.Rounded.Share,
                        kind = ButtonKind.Secondary,
                        height = 48.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Results(card: Scorecard, date: String, partial: Boolean) {
    val perNote = card.fieldSeconds + (card.speechSeconds ?: 0f)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tile("FIELDS RIGHT", Bench.percent(card.fieldsRight), "of ${card.outcomes.sumOf { it.marks.size }} marked", tone(card.fieldsRight), Modifier.weight(1f))
        Tile("A NOTE TAKES", "%.1f s".format(perNote), if (card.spoken) "speech and fields" else "note to fields", SafetyYellow, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tile("MADE UP", "${card.madeUp}", "in ${card.chances} empty fields", if (card.madeUp == 0) GoGreen else SignalRed, Modifier.weight(1f))
        Tile(
            "BATTERY",
            card.mahPerNote?.let { "%.1f".format(it) } ?: "—",
            when {
                card.mahPerNote != null -> "mAh a note"
                card.charging -> "not read: charging"
                else -> "not read by this phone"
            },
            Chalk,
            Modifier.weight(1f),
        )
    }

    Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.dp, Line.copy(alpha = 0.6f)), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("BY FIELD", style = MaterialTheme.typography.labelSmall, color = Fog)
            Bench.FIELDS.forEach { field -> Bar(field, card.right(field)) }
            card.wordsRight?.let { Bar("Words heard", it) }
        }
    }

    Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.dp, Line.copy(alpha = 0.6f)), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (partial) "SO FAR" else "THE RUN", style = MaterialTheme.typography.labelSmall, color = Fog)
            Fact("Phone", card.phone)
            Fact("When", date)
            Fact("Notes", "${card.notes} of ${Bench.cases.size}" + if (card.spoken) ", read aloud by the phone's own voice" else ", given as text")
            card.speechSeconds?.let { Fact("Speech to English", "%.1f s a note, listening for ${card.language}".format(it)) }
            Fact("Note to report fields", "%.1f s a note".format(card.fieldSeconds) + if (card.processor.isNotBlank()) " on the ${card.processor}" else "")
            Fact("Slowest note", "%.1f s".format(card.slowest))
            Fact("Kept out by the guard", "${card.keptOut} value${if (card.keptOut == 1) "" else "s"} nobody said")
            Text(
                text = "The notes carry no picture, so reading a photo is not in these times. " +
                    (if (card.spoken) "The voice is the phone's own, clear and steady, in English: a person on a noisy site, or mixing languages, will be heard less well. " else "") +
                    "Battery is the phone's own gauge over the whole run, screen on.",
                style = MaterialTheme.typography.bodySmall,
                color = Fog,
            )
        }
    }

    Text("NOTE BY NOTE", style = MaterialTheme.typography.labelSmall, color = Fog)
    card.outcomes.forEach { NoteRow(it) }
}

private fun tone(share: Float): Color = when {
    share >= 0.85f -> GoGreen
    share >= 0.6f -> SeverityMedium
    else -> SignalRed
}

@Composable
private fun Tile(label: String, value: String, under: String, tone: Color, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.dp, tone.copy(alpha = 0.45f)), modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Fog)
            Text(value, style = MaterialTheme.typography.displaySmall, color = tone)
            Text(under, style = MaterialTheme.typography.bodySmall, color = Fog)
        }
    }
}

@Composable
private fun Bar(label: String, share: Float) {
    val shown by animateFloatAsState(share, label = "bar")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Chalk, modifier = Modifier.width(96.dp))
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Steel)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shown.coerceIn(0.02f, 1f))
                    .height(10.dp)
                    .background(tone(share))
            )
        }
        Text(Bench.percent(share), style = MaterialTheme.typography.labelLarge, color = Chalk, modifier = Modifier.width(42.dp))
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Fog, modifier = Modifier.width(132.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Chalk, modifier = Modifier.weight(1f))
    }
}

/** One note of the run: what was said, how many fields came out right, and each one that did not. */
@Composable
private fun NoteRow(outcome: Outcome) {
    val right = outcome.marks.count { it.right }
    val all = right == outcome.marks.size && outcome.failed == null
    Surface(shape = RoundedCornerShape(16.dp), color = Coal, border = BorderStroke(1.dp, Line.copy(alpha = 0.6f)), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(if (all) GoGreen else SeverityMedium, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (all) Icons.Rounded.Check else Icons.Rounded.Close, contentDescription = null, tint = Ink, modifier = Modifier.size(16.dp))
                }
                Text("NOTE ${outcome.number}", style = MaterialTheme.typography.labelLarge, color = Chalk, modifier = Modifier.weight(1f))
                Text(
                    text = "$right of ${outcome.marks.size} · %.1f s".format(outcome.speechSeconds + outcome.fieldSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = Fog,
                )
            }
            Text(outcome.note, style = MaterialTheme.typography.bodySmall, color = Fog)
            outcome.heard?.takeIf { it.isNotBlank() && outcome.wordsRight != null && outcome.wordsRight < 0.999f }?.let {
                Text("Heard as: $it", style = MaterialTheme.typography.bodySmall, color = Chalk)
            }
            outcome.failed?.let { Text("Could not be run: $it", style = MaterialTheme.typography.bodySmall, color = SignalRed) }
            if (outcome.failed == null) {
                outcome.marks.filter { !it.right }.forEach { mark ->
                    Text(
                        text = "${mark.field}: wanted ${mark.expected.ifBlank { "nothing" }}, got ${mark.got.ifBlank { "nothing" }}" +
                            if (mark.madeUp) " (made up)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (mark.madeUp) SignalRed else SeverityMedium,
                    )
                }
            }
        }
    }
}
