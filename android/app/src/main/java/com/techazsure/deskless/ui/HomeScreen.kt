package com.techazsure.deskless.ui

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.techazsure.deskless.AppState
import com.techazsure.deskless.Filter
import com.techazsure.deskless.Screen
import com.techazsure.deskless.Sort
import com.techazsure.deskless.data.Check
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.data.Template
import com.techazsure.deskless.report.PdfReport
import com.techazsure.deskless.report.ReportEntry
import com.techazsure.deskless.report.ReportPack
import com.techazsure.deskless.report.SignOff
import com.techazsure.deskless.ui.theme.Chalk
import com.techazsure.deskless.ui.theme.Coal
import com.techazsure.deskless.ui.theme.Fog
import com.techazsure.deskless.ui.theme.GoGreen
import com.techazsure.deskless.ui.theme.Ink
import com.techazsure.deskless.ui.theme.Line
import com.techazsure.deskless.ui.theme.SafetyYellow
import com.techazsure.deskless.ui.theme.SeverityMedium
import com.techazsure.deskless.ui.theme.Steel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.Signature
import java.util.Date
import kotlin.coroutines.resume

@Composable
fun HomeScreen(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var building by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("Building…") }
    var confirmNew by remember { mutableStateOf(false) }
    var pickTemplate by remember { mutableStateOf(false) }
    var concerns by remember { mutableStateOf(emptyList<Pair<Issue, String>>()) }

    fun buildReport() {
        building = true
        val inspection = state.inspection
        val issues = state.issues.toList()
        scope.launch {
            // The model writes the opening briefing first; without it the report is built all the same.
            stage = "Writing the briefing…"
            val briefing = state.writeBriefing(inspection, issues)
            // Before it goes out, the report's own evidence is checked: is it what it says it is?
            stage = "AI is reviewing…"
            val review = state.reviewReport(inspection, issues)
            // The fingerprint is asked for last, when everything else is ready, and signs the finished PDF.
            val signature = if (SignOff.available(context)) {
                stage = "Your fingerprint…"
                askToSign(context, inspection.inspector, inspection.site.ifBlank { inspection.template.title })
            } else {
                null
            }
            stage = "Building…"
            val speed = state.averageAiSeconds()?.let { seconds ->
                "Gemma 4 on this phone" + (state.gemmaBackend?.let { " ($it)" } ?: "") + ", about %.1f s per issue".format(seconds)
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PdfReport.newFile(context, inspection).also { file ->
                        val now = Date()
                        val mark = signature?.let { SignOff.keyId() }?.let { SignOff.Mark(inspection.inspector, now, it, SignOff.fileFor(file).name) }
                        SignOff.fileFor(file).delete()
                        PdfReport.build(inspection, issues, state::photoOf, file, briefing, speed, state::fileOf, mark, review)
                        // If the signature cannot be made after all, the report must not claim to be signed.
                        if (mark != null && signature != null && runCatching { SignOff.seal(file, signature, inspection.inspector, now) }.isFailure) {
                            SignOff.fileFor(file).delete()
                            PdfReport.build(inspection, issues, state::photoOf, file, briefing, speed, state::fileOf, review = review)
                        }
                        ReportPack.write(file, inspection, issues)
                    }
                }
            }
            building = false
            result
                .onSuccess {
                    state.recordReport(it, inspection, issues, review)
                    state.screen = Screen.Report(it.absolutePath)
                }
                .onFailure {
                    Toast.makeText(context, "Could not build the report: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    // The check before a report goes out: anything the AI doubted or has not finished is listed first.
    if (concerns.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { concerns = emptyList() },
            title = { Text("Check before you build") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    concerns.forEach { (issue, why) ->
                        val number = state.issues.indexOfFirst { it.id == issue.id } + 1
                        Text("Issue $number: $why", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val first = concerns.first().first.id
                    concerns = emptyList()
                    state.screen = Screen.Edit(first)
                }) { Text("Review") }
            },
            dismissButton = {
                TextButton(onClick = {
                    concerns = emptyList()
                    buildReport()
                }) { Text("Build anyway") }
            },
        )
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("Start a new one?") },
            text = {
                Text(
                    "The issues of this ${state.inspection.template.title.lowercase()}, with their photos, clips " +
                        "and voice notes, are removed from the phone. Reports you already built are kept."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmNew = false
                    state.startNewInspection()
                }) { Text("Start new") }
            },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Cancel") } },
        )
    }

    var editPlace by remember { mutableStateOf(false) }
    if (editPlace) {
        var name by remember { mutableStateOf(state.place) }
        AlertDialog(
            onDismissRequest = { editPlace = false },
            title = { Text("Tagged place") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name of this tag") },
                    singleLine = true,
                    supportingText = { Text("New issues are filed here until you tap another tag or leave.") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && name.trim() != state.place) state.renamePlace(name)
                    editPlace = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    state.leavePlace()
                    editPlace = false
                }) { Text("Leave this place") }
            },
        )
    }

    if (pickTemplate) {
        TemplatePicker(
            current = state.inspection.template,
            onPick = {
                state.changeTemplate(it)
                pickTemplate = false
            },
            onDismiss = { pickTemplate = false },
        )
    }

    // The side bar: this phone's QR code and the phones it is linked with. It opens from the yellow
    // button at the top left, or with a swipe from the left edge.
    val sideBar = rememberDrawerState(DrawerValue.Closed)
    // Back closes the side bar first, rather than leaving the app with it open.
    BackHandler(enabled = sideBar.isOpen) { scope.launch { sideBar.close() } }
    ModalNavigationDrawer(
        drawerState = sideBar,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Coal) {
                LinkPanel(state, onScan = { state.screen = Screen.Scan })
            }
        },
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
    ) {
        val shown = state.shownIssues()
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "brand") { BrandBar(state, onSideBar = { scope.launch { sideBar.open() } }) }
            item(key = "hero") { Hero(state, onNew = { confirmNew = true }, onChangeType = { pickTemplate = true }) }
            // A question about the walk, asked aloud and answered on the phone; and push to talk with
            // the linked phones, which needs no internet either.
            item(key = "ask") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.issues.isNotEmpty()) {
                        BrandButton(
                            text = "Ask the walk",
                            onClick = { state.screen = Screen.Ask },
                            icon = Icons.Rounded.Mic,
                            kind = ButtonKind.Secondary,
                            height = 50.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    BrandButton(
                        text = "Walkie-talkie" + state.talks.count { !it.heard }.let { if (it > 0) " · $it new" else "" },
                        onClick = { state.screen = Screen.Radio },
                        icon = Icons.Rounded.SettingsInputAntenna,
                        kind = ButtonKind.Secondary,
                        height = 50.dp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // On a newly installed app the models are on their way: the card says how far, and opens the AI screen.
            if (!(state.speechReady && state.gemmaReady)) {
                item(key = "models") { FetchCard(state.modelFetch, onClick = { state.screen = Screen.Models }) }
            }
            // Reports that came from other phones and have not been opened yet sit at the top until they are.
            state.unread.forEach { arrived ->
                item(key = "new-${arrived.fileName}") {
                    Box(Modifier.animateItem()) {
                        ArrivedCard(arrived, onClick = { state.screen = Screen.Report(state.reportFile(arrived).absolutePath) })
                    }
                }
            }
            if (state.place.isNotBlank()) {
                item(key = "place") { PlaceBar(state.place, onClick = { editPlace = true }) }
            }
            if (state.issues.isEmpty()) {
                item(key = "empty") { EmptyState(state.inspection.template) }
            } else {
                if (state.issues.count { it.located } >= 2) item(key = "map") { IssueMapCard(state) }
                item(key = "filters") { FilterBar(state) }
                if (shown.isEmpty()) {
                    item(key = "none") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("No issue matches this filter.", style = MaterialTheme.typography.bodyLarge, color = Fog)
                            TextButton(onClick = { state.filter = Filter.All }) { Text("Show all") }
                        }
                    }
                }
            }

            // Grouped by type, each type gets a heading; otherwise it is one run of cards.
            var heading: String? = null
            shown.forEach { issue ->
                val group = issue.category.ifBlank { AppState.UNSORTED }
                if (state.sort == Sort.TYPE && group != heading) {
                    heading = group
                    val size = shown.count { it.category.ifBlank { AppState.UNSORTED } == group }
                    item(key = "group-$group") {
                        Row(
                            modifier = Modifier
                                .animateItem()
                                .padding(start = 4.dp, top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(group.uppercase(), style = MaterialTheme.typography.labelMedium, color = SafetyYellow)
                            Text("$size", style = MaterialTheme.typography.labelMedium, color = Fog)
                        }
                    }
                }
                item(key = issue.id) {
                    // The number is the issue's place in the walk, whatever order it is shown in.
                    val number = state.issues.indexOfFirst { it.id == issue.id } + 1
                    Box(Modifier.animateItem()) { IssueCard(state, number, issue) }
                }
            }
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
                    text = if (building) stage else "Build report",
                    onClick = {
                        concerns = concernsBeforeReport(state)
                        if (concerns.isEmpty()) buildReport()
                    },
                    icon = Icons.Rounded.Description,
                    kind = ButtonKind.Secondary,
                    enabled = state.issues.isNotEmpty() && !building,
                    modifier = Modifier.weight(1f),
                )
                val glow = if (state.issues.isEmpty()) pulse(1f, 1.04f, 800) else 1f
                BrandButton(
                    text = "Add issue",
                    onClick = { state.screen = Screen.Capture() },
                    icon = Icons.Rounded.CameraAlt,
                    modifier = Modifier
                        .weight(1.15f)
                        .graphicsLayer {
                            scaleX = glow
                            scaleY = glow
                        },
                )
            }
        }
    }
    }
}

