package com.techazsure.deskless.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.techazsure.deskless.AppState
import com.techazsure.deskless.Screen
import com.techazsure.deskless.models.ModelCatalog
import com.techazsure.deskless.models.ModelFetch
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

@Composable
fun ModelsScreen(state: AppState) {
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        if (folder != null) state.importModelFolder(folder)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
        if (files.isNotEmpty()) state.importModelFiles(files)
    }
    BackHandler(enabled = !state.importing) { state.screen = Screen.Home }

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
            RoundIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                label = "Back",
                onClick = { if (!state.importing) state.screen = Screen.Home },
                container = Steel,
            )
            Spacer(Modifier.width(14.dp))
            Text("ON-DEVICE AI", style = MaterialTheme.typography.headlineSmall, color = Chalk)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Both models run on this phone: what you photograph and say is turned into the report here, with no " +
                    "network. They are too large to ship inside the app, so the app downloads them once and keeps them here.",
                style = MaterialTheme.typography.bodyLarge,
                color = Fog,
            )
            ModelCard(
                icon = Icons.Rounded.Translate,
                name = "Speech to English",
                detail = "Whisper small with Silero VAD · 360 MB",
                ready = state.speechReady,
            )
            // The language the notes are spoken in: Hindi with English, or one of five other Indian languages.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("You speak:", style = MaterialTheme.typography.bodyMedium, color = Fog)
                LanguageChip(state)
                Text("written down in English", style = MaterialTheme.typography.bodySmall, color = Fog, modifier = Modifier.weight(1f))
            }
            ModelCard(
                icon = Icons.Rounded.AutoAwesome,
                name = "Note and photo to report fields",
                detail = "Gemma 4 E2B · 2.6 GB" + (state.gemmaBackend?.let { " · running on the $it" } ?: ""),
                ready = state.gemmaReady,
            )

            FetchCard(state.modelFetch)

            MeasureCard(state)

            ProofCard(state)

            MemoryCard(state)

            Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.dp, Line.copy(alpha = 0.6f))) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("WITH NO INTERNET", style = MaterialTheme.typography.labelSmall, color = Fog)
                    HowTo(1, "Send the DeskLess-models folder from a laptop with Office Kit file transfer.")
                    HowTo(2, "Tap Import from a folder below and pick it. DeskLess copies and checks the files.")
                }
            }

            if (state.importing) {
                val progress = state.importProgress
                val fraction by animateFloatAsState(
                    targetValue = if (progress == null || progress.totalBytes <= 0) 0f else progress.copiedBytes.toFloat() / progress.totalBytes,
                    label = "import",
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Steel)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                                .height(10.dp)
                                .background(SafetyYellow)
                        )
                    }
                    Text(
                        text = if (progress == null) "Looking for the model files…"
                        else "${progress.file}: ${progress.copiedBytes shr 20} of ${progress.totalBytes shr 20} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = Fog,
                    )
                }
            }

            state.importResult?.let { result ->
                if (result.imported.isNotEmpty()) {
                    Text(
                        text = "Imported: ${result.imported.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GoGreen,
                    )
                }
                result.problems.forEach {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, color = SignalRed)
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val fetch = state.modelFetch
            if (fetch.busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrandButton(
                        text = "Stop",
                        onClick = { state.stopFetchingModels() },
                        kind = ButtonKind.Danger,
                        modifier = Modifier.weight(1f),
                    )
                    if (!fetch.mobileData && fetch.phase != ModelFetch.Phase.CHECKING) {
                        BrandButton(
                            text = "Use mobile data",
                            onClick = { state.fetchModels(mobileData = true) },
                            kind = ButtonKind.Secondary,
                            modifier = Modifier.weight(1.6f),
                        )
                    }
                }
            } else if (fetch.phase != ModelFetch.Phase.COMPLETE) {
                BrandButton(
                    text = "Download · ${ModelCatalog.size(fetch.missingBytes)}",
                    onClick = { state.fetchModels(mobileData = false) },
                    icon = Icons.Rounded.Download,
                    enabled = !state.importing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BrandButton(
                text = if (state.importing) "Importing…" else "Import from a folder",
                onClick = { pickFolder.launch(null) },
                icon = Icons.Rounded.FolderOpen,
                kind = ButtonKind.Secondary,
                enabled = !state.importing,
                height = 48.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            BrandButton(
                text = "Or pick the files one by one",
                onClick = { pickFiles.launch(arrayOf("*/*")) },
                kind = ButtonKind.Secondary,
                enabled = !state.importing,
                height = 48.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Where the fetching of the model files stands: how much has arrived, what is arriving now, what it is
 * waiting for, and what went wrong if something did. Nothing is shown once every model is on the phone.
 */
@Composable
fun FetchCard(fetch: ModelFetch, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    if (fetch.phase == ModelFetch.Phase.COMPLETE) return
    val fraction by animateFloatAsState(targetValue = fetch.fraction, label = "fetch")
    val shape = RoundedCornerShape(20.dp)
    val border = BorderStroke(1.dp, SafetyYellow.copy(alpha = 0.45f))
    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (fetch.busy) "GETTING THE AI MODELS" else "THE AI MODELS", style = MaterialTheme.typography.labelSmall, color = Fog)
            Text(fetch.words, style = MaterialTheme.typography.bodyLarge, color = Chalk)
            if (fetch.busy) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Steel)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                            .height(10.dp)
                            .background(SafetyYellow)
                    )
                }
                Text(
                    text = listOf("${fetch.percent}%", fetch.now).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Fog,
                )
            }
            fetch.problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SignalRed) }
            // What went wrong already says what to do next; otherwise a line on how the fetching behaves.
            if (fetch.busy || fetch.problem == null) Text(
                text = when {
                    fetch.stopped -> "You stopped the download. Tap Download to fetch them; until they are here, notes are not turned into text or report fields."
                    !fetch.busy -> "DeskLess downloads them by itself over Wi-Fi. Until they are here, notes are not turned into text or report fields."
                    fetch.phase == ModelFetch.Phase.WAITING_FOR_WIFI ->
                        "It goes on by itself when Wi-Fi is back. Use mobile data makes it go on now; the file that was arriving then starts again."
                    fetch.mobileData -> "Mobile data may be used. It goes on while DeskLess is closed, and picks up where it stopped if the network drops."
                    else -> "Over Wi-Fi only. It goes on while DeskLess is closed, and picks up where it stopped if the network drops."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Fog,
            )
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = Coal, border = border, modifier = modifier.fillMaxWidth(), content = content)
    } else {
        Surface(shape = shape, color = Coal, border = border, modifier = modifier.fillMaxWidth(), content = content)
    }
}

