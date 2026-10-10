package com.techazsure.deskless.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.techazsure.deskless.AppState
import com.techazsure.deskless.MainActivity
import com.techazsure.deskless.Screen
import com.techazsure.deskless.capture.Locator
import com.techazsure.deskless.data.Readings
import com.techazsure.deskless.report.SignOff
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

/** How one of the phone's parts stands: working, there but needing something from the user, or absent. */
private enum class Standing(val tone: Color) { Live(GoGreen), Needs(SeverityMedium), Missing(SignalRed) }

/**
 * The phone as an instrument: every part DeskLess measures with, what it reads this moment, and what the
 * app does with it. It doubles as a check that each one works on the phone in your hand.
 */
@Composable
fun SensorsScreen(state: AppState) {
    val context = LocalContext.current
    val sensors = state.sensors
    BackHandler { state.screen = Screen.Home }
    DisposableEffect(Unit) {
        sensors.watch()
        onDispose { sensors.unwatch() }
    }

    fun allowed(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    // Asking for a permission from here redraws the tiles with the answer.
    var asked by remember { mutableStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        sensors.countSteps()
        asked++
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
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = { state.screen = Screen.Home }, container = Steel)
            Spacer(Modifier.width(14.dp))
            Text("PHONE AS INSTRUMENT", style = MaterialTheme.typography.headlineSmall, color = Chalk)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Everything DeskLess measures with, live. None of it needs a network.",
                style = MaterialTheme.typography.bodyMedium,
                color = Fog,
            )

            // The compass gets a needle of its own: it is the reading people check first.
            Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.dp, Line.copy(alpha = 0.6f))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Compass(sensors.heading)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("CAMERA FACING", style = MaterialTheme.typography.labelSmall, color = Fog)
                        Text(
                            text = sensors.heading?.let { "${Readings.compassPoint(it).replaceFirstChar(Char::uppercase)} · ${it.toInt()}°" }
                                ?: if (sensors.hasCompass) (Readings.aim(sensors.pitch)?.let { "Aimed $it" } ?: "Finding north…") else "No compass on this phone",
                            style = MaterialTheme.typography.titleLarge,
                            color = Chalk,
                        )
                        Text(
                            text = sensors.pitch?.let { pitch ->
                                when {
                                    pitch > 2f -> "${pitch.toInt()}° above the horizon"
                                    pitch < -2f -> "${(-pitch).toInt()}° below the horizon"
                                    else -> "Level with the horizon"
                                }
                            } ?: " ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Fog,
                        )
                        Text("Stamped on every picture: which wall, and whether it is overhead or at ground level.", style = MaterialTheme.typography.bodySmall, color = Fog)
                    }
                }
            }

            Tile(
                icon = Icons.Rounded.WbSunny,
                name = "Light",
                value = sensors.lux?.let { "${it.toInt()} lux · ${Readings.light(it)}" } ?: if (sensors.hasLight) "Reading…" else "Not on this phone",
                use = "Brightness at each issue, and the torch comes on by itself in the dark.",
                standing = if (sensors.hasLight) Standing.Live else Standing.Missing,
            )
            Tile(
                icon = Icons.Rounded.Straighten,
                name = "Level and vibration",
                value = if (sensors.pitch != null || sensors.upright) "Motion sensors running" else "Reading…",
                use = "Slope of a floor or wall in degrees and mm per metre; how hard a machine shakes and at what frequency.",
                standing = Standing.Live,
            )
            Tile(
                icon = Icons.Rounded.Mic,
                name = "Microphone",
                value = if (allowed(Manifest.permission.RECORD_AUDIO)) "Allowed" else "Tap to allow",
                use = "Your spoken note becomes English text on the phone; the noise meter reads decibels.",
                standing = if (allowed(Manifest.permission.RECORD_AUDIO)) Standing.Live else Standing.Needs,
                onClick = { ask.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) },
            )
            Tile(
                icon = Icons.Rounded.CameraAlt,
                name = "Cameras",
                value = if (allowed(Manifest.permission.CAMERA)) "Allowed" else "Tap to allow",
                use = "Photo or short video of each issue, with wide and long lenses where the phone offers them. The AI checks the picture against your note.",
                standing = if (allowed(Manifest.permission.CAMERA)) Standing.Live else Standing.Needs,
                onClick = { ask.launch(arrayOf(Manifest.permission.CAMERA)) },
            )
            Tile(
                icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
                name = "Step counter",
                value = when {
                    !sensors.hasStepCounter -> "Not on this phone"
                    !sensors.stepsAllowed -> "Tap to allow"
                    sensors.steps < 0 -> "Waiting for the first step"
                    else -> "${sensors.steps} steps since the app opened"
                },
                use = "How far you walked from one issue to the next, where satellites cannot reach.",
                standing = when {
                    !sensors.hasStepCounter -> Standing.Missing
                    !sensors.stepsAllowed -> Standing.Needs
                    else -> Standing.Live
                },
                onClick = { ask.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)) },
            )
            Tile(
                icon = Icons.Rounded.PhoneInTalk,
                name = "Proximity",
                value = when {
                    !sensors.hasProximity -> "Not on this phone"
                    sensors.near -> "Something is close"
                    else -> "Clear"
                },
                use = "Lift the phone to your ear and speak: the note records with no button, for gloved or dirty hands.",
                standing = if (sensors.hasProximity) Standing.Live else Standing.Missing,
            )
            Tile(
                icon = Icons.Rounded.MyLocation,
                name = "Satellite position",
                value = if (state.locationAllowed) "Allowed" else "Tap to allow",
                use = "Where each issue is, and the map of the walk. Satellites alone are enough; it works in airplane mode.",
                standing = if (state.locationAllowed) Standing.Live else Standing.Needs,
                onClick = { ask.launch(Locator.PERMISSIONS) },
            )
            val nfc = (context as? MainActivity)?.nfcOn
            Tile(
                icon = Icons.Rounded.Nfc,
                name = "NFC",
                value = when (nfc) {
                    null -> "Not on this phone"
                    false -> "Switched off in the phone's settings"
                    true -> if (state.knownTags == 0) "On · tap a tag to name it" else "On · ${state.knownTags} tags known"
                },
                use = "Tap a sticker on a door, a machine or a medicine fridge and new issues are filed there. Any chip card works.",
                standing = when (nfc) {
                    null -> Standing.Missing
                    false -> Standing.Needs
                    true -> Standing.Live
                },
            )
            val signs = remember(asked) { SignOff.available(context) }
            Tile(
                icon = Icons.Rounded.Fingerprint,
                name = "Fingerprint",
                value = if (signs) "Ready" + (remember(asked) { SignOff.keyId() }?.let { " · signing key $it" } ?: "") else "No fingerprint set up on this phone",
                use = "Signs the finished report. The signature travels with the PDF and breaks if the PDF is changed.",
                standing = if (signs) Standing.Live else Standing.Needs,
            )
            Tile(
                icon = Icons.Rounded.Vibration,
                name = "Motor",
                value = "Tap to feel the alert",
                use = "A double tap when the AI has filled the report, three firm pulses when it rates an issue High or Critical.",
                standing = Standing.Live,
                onClick = { state.haptics.alert() },
            )

            Tile(
                icon = Icons.Rounded.RecordVoiceOver,
                name = "Voice",
                value = if (state.voice.ready) "Ready · tap to hear it" else "No speech engine found on this phone",
                use = "The phone says \"Report sent\" and \"Report received\" when a report crosses to a linked phone.",
                standing = if (state.voice.ready) Standing.Live else Standing.Needs,
                onClick = { state.voice.say("Report sent") },
            )

            // The whole list, as the phone gives it: useful to see what else this model offers.
            val all = remember { sensors.inventory() }
            Spacer(Modifier.height(6.dp))
            Text("EVERY SENSOR THIS PHONE REPORTS · ${all.size}", style = MaterialTheme.typography.labelSmall, color = Fog)
            Text(all.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = Fog)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Tile(
    icon: ImageVector,
    name: String,
    value: String,
    use: String,
    standing: Standing,
    onClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        interactionSource = interaction,
        shape = RoundedCornerShape(20.dp),
        color = Coal,
        border = BorderStroke(1.dp, Line.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .bouncy(interaction, pressedScale = 0.98f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Steel, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(24.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fog)
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(standing.tone, CircleShape)
                    )
                }
                Text(value, style = MaterialTheme.typography.titleMedium, color = Chalk)
                Text(use, style = MaterialTheme.typography.bodySmall, color = Fog)
            }
        }
    }
}