/** Shows the fingerprint prompt and waits for it. Null when the user builds unsigned or the phone cannot sign. */
private suspend fun askToSign(context: Context, who: String, what: String): Signature? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return suspendCancellableCoroutine { waiting ->
        SignOff.ask(context, who, what) { if (waiting.isActive) waiting.resume(it) }
    }
}

/** A report that has arrived from another phone and not been opened yet. A tap opens it. */
@Composable
private fun ArrivedCard(entry: ReportEntry, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(18.dp),
        color = GoGreen.copy(alpha = 0.14f),
        border = BorderStroke(1.5.dp, GoGreen.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .bouncy(interaction, pressedScale = 0.98f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Description, contentDescription = null, tint = GoGreen, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text("NEW REPORT RECEIVED", style = MaterialTheme.typography.labelSmall, color = GoGreen)
                Text(
                    text = entry.place.ifBlank { entry.template?.title ?: "Report" },
                    style = MaterialTheme.typography.titleMedium,
                    color = Chalk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("From ${entry.from}. Tap to open.", style = MaterialTheme.typography.bodySmall, color = Fog)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = GoGreen, modifier = Modifier.size(22.dp))
        }
    }
}

/** Where the phone was last tapped on a tag: new issues are filed there. A tap renames it or leaves. */
@Composable
private fun PlaceBar(place: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(18.dp),
        color = Steel,
        border = BorderStroke(1.dp, SafetyYellow.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .bouncy(interaction, pressedScale = 0.98f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Nfc, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text("TAGGED PLACE", style = MaterialTheme.typography.labelSmall, color = Fog)
                Text(place, style = MaterialTheme.typography.titleMedium, color = Chalk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("New issues go here", style = MaterialTheme.typography.bodySmall, color = Fog)
        }
    }
}

/** What should be looked at before these issues go into a report: each issue with the reason. */
private fun concernsBeforeReport(state: AppState): List<Pair<Issue, String>> =
    state.issues.mapNotNull { issue ->
        val why = when {
            issue.id in state.transcribing || issue.id in state.structuring -> "the AI is still working on it."
            issue.check == Check.MISMATCH -> "the picture may not show what the note says."
            issue.title.isBlank() -> "it has no title yet."
            issue.check == Check.UNSURE -> "the AI could not tell whether the picture shows it."
            else -> null
        }
        why?.let { issue to it }
    }

@Composable
private fun BrandBar(state: AppState, onSideBar: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Opens the side bar. A green dot says a linked phone is in range right now.
        Box {
            RoundIconButton(
                icon = Icons.Rounded.QrCode2,
                label = "Link phones: this phone's code",
                onClick = onSideBar,
                container = SafetyYellow,
                content = Ink,
                size = 38.dp,
            )
            if (state.link.connected.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(11.dp)
                        .background(Ink, CircleShape)
                        .padding(2.dp)
                        .background(GoGreen, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text("DeskLess", style = MaterialTheme.typography.headlineSmall, color = Chalk)
        Spacer(Modifier.weight(1f))

        RoundIconButton(
            icon = Icons.Rounded.Sensors,
            label = "The phone's sensors",
            onClick = { state.screen = Screen.Sensors },
            container = Steel,
            content = SafetyYellow,
            size = 38.dp,
        )
        Spacer(Modifier.width(8.dp))
        RoundIconButton(
            icon = Icons.Rounded.FolderCopy,
            label = "Reports built on this phone",
            onClick = { state.screen = Screen.Reports },
            container = Steel,
            size = 38.dp,
        )
        Spacer(Modifier.width(8.dp))

        val ready = state.speechReady && state.gemmaReady
        val color = if (ready) GoGreen else SeverityMedium
        val interaction = remember { MutableInteractionSource() }
        Surface(
            onClick = { state.screen = Screen.Models },
            interactionSource = interaction,
            shape = RoundedCornerShape(50),
            color = Steel,
            border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
            modifier = Modifier.bouncy(interaction),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val beat = pulse(0.45f, 1f, 900)
                Box(
                    Modifier
                        .size(8.dp)
                        .graphicsLayer { alpha = beat }
                        .background(color, CircleShape)
                )
                Text(
                    text = when {
                        !ready && state.modelFetch.busy -> "AI models ${state.modelFetch.percent}%"
                        !ready -> "Add AI models"
                        state.gemmaBackend != null -> "AI · ${state.gemmaBackend}"
                        else -> "AI on device"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Chalk,
                )
            }
        }
    }
}

@Composable
private fun Hero(state: AppState, onNew: () -> Unit, onChangeType: () -> Unit) {
    val inspection = state.inspection
    val template = inspection.template
    Surface(shape = RoundedCornerShape(24.dp), color = Coal, border = BorderStroke(1.dp, Line.copy(alpha = 0.6f))) {
        Column {
            HazardStripes(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The kind of report this is; tap to switch to another.
                    val interaction = remember { MutableInteractionSource() }
                    Surface(
                        onClick = onChangeType,
                        interactionSource = interaction,
                        shape = RoundedCornerShape(50),
                        color = SafetyYellow.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, SafetyYellow.copy(alpha = 0.5f)),
                        modifier = Modifier.bouncy(interaction),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(templateIcon(template), contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(17.dp))
                            Text(template.title, style = MaterialTheme.typography.labelMedium, color = SafetyYellow)
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = "Change the report type", tint = SafetyYellow, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.issues.isNotEmpty()) {
                        val newInteraction = remember { MutableInteractionSource() }
                        Surface(
                            onClick = onNew,
                            interactionSource = newInteraction,
                            shape = RoundedCornerShape(50),
                            color = Steel,
                            modifier = Modifier.bouncy(newInteraction),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = Fog, modifier = Modifier.size(16.dp))
                                Text("New", style = MaterialTheme.typography.labelMedium, color = Fog)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(template.placeLabel.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fog)
                Spacer(Modifier.height(2.dp))
                PlainField(
                    value = inspection.site,
                    onValueChange = { state.updateInspection(it, inspection.inspector) },
                    placeholder = template.placeHint,
                    style = MaterialTheme.typography.headlineMedium.copy(color = Chalk),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Person, contentDescription = null, tint = Fog, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    PlainField(
                        value = inspection.inspector,
                        onValueChange = { state.updateInspection(inspection.site, it) },
                        placeholder = template.personHint,
                        style = MaterialTheme.typography.bodyLarge.copy(color = Chalk),
                    )
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Line.copy(alpha = 0.6f))
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(
                        targetState = state.issues.size,
                        transitionSpec = {
                            (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                        },
                        label = "count",
                    ) { count ->
                        Text("$count", style = MaterialTheme.typography.displayMedium, color = SafetyYellow)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (state.issues.size == 1) "issue\nlogged" else "issues\nlogged",
                        style = MaterialTheme.typography.labelMedium,
                        color = Fog,
                    )
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Severity.entries.reversed().forEach { severity ->
                            val count = state.issues.count { it.severity == severity }
                            if (count > 0) SeverityCount(severity, count)
                        }
                    }
                }
            }
        }
    }
}

/** The kinds of report, one for each kind of place, as cards to choose from. */
@Composable
private fun TemplatePicker(current: Template, onPick: (Template) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = Coal, border = BorderStroke(1.dp, Line)) {
            // More kinds of place than fit on a screen: the list scrolls.
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("WHAT ARE YOU CHECKING?", style = MaterialTheme.typography.headlineSmall, color = Chalk)
                Text(
                    text = "The steps stay the same: picture, voice, report. The type sets the wording, how the AI " +
                        "rates severity, and how issues are sorted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Fog,
                )
                Template.entries.forEach { template ->
                    val chosen = template == current
                    val interaction = remember { MutableInteractionSource() }
                    Surface(
                        onClick = { onPick(template) },
                        interactionSource = interaction,
                        shape = RoundedCornerShape(18.dp),
                        color = if (chosen) SafetyYellow.copy(alpha = 0.12f) else Steel,
                        border = BorderStroke(1.5.dp, if (chosen) SafetyYellow else Line),
                        modifier = Modifier
                            .fillMaxWidth()
                            .bouncy(interaction, pressedScale = 0.97f),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(SafetyYellow, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(templateIcon(template), contentDescription = null, tint = Ink, modifier = Modifier.size(26.dp))
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(template.title, style = MaterialTheme.typography.titleMedium, color = Chalk)
                                Text(
                                    text = (if (template.custom) "Your company's own · " else "") + template.blurb,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Fog,
                                )
                            }
                            if (chosen) Icon(Icons.Rounded.Check, contentDescription = "Chosen", tint = SafetyYellow, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The heading over the list, with what to show and in which order. */
@Composable
private fun FilterBar(state: AppState) {
    var sortOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ISSUES ON THIS WALK", style = MaterialTheme.typography.labelSmall, color = Fog)
            Spacer(Modifier.weight(1f))
            Box {
                val interaction = remember { MutableInteractionSource() }
                Surface(
                    onClick = { sortOpen = true },
                    interactionSource = interaction,
                    shape = RoundedCornerShape(50),
                    color = Steel,
                    modifier = Modifier.bouncy(interaction),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(16.dp))
                        Text(state.sort.label, style = MaterialTheme.typography.labelMedium, color = Chalk)
                    }
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    Sort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.label) },
                            onClick = {
                                state.sort = sort
                                sortOpen = false
                            },
                            trailingIcon = {
                                if (sort == state.sort) Icon(Icons.Rounded.Check, contentDescription = null, tint = SafetyYellow)
                            },
                        )
                    }
                }
            }
        }

        // One choice at a time: everything, what needs a look, a severity, or a type.
        val flagged = state.issues.count(state::needsALook)
        val types = state.issues.map { it.category.ifBlank { AppState.UNSORTED } }.distinct()
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Pill("All", selected = state.filter == Filter.All, count = state.issues.size, onClick = { state.filter = Filter.All })
            }
            if (flagged > 0) {
                item {
                    Pill(
                        text = "Needs a look",
                        selected = state.filter == Filter.NeedsALook,
                        dot = SeverityMedium,
                        count = flagged,
                        onClick = { state.filter = Filter.NeedsALook },
                    )
                }
            }
            // Open and Fixed appear once something has been closed out.
            val fixed = state.issues.count { it.resolved }
            if (fixed > 0) {
                item {
                    Pill("Open", selected = state.filter == Filter.Open, count = state.issues.size - fixed, onClick = { state.filter = Filter.Open })
                }
                item {
                    Pill("Fixed", selected = state.filter == Filter.Fixed, dot = GoGreen, count = fixed, onClick = { state.filter = Filter.Fixed })
                }
            }
            items(Severity.entries.reversed().filter { severity -> state.issues.any { it.severity == severity } }) { severity ->
                Pill(
                    text = severity.label,
                    selected = state.filter == Filter.BySeverity(severity),
                    dot = severityColor(severity),
                    count = state.issues.count { it.severity == severity },
                    onClick = { state.filter = Filter.BySeverity(severity) },
                )
            }
            items(types) { type ->
                Pill(
                    text = type,
                    selected = state.filter == Filter.ByCategory(type),
                    count = state.issues.count { it.category.ifBlank { AppState.UNSORTED } == type },
                    onClick = { state.filter = Filter.ByCategory(type) },
                )
            }
        }
    }
}

