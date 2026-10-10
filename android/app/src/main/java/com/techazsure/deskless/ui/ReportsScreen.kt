package com.techazsure.deskless.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.techazsure.deskless.AppState
import com.techazsure.deskless.Screen
import com.techazsure.deskless.data.Template
import com.techazsure.deskless.report.ReportEntry
import com.techazsure.deskless.ui.theme.Chalk
import com.techazsure.deskless.ui.theme.Coal
import com.techazsure.deskless.report.Verdict
import com.techazsure.deskless.ui.theme.Fog
import com.techazsure.deskless.ui.theme.GoGreen
import com.techazsure.deskless.ui.theme.Ink
import com.techazsure.deskless.ui.theme.Line
import com.techazsure.deskless.ui.theme.SafetyYellow
import com.techazsure.deskless.ui.theme.SeverityHigh
import com.techazsure.deskless.ui.theme.SeverityMedium
import com.techazsure.deskless.ui.theme.Steel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ReportSort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    SERIOUS("Most serious first"),
    PLACE("By place, A to Z"),
}

/** Every report built on this phone: filter by what kind it is, sort, and open one. */
@Composable
fun ReportsScreen(state: AppState) {
    // -1 shows every kind; otherwise the position of a Template.
    var kind by rememberSaveable { mutableStateOf(-1) }
    var sort by remember { mutableStateOf(ReportSort.NEWEST) }
    var sortOpen by remember { mutableStateOf(false) }
    BackHandler { state.screen = Screen.Home }

    val chosen = Template.entries.getOrNull(kind)
    val shown = state.reports
        .filter { chosen == null || it.template == chosen }
        .let { list ->
            when (sort) {
                ReportSort.NEWEST -> list.sortedByDescending { it.since }
                ReportSort.OLDEST -> list.sortedBy { it.since }
                ReportSort.SERIOUS -> list.sortedWith(compareByDescending<ReportEntry> { it.serious }.thenByDescending { it.since })
                ReportSort.PLACE -> list.sortedBy { it.place.lowercase(Locale.ENGLISH) }
            }
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
            Text("REPORTS", style = MaterialTheme.typography.headlineSmall, color = Chalk)
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
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(16.dp))
                        Text(sort.label, style = MaterialTheme.typography.labelMedium, color = Chalk)
                    }
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    ReportSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                sort = option
                                sortOpen = false
                            },
                            trailingIcon = {
                                if (option == sort) Icon(Icons.Rounded.Check, contentDescription = null, tint = SafetyYellow)
                            },
                        )
                    }
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Pill("All", selected = kind == -1, count = state.reports.size, onClick = { kind = -1 }) }
            items(Template.entries.filter { template -> state.reports.any { it.template == template } }) { template ->
                Pill(
                    text = template.title,
                    selected = kind == template.ordinal,
                    count = state.reports.count { it.template == template },
                    onClick = { kind = template.ordinal },
                )
            }
        }

        if (shown.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("NO REPORTS YET", style = MaterialTheme.typography.headlineSmall, color = Chalk)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Reports you build are kept here on the phone, so you can open or send one again later.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Fog,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.fileName }) { entry ->
                    Box(Modifier.animateItem()) { ReportCard(state, entry) }
                }
            }
        }
    }
}

@Composable
private fun ReportCard(state: AppState, entry: ReportEntry) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = { state.screen = Screen.Report(state.reportFile(entry).absolutePath, fromLibrary = true) },
        interactionSource = interaction,
        shape = RoundedCornerShape(20.dp),
        color = Coal,
        border = BorderStroke(1.dp, Line.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .bouncy(interaction, pressedScale = 0.97f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(SafetyYellow, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(templateIcon(entry.template), contentDescription = null, tint = Ink, modifier = Modifier.size(28.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = (entry.template?.title ?: "Report").uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = SafetyYellow,
                    )
                    // A report from another phone that nobody has opened here yet.
                    if (entry.unread) {
                        Text(
                            text = "NEW",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink,
                            modifier = Modifier
                                .background(GoGreen, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    text = entry.place.ifBlank { "No place given" },
                    style = MaterialTheme.typography.titleMedium,
                    color = Chalk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH).format(Date(entry.builtAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Fog,
                )
                // Counts are known only for reports built since the library was added.
                if (entry.template != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = if (entry.issues == 1) "1 issue" else "${entry.issues} issues",
                            style = MaterialTheme.typography.bodySmall,
                            color = Chalk,
                        )
                        if (entry.serious > 0) {
                            Text("${entry.serious} high or critical", style = MaterialTheme.typography.bodySmall, color = SeverityHigh)
                        }
                        if (entry.flagged > 0) {
                            Text("${entry.flagged} flagged", style = MaterialTheme.typography.bodySmall, color = SeverityMedium)
                        }
                    }
                }
                // Whether the office has it yet. Only reports built on this phone are sent from it.
                val atOffice = when (entry.fileName) {
                    in state.sentToOffice -> true
                    in state.waitingForOffice -> false
                    else -> null
                }
                if (atOffice != null) {
                    Text(
                        text = if (atOffice) "Sent to the office" else "Waiting to be sent to the office",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (atOffice) GoGreen else SafetyYellow,
                    )
                }
                // What the AI review decided, and where the report came from if not from this phone.
                val reviewed = Verdict.entries.firstOrNull { it.name == entry.review }
                if (reviewed != null || entry.from.isNotBlank()) {
                    Text(
                        text = listOfNotNull(
                            reviewed?.let { "AI review: ${it.decision.lowercase()}" },
                            entry.from.takeIf { it.isNotBlank() }?.let { "from $it" },
                        ).joinToString(" · ").replaceFirstChar(Char::uppercase),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (reviewed) {
                            Verdict.GENUINE -> GoGreen
                            Verdict.FAKE -> SeverityHigh
                            Verdict.DOUBTFUL -> SeverityMedium
                            null -> Fog
                        },
                    )
                }
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Fog, modifier = Modifier.size(22.dp))
        }
    }
}
