package com.techazsure.deskless.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.techazsure.deskless.AppState
import com.techazsure.deskless.link.Pairing
import com.techazsure.deskless.link.Qr
import com.techazsure.deskless.link.Stage
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

/**
 * The side bar: this phone's QR code, the phones it is linked with, and what the link is doing. Another
 * phone scans the code once; after that the two find each other by themselves and reports pass between
 * them with no prompt on either side.
 */
@Composable
fun LinkPanel(state: AppState, onScan: () -> Unit) {
    val link = state.link
    // Coming back from a system prompt redraws the rows below with the new answer.
    var asked by remember { mutableIntStateOf(0) }
    val turnOn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        link.start()
        asked++
    }
    val allow = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        link.start()
        asked++
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(324.dp)
            .background(Coal)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("LINK PHONES", style = MaterialTheme.typography.headlineSmall, color = Chalk)
        Text(
            text = "Scan this code with DeskLess on another phone. The two are then linked: reports pass " +
                "between them with no prompts and no network.",
            style = MaterialTheme.typography.bodySmall,
            color = Fog,
        )

        QrCode(
            text = Pairing.uri(link.me),
            modifier = Modifier
                .size(236.dp)
                .align(Alignment.CenterHorizontally),
        )
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(link.me.name, style = MaterialTheme.typography.titleMedium, color = Chalk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(link.me.id, style = MaterialTheme.typography.labelLarge, color = SafetyYellow)
        }

        BrandButton(
            text = "Scan another phone",
            onClick = onScan,
            icon = Icons.Rounded.QrCodeScanner,
            modifier = Modifier.fillMaxWidth(),
            height = 52.dp,
        )

        // What the link needs from the phone, each one tap away when it is missing.
        val bluetooth = remember(asked, link.running) { link.bluetoothOn }
        val allowed = remember(asked, link.running) { link.allowed }
        StatusRow(
            icon = Icons.Rounded.Bluetooth,
            title = "Bluetooth",
            value = when {
                bluetooth == null -> "Not on this phone"
                !allowed -> "Tap to allow nearby devices"
                bluetooth -> "On"
                else -> "Off · tap to turn on"
            },
            tone = when {
                bluetooth == null -> SignalRed
                !allowed || !bluetooth -> SeverityMedium
                else -> GoGreen
            },
            onClick = when {
                !allowed -> ({ allow.launch(link.permissions) })
                bluetooth == false -> ({ runCatching { turnOn.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } })
                else -> null
            },
        )
        StatusRow(
            icon = Icons.Rounded.Sensors,
            title = "Link",
            value = when {
                link.problem != null -> "Could not start: ${link.problem}"
                !link.running -> "Off"
                link.connected.isNotEmpty() -> if (link.connected.size == 1) "Connected to 1 phone" else "Connected to ${link.connected.size} phones"
                link.peers.isEmpty() -> "Ready to be scanned"
                else -> "Looking for linked phones"
            },
            tone = when {
                link.problem != null -> SignalRed
                !link.running -> SeverityMedium
                else -> GoGreen
            },
            onClick = if (!link.running) ({ link.start().also { asked++ } }) else null,
        )

        Text("LINKED PHONES", style = MaterialTheme.typography.labelSmall, color = Fog)
        if (link.peers.isEmpty()) {
            Text("None yet. Scan another phone, or let it scan this one.", style = MaterialTheme.typography.bodySmall, color = Fog)
        }
        link.peers.forEach { peer ->
            val near = peer.id in link.connected
            val queued = link.sent.count { it.to == peer.id && it.stage != Stage.DELIVERED && it.stage != Stage.HANDED }
            Surface(shape = RoundedCornerShape(16.dp), color = Steel, border = BorderStroke(1.dp, if (near) GoGreen.copy(alpha = 0.6f) else Line)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = if (near) GoGreen else Fog, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(peer.name, style = MaterialTheme.typography.titleMedium, color = Chalk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            // Whether the link has moved up to Wi-Fi decides if a report takes seconds or minutes.
                            text = when {
                                peer.public.isBlank() -> "Linked, not met yet. Open DeskLess on both phones."
                                !near -> "Not in range"
                                link.fast[peer.id] == true -> "Connected · fast link"
                                else -> "Connected · Bluetooth only, slow"
                            } +
                                when (queued) {
                                    0 -> ""
                                    1 -> " · 1 report on its way"
                                    else -> " · $queued reports on their way"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (near) GoGreen else Fog,
                        )
                    }
                    RoundIconButton(Icons.Rounded.Close, "Unlink ${peer.name}", onClick = { link.forget(peer.id) }, container = Color.Transparent, content = Fog, size = 36.dp)
                }
            }
        }
        // What this phone is carrying for others. It holds the sealed copies and cannot open them.
        if (link.carrying.isNotEmpty()) {
            Text("CARRYING FOR OTHERS", style = MaterialTheme.typography.labelSmall, color = Fog)
            Surface(shape = RoundedCornerShape(16.dp), color = Steel, border = BorderStroke(1.dp, Line)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    link.carrying.forEach { label ->
                        val goal = link.peers.firstOrNull { it.id == label.to }?.name ?: label.to
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(16.dp))
                            Text("From ${label.fromName} for $goal", style = MaterialTheme.typography.bodyMedium, color = Chalk)
                        }
                    }
                    Text(
                        text = "Sealed for the phone they are for. This phone passes them on and cannot open them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Fog,
                    )
                }
            }
        }
        if (link.connected.any { link.fast[it] != true }) {
            Text(
                text = "For a fast link, switch Wi-Fi on on both phones. It does not have to be joined to any network.",
                style = MaterialTheme.typography.bodySmall,
                color = SeverityMedium,
            )
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun StatusRow(icon: ImageVector, title: String, value: String, tone: Color, onClick: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        interactionSource = interaction,
        shape = RoundedCornerShape(16.dp),
        color = Steel,
        modifier = Modifier
            .fillMaxWidth()
            .bouncy(interaction, pressedScale = 0.98f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fog)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = Chalk)
            }
            Box(
                Modifier
                    .size(8.dp)
                    .background(tone, CircleShape)
            )
        }
    }
}

/** A QR code drawn square by square on a white tile, sharp at any size. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val squares = remember(text) { runCatching { Qr.squares(text) }.getOrNull() }
    Canvas(modifier) {
        drawRoundRect(Color.White, cornerRadius = CornerRadius(18.dp.toPx()))
        val code = squares ?: return@Canvas
        // A QR code needs a quiet white border to be read; four squares is the rule.
        val cell = size.minDimension / (code.width + 8)
        val start = (size.minDimension - cell * code.width) / 2
        for (row in 0 until code.height) {
            for (column in 0 until code.width) {
                if (code.get(column, row)) {
                    // A hair of overlap stops white seams showing between neighbouring squares.
                    drawRect(Ink, Offset(start + column * cell, start + row * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
