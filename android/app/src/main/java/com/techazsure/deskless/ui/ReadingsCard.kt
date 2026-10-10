package com.techazsure.deskless.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.techazsure.deskless.AppState
import com.techazsure.deskless.capture.LevelMeter
import com.techazsure.deskless.capture.NoiseMeter
import com.techazsure.deskless.capture.Signal
import com.techazsure.deskless.capture.VibrationMeter
import com.techazsure.deskless.data.Instrument
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.Reading
import com.techazsure.deskless.data.Readings
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
import java.util.Locale

fun instrumentIcon(kind: Instrument): ImageVector = when (kind) {
    Instrument.LEVEL -> Icons.Rounded.Straighten
    Instrument.NOISE -> Icons.AutoMirrored.Rounded.VolumeUp
    Instrument.VIBRATION -> Icons.Rounded.Vibration
}

/** A reading as the two lines of an instrument tile: the number, and what it means. */
private fun short(reading: Reading): Pair<String, String> = when (reading.kind) {
    Instrument.LEVEL -> String.format(Locale.ENGLISH, "%.1f°", reading.value) to "${Readings.millimetresPerMetre(reading.value)} mm/m"
    Instrument.NOISE -> String.format(Locale.ENGLISH, "%.0f dB", reading.value) to Readings.noise(reading.value)
    Instrument.VIBRATION -> String.format(Locale.ENGLISH, "%.2f m/s²", reading.value) to
        if (reading.extra > 0f) String.format(Locale.ENGLISH, "%.0f Hz", reading.extra) else Readings.vibration(reading.value)
}

/**
 * What the phone measured for this issue: the readings stamped when the picture was taken, and the three
 * instruments the inspector can use on the spot. The readings go to the on-device model and into the report.
 */