@Composable
private fun SeverityCount(severity: Severity, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$count", style = MaterialTheme.typography.titleLarge, color = severityColor(severity))
        Text(severity.label, style = MaterialTheme.typography.bodySmall, color = Fog)
    }
}

/** A text field with no box around it, for the place and person in the header card. */
@Composable
private fun PlainField(value: String, onValueChange: (String) -> Unit, placeholder: String, style: TextStyle) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(SafetyYellow),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { field ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = Fog.copy(alpha = 0.55f)))
                field()
            }
        },
    )
}

@Composable
private fun EmptyState(template: Template) {
    val float = pulse(-6f, 6f, 1600)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 210.dp, height = 150.dp)
                .graphicsLayer { translationY = float.dp.toPx() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val corner = 26.dp.toPx()
                drawRoundRect(
                    color = Line,
                    cornerRadius = CornerRadius(corner),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 14f))),
                )
                // Viewfinder corners, as on the capture screen.
                val arm = 26.dp.toPx()
                val inset = 14.dp.toPx()
                val width = 4.dp.toPx()
                listOf(
                    Offset(inset, inset) to Offset(1f, 1f),
                    Offset(size.width - inset, inset) to Offset(-1f, 1f),
                    Offset(inset, size.height - inset) to Offset(1f, -1f),
                    Offset(size.width - inset, size.height - inset) to Offset(-1f, -1f),
                ).forEach { (at, dir) ->
                    drawLine(SafetyYellow, at, Offset(at.x + arm * dir.x, at.y), width, StrokeCap.Round)
                    drawLine(SafetyYellow, at, Offset(at.x, at.y + arm * dir.y), width, StrokeCap.Round)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CameraAlt, contentDescription = null, tint = Chalk, modifier = Modifier.size(44.dp))
                Text("+", style = MaterialTheme.typography.headlineMedium, color = Fog)
                Icon(Icons.Rounded.Mic, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(template.callToAction, style = MaterialTheme.typography.headlineMedium, color = Chalk)
        Spacer(Modifier.height(8.dp))
        Text(
            text = template.guide,
            style = MaterialTheme.typography.bodyLarge,
            color = Fog,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun IssueCard(state: AppState, number: Int, issue: Issue) {
    val appear = remember(issue.id) { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = appear,
        enter = fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 3 },
    ) {
        val interaction = remember { MutableInteractionSource() }
        val accent = if (issue.resolved) GoGreen else severityColor(issue.severity)
        Surface(
            onClick = { state.screen = Screen.Edit(issue.id) },
            interactionSource = interaction,
            shape = RoundedCornerShape(20.dp),
            color = Coal,
            border = BorderStroke(1.dp, Line.copy(alpha = 0.6f)),
            modifier = Modifier
                .fillMaxWidth()
                .bouncy(interaction, pressedScale = 0.97f),
        ) {
            Row(modifier = Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(5.dp)
                        .fillMaxHeight()
                        .background(accent)
                )
                Box(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp)) {
                    Photo(
                        file = state.photoOf(issue),
                        maxEdge = 256,
                        modifier = Modifier
                            .size(78.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                    Text(
                        text = "$number",
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink,
                        modifier = Modifier
                            .padding(5.dp)
                            .background(SafetyYellow, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (issue.category.isNotBlank() || issue.resolved) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (issue.resolved) Text("FIXED", style = MaterialTheme.typography.labelSmall, color = GoGreen)
                            if (issue.category.isNotBlank()) {
                                Text(issue.category.uppercase(), style = MaterialTheme.typography.labelSmall, color = SafetyYellow)
                            }
                        }
                    }
                    Text(
                        text = issue.title.ifBlank { "Untitled issue" },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (issue.title.isBlank()) Fog else Chalk,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (issue.location.isNotBlank()) {
                        Meta(Icons.Rounded.Place, issue.location)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SeverityPill(issue.severity)
                        when {
                            issue.id in state.transcribing -> Working(Icons.Rounded.GraphicEq, "Writing your note…")
                            issue.id in state.structuring -> Working(Icons.Rounded.AutoAwesome, "Checking and filling…")
                            else -> {
                                if (issue.voiceFile != null) {
                                    Meta(if (issue.videoFile != null) Icons.Rounded.Videocam else Icons.Rounded.Mic, "${issue.voiceSeconds} s")
                                }
                                CheckMark(issue.check)
                            }
                        }
                    }
                }
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Fog,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun Meta(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Fog, modifier = Modifier.size(15.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Fog, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A status that breathes while a model is working on this issue. */
@Composable
private fun Working(icon: ImageVector, text: String) {
    val beat = pulse(0.4f, 1f, 650)
    Row(
        modifier = Modifier.graphicsLayer { alpha = beat },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(15.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = SafetyYellow, maxLines = 1)
    }
}