/**
 * What can be checked, not just claimed, about the data staying on the phone: whether Android has given
 * the app the right to use the network at all, and how fast the model runs here.
 */
@Composable
private fun ProofCard(state: AppState) {
    val context = LocalContext.current
    val online = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty().contains(Manifest.permission.INTERNET)
        }.getOrDefault(true)
    }
    val seconds = state.averageAiSeconds()

    Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.dp, GoGreen.copy(alpha = 0.45f))) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("PROOF IT STAYS ON THE PHONE", style = MaterialTheme.typography.labelSmall, color = Fog)
            ProofLine(
                icon = Icons.Rounded.WifiOff,
                text = if (online) {
                    "This build has the internet permission, so Android would let it connect."
                } else {
                    "DeskLess has no internet permission. Android will not let it open a connection, so nothing can be uploaded."
                },
                good = !online,
            )
            ProofLine(
                icon = Icons.Rounded.CloudOff,
                text = "Speech and pictures are worked on only in this app's own storage. A report leaves the phone when you save or share it, and when it is sent to your company's server.",
                good = true,
            )
            if (seconds != null) {
                ProofLine(
                    icon = Icons.Rounded.Bolt,
                    text = "On this walk the model took %.1f s per issue on average".format(seconds) +
                        (state.gemmaBackend?.let { ", on this phone's $it." } ?: "."),
                    good = true,
                )
            }
        }
    }
}

@Composable
private fun ProofLine(icon: ImageVector, text: String, good: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (good) GoGreen else SeverityMedium, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Chalk, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ModelCard(icon: ImageVector, name: String, detail: String, ready: Boolean) {
    val tone by animateColorAsState(if (ready) GoGreen else SeverityMedium, label = "model")
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Coal,
        border = BorderStroke(1.dp, tone.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(tone.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (ready) Icons.Rounded.Check else icon, contentDescription = null, tint = tone, modifier = Modifier.size(24.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = Chalk)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Fog)
            }
            Text(
                text = if (ready) "READY" else "MISSING",
                style = MaterialTheme.typography.labelSmall,
                color = tone,
            )
        }
    }
}

@Composable
private fun HowTo(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(SafetyYellow, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = Ink)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Chalk, modifier = Modifier.weight(1f))
    }
}

/** The way in to the app's own measurements on this phone, with the last result in one line. */
@Composable
private fun MeasureCard(state: AppState) {
    val card = state.scorecard
    Surface(
        onClick = { state.screen = Screen.Scorecard },
        shape = RoundedCornerShape(20.dp),
        color = Coal,
        border = BorderStroke(1.dp, SafetyYellow.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(46.dp).background(SafetyYellow.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(24.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Measured on this phone", style = MaterialTheme.typography.titleMedium, color = Chalk)
                Text(
                    text = if (card == null || card.notes == 0) "Twenty fixed notes: how many fields come out right, how fast, and what it costs the battery."
                    else "Last run: ${(card.fieldsRight * 100).toInt()}% of fields right over ${card.notes} notes, %.1f s a note.".format(card.fieldSeconds + (card.speechSeconds ?: 0f)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Fog,
                )
            }
            Text("OPEN", style = MaterialTheme.typography.labelSmall, color = SafetyYellow)
        }
    }
}