@Composable
fun ReadingsCard(state: AppState, issue: Issue) {
    var open by remember(issue.id) { mutableStateOf<Instrument?>(null) }
    val stamp = issue.stamp

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Coal,
        border = BorderStroke(1.dp, Line.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("READINGS ON THE SPOT", style = MaterialTheme.typography.labelSmall, color = Fog)

            // What the sensors read by themselves at the moment of the picture.
            val stamped = listOfNotNull(
                issue.place.takeIf { it.isNotBlank() }?.let { Icons.Rounded.Nfc to it },
                stamp?.lux?.let { Icons.Rounded.WbSunny to "${it.toInt()} lux · ${Readings.light(it)}" },
                stamp?.heading?.let { Icons.Rounded.Explore to "Facing ${Readings.compassPoint(it)}" },
                Readings.aim(stamp?.pitch)?.let { Icons.Rounded.SwapVert to "Aimed $it" },
                stamp?.steps?.takeIf { it > 0 }?.let { Icons.AutoMirrored.Rounded.DirectionsWalk to "$it steps from the last" },
                stamp?.zoom?.takeIf { it < 0.95f || it > 1.05f }?.let { Icons.Rounded.ZoomIn to "%.1f×".format(Locale.ENGLISH, it).replace(".0×", "×") },
                stamp?.takeIf { it.torch }?.let { Icons.Rounded.FlashOn to "Torch" },
            )
            if (stamped.isEmpty()) {
                Text(
                    text = "The phone's sensors stamp each new picture with the light, the direction and the steps walked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Fog,
                )
            } else {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    stamped.forEach { (icon, text) -> StampChip(icon, text) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Instrument.entries.forEach { kind ->
                    InstrumentTile(
                        kind = kind,
                        reading = issue.readings.lastOrNull { it.kind == kind },
                        onClick = { open = kind },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // A reading taken after the AI ran is not in its answer yet.
            if (issue.readings.isNotEmpty() && issue.note.isNotBlank() && state.gemmaReady) {
                if (issue.id in state.structuring) {
                    Text("The AI is reading the note, the photo and the readings…", style = MaterialTheme.typography.bodySmall, color = SafetyYellow)
                } else {
                    TextButton(onClick = { state.fillFields(issue.id) }) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Let the AI use the readings")
                    }
                }
            }
            Text(
                text = "Measured by this phone's own sensors. A guide, not a calibrated instrument.",
                style = MaterialTheme.typography.bodySmall,
                color = Fog.copy(alpha = 0.7f),
            )
        }
    }

    val current = issue.readings.lastOrNull { it.kind == open }
    when (open) {
        Instrument.LEVEL -> LevelDialog(state, current, onSave = { state.addReading(issue.id, it) }, onRemove = { state.removeReading(issue.id, it) }) { open = null }
        Instrument.NOISE -> NoiseDialog(current, onSave = { state.addReading(issue.id, it) }, onRemove = { state.removeReading(issue.id, it) }) { open = null }
        Instrument.VIBRATION -> VibrationDialog(current, onSave = { state.addReading(issue.id, it) }, onRemove = { state.removeReading(issue.id, it) }) { open = null }
        null -> Unit
    }
}

@Composable
private fun StampChip(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .background(Steel, RoundedCornerShape(50))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(15.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Chalk, maxLines = 1)
    }
}

@Composable
private fun InstrumentTile(kind: Instrument, reading: Reading?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    // A reading that is itself a finding, such as a floor well out of level, is edged in red.
    val edge by animateColorAsState(
        targetValue = when {
            reading == null -> Line
            Readings.notable(reading) -> SignalRed
            else -> SafetyYellow
        },
        label = "tile",
    )
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(16.dp),
        color = Steel,
        border = BorderStroke(1.5.dp, edge),
        modifier = modifier.bouncy(interaction, pressedScale = 0.94f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = instrumentIcon(kind),
                contentDescription = null,
                tint = if (reading != null && Readings.notable(reading)) SignalRed else SafetyYellow,
                modifier = Modifier.size(24.dp),
            )
            val words = reading?.let(::short)
            Text(
                text = words?.first ?: kind.label,
                style = MaterialTheme.typography.titleMedium,
                color = Chalk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = words?.second ?: "Measure",
                style = MaterialTheme.typography.bodySmall,
                color = Fog,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The frame every instrument opens in: a title, the instrument itself, and Save, Remove and Close. */
@Composable
private fun InstrumentFrame(
    kind: Instrument,
    guide: String,
    saved: Reading?,
    canSave: Boolean,
    onSave: () -> Unit,
    onRemove: (Reading) -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(28.dp), color = Coal, border = BorderStroke(1.dp, Line)) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(instrumentIcon(kind), contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.size(10.dp))
                    Text(kind.label.uppercase(), style = MaterialTheme.typography.headlineSmall, color = Chalk)
                }
                Text(guide, style = MaterialTheme.typography.bodySmall, color = Fog, modifier = Modifier.fillMaxWidth())
                content()
                BrandButton(
                    text = "Save to this issue",
                    onClick = {
                        onSave()
                        onClose()
                    },
                    icon = Icons.Rounded.Check,
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                    height = 52.dp,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (saved != null) {
                        TextButton(onClick = {
                            onRemove(saved)
                            onClose()
                        }) { Text("Remove the saved one", color = SignalRed) }
                    } else {
                        Spacer(Modifier.size(1.dp))
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun LevelDialog(state: AppState, saved: Reading?, onSave: (Reading) -> Unit, onRemove: (Reading) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val meter = remember { LevelMeter(context) }
    DisposableEffect(Unit) {
        meter.start()
        onDispose { meter.stop() }
    }
    val level = meter.ready && meter.degrees < 0.3f
    // A tick from the motor when it finds true, as a good level clicks into place.
    LaunchedEffect(level) { if (level) state.haptics.tick() }

    InstrumentFrame(
        kind = Instrument.LEVEL,
        guide = if (meter.onEdge) {
            "On its edge: how far the edge is off level, or held against a wall, how far the wall is off plumb."
        } else {
            "Lying flat: the slope of the surface under the phone. Stand it on an edge to check an edge or a wall."
        },
        saved = saved,
        canSave = meter.ready,
        onSave = { onSave(Reading(Instrument.LEVEL, meter.degrees, extra = if (meter.onEdge) 1f else 0f, takenAt = System.currentTimeMillis())) },
        onRemove = onRemove,
        onClose = onClose,
    ) {
        val tone by animateColorAsState(if (level) GoGreen else SafetyYellow, label = "level")
        val x by animateFloatAsState(meter.bubbleX, spring(dampingRatio = 0.8f, stiffness = 200f), label = "x")
        val y by animateFloatAsState(meter.bubbleY, spring(dampingRatio = 0.8f, stiffness = 200f), label = "y")
        Canvas(Modifier.size(width = 220.dp, height = if (meter.onEdge) 70.dp else 200.dp)) {
            val bubble = 17.dp.toPx()
            if (meter.onEdge) {
                // A vial: a rounded tube with the two lines the bubble should sit between.
                val top = size.height / 2 - 24.dp.toPx()
                drawRoundRect(Steel, Offset(0f, top), Size(size.width, 48.dp.toPx()), CornerRadius(24.dp.toPx()))
                drawRoundRect(Line, Offset(0f, top), Size(size.width, 48.dp.toPx()), CornerRadius(24.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                listOf(-1, 1).forEach { side ->
                    val lineX = size.width / 2 + side * (bubble + 4.dp.toPx())
                    drawLine(Fog, Offset(lineX, top + 6.dp.toPx()), Offset(lineX, top + 42.dp.toPx()), 1.5.dp.toPx())
                }
                val travel = size.width / 2 - bubble - 6.dp.toPx()
                drawCircle(tone, bubble, Offset(size.width / 2 + x * travel, size.height / 2))
            } else {
                // A bullseye: the bubble rides to the high side and sits in the middle ring when level.
                val radius = size.minDimension / 2
                val centre = Offset(size.width / 2, size.height / 2)
                drawCircle(Steel, radius, centre)
                drawCircle(Line, radius, centre, style = Stroke(1.5.dp.toPx()))
                drawCircle(Fog, radius * 0.5f, centre, style = Stroke(1.dp.toPx()))
                drawCircle(Fog, bubble + 4.dp.toPx(), centre, style = Stroke(1.5.dp.toPx()))
                drawLine(Line, Offset(centre.x - radius, centre.y), Offset(centre.x + radius, centre.y), 1.dp.toPx())
                drawLine(Line, Offset(centre.x, centre.y - radius), Offset(centre.x, centre.y + radius), 1.dp.toPx())
                val travel = radius - bubble - 4.dp.toPx()
                drawCircle(tone, bubble, Offset(centre.x + x * travel, centre.y + y * travel))
            }
        }
        Text(
            text = if (meter.ready) String.format(Locale.ENGLISH, "%.1f°", meter.degrees) else "…",
            style = MaterialTheme.typography.displayMedium,
            color = tone,
        )
        Text(
            text = if (level) "Level" else "${Readings.millimetresPerMetre(meter.degrees)} mm per metre",
            style = MaterialTheme.typography.titleMedium,
            color = Chalk,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { meter.setZero() }) { Text("Set zero here") }
            if (meter.zeroed) TextButton(onClick = { meter.clearZero() }) { Text("Clear zero", color = Fog) }
        }
    }
}

/** A ring that fills as a measurement runs, with the live number inside it. */
@Composable
private fun MeasuringRing(progress: Float, tone: Color, number: String, caption: String) {
    Box(modifier = Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(190.dp)) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            drawArc(Steel, -90f, 360f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
            drawArc(
                tone, -90f, 360f * progress.coerceIn(0f, 1f), false,
                Offset(inset, inset), Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(number, style = MaterialTheme.typography.displaySmall, color = Chalk)
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = Fog, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun NoiseDialog(saved: Reading?, onSave: (Reading) -> Unit, onRemove: (Reading) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val meter = remember { NoiseMeter() }
    var result by remember { mutableStateOf<Reading?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun measure() {
        result = null
        problem = null
        meter.measure { average, peak ->
            if (average == null || peak == null) {
                problem = "Could not open the microphone."
            } else {
                result = Reading(Instrument.NOISE, average, extra = peak, takenAt = System.currentTimeMillis())
            }
        }
    }

    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) measure() else problem = "The noise meter needs the microphone."
    }
    LaunchedEffect(Unit) {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (allowed) measure() else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(Unit) { onDispose { meter.cancel() } }

    InstrumentFrame(
        kind = Instrument.NOISE,
        guide = "Hold the phone still and stay quiet for three seconds. The reading is the average loudness of the place.",
        saved = saved,
        canSave = result != null,
        onSave = { result?.let(onSave) },
        onRemove = onRemove,
        onClose = onClose,
    ) {
        val done = result
        val shown = done?.value ?: meter.now
        val tone = if (shown >= 85f) SignalRed else SafetyYellow
        MeasuringRing(
            progress = if (done != null) 1f else meter.progress,
            tone = tone,
            number = if (done != null || meter.measuring) String.format(Locale.ENGLISH, "%.0f dB", shown) else "…",
            caption = when {
                done != null -> Readings.noise(done.value)
                meter.measuring -> "listening"
                else -> ""
            },
        )
        Text(
            text = problem ?: done?.let { String.format(Locale.ENGLISH, "Loudest moment %.0f dB", it.extra) } ?: " ",
            style = MaterialTheme.typography.titleMedium,
            color = if (problem != null) SignalRed else Chalk,
        )
        TextButton(onClick = { measure() }, enabled = !meter.measuring && problem == null) { Text("Measure again") }
    }
}

@Composable
private fun VibrationDialog(saved: Reading?, onSave: (Reading) -> Unit, onRemove: (Reading) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val meter = remember { VibrationMeter(context) }
    var result by remember { mutableStateOf<Signal.Vibration?>(null) }
    var settling by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { meter.cancel() } }

    // The tap that starts it shakes the phone, so the measurement waits a moment for the hand to leave.
    LaunchedEffect(settling) {
        if (!settling) return@LaunchedEffect
        delay(1200)
        settling = false
        meter.measure { found ->
            if (found == null) problem = "This phone's motion sensor gave no readings." else result = found
        }
    }

    InstrumentFrame(
        kind = Instrument.VIBRATION,
        guide = "Rest the phone on the machine, pipe or floor, tap Start and take your hand away for five seconds.",
        saved = saved,
        canSave = result != null,
        onSave = { result?.let { onSave(Reading(Instrument.VIBRATION, it.rms, extra = it.hertz, takenAt = System.currentTimeMillis())) } },
        onRemove = onRemove,
        onClose = onClose,
    ) {
        val done = result
        val shown = done?.rms ?: meter.now
        MeasuringRing(
            progress = if (done != null) 1f else meter.progress,
            tone = if (shown >= 1.5f) SignalRed else SafetyYellow,
            number = when {
                settling -> "…"
                done != null || meter.measuring -> String.format(Locale.ENGLISH, "%.2f", shown)
                else -> "0.00"
            },
            caption = when {
                settling -> "hands off"
                done != null -> "m/s² · ${Readings.vibration(done.rms)}"
                else -> "m/s²"
            },
        )
        Text(
            text = problem ?: when {
                done == null -> " "
                done.hertz > 0f -> String.format(Locale.ENGLISH, "Mainly at %.0f Hz", done.hertz)
                else -> "No single frequency stands out"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (problem != null) SignalRed else Chalk,
        )
        BrandButton(
            text = if (done == null) "Start" else "Measure again",
            onClick = {
                result = null
                problem = null
                settling = true
            },
            kind = ButtonKind.Secondary,
            enabled = !meter.measuring && !settling && meter.available,
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
        )
    }
}