/** A compass card that turns under a fixed mark, so the letter under the mark is the way the camera faces. */
@Composable
private fun Compass(heading: Float?) {
    // Turn the short way round when the reading crosses north.
    val turned = remember { floatArrayOf(0f) }
    if (heading != null) turned[0] += ((-heading - turned[0]) % 360f + 540f) % 360f - 180f
    val angle by animateFloatAsState(turned[0], spring(dampingRatio = 0.8f, stiffness = 120f), label = "compass")
    Canvas(Modifier.size(96.dp)) {
        val radius = size.minDimension / 2
        val centre = Offset(size.width / 2, size.height / 2)
        drawCircle(Steel, radius, centre)
        drawCircle(Line, radius, centre, style = Stroke(1.5.dp.toPx()))
        rotate(angle, centre) {
            for (tick in 0 until 12) {
                rotate(tick * 30f, centre) {
                    drawLine(
                        color = if (tick == 0) SafetyYellow else Fog,
                        start = Offset(centre.x, centre.y - radius + 5.dp.toPx()),
                        end = Offset(centre.x, centre.y - radius + (if (tick % 3 == 0) 16 else 10).dp.toPx()),
                        strokeWidth = (if (tick == 0) 4f else 1.5f).dp.toPx(),
                    )
                }
            }
            // The needle points to north on the card.
            val needle = Path().apply {
                moveTo(centre.x, centre.y - radius * 0.55f)
                lineTo(centre.x - 7.dp.toPx(), centre.y)
                lineTo(centre.x + 7.dp.toPx(), centre.y)
                close()
            }
            drawPath(needle, if (heading == null) Fog else SafetyYellow)
        }
        // The fixed mark at the top: the camera's direction.
        drawLine(Chalk, Offset(centre.x, 0f), Offset(centre.x, 7.dp.toPx()), 3.dp.toPx())
        drawCircle(Chalk, 3.dp.toPx(), centre)
    }
}
