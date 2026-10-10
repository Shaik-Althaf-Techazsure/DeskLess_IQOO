package com.techazsure.deskless.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.techazsure.deskless.AppState
import com.techazsure.deskless.AppState.AskPhase
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.speech.Spoken
import com.techazsure.deskless.structure.Source
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

/** The colour a source is shown in: green for what was said or seen, amber for the model's own call. */
fun sourceColor(source: Source): Color = when (source) {
    Source.HEARD, Source.SEEN, Source.KNOWN, Source.ASKED, Source.TOLD -> GoGreen
    Source.PHONE -> SafetyYellow
    Source.JUDGED -> SeverityMedium
    Source.TYPED -> Fog
}

/** The small tag beside a field's name saying where its value came from. */
@Composable
fun SourceTag(source: Source, modifier: Modifier = Modifier) {
    val tone = sourceColor(source)
    Text(
        text = source.tag,
        style = MaterialTheme.typography.labelSmall,
        color = tone,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, tone.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * The language notes are spoken in, as a chip that opens the choice. Whatever is chosen, the note is
 * written down in English.
 */
@Composable
fun LanguageChip(state: AppState, modifier: Modifier = Modifier) {
    var choosing by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = { choosing = true },
        interactionSource = interaction,
        shape = RoundedCornerShape(50),
        color = Steel,
        border = BorderStroke(1.dp, Line),
        modifier = modifier.bouncy(interaction),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Translate, contentDescription = "Language spoken", tint = SafetyYellow, modifier = Modifier.size(14.dp))
            Text(state.spoken.label, style = MaterialTheme.typography.labelMedium, color = Chalk, maxLines = 1)
        }
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Which language will you speak?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "The note is written in English whichever you choose. Translation happens on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spoken.entries.forEach { choice ->
                        val chosen = choice == state.spoken
                        Surface(
                            onClick = {
                                state.speakIn(choice)
                                choosing = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (chosen) SafetyYellow else Color.Transparent,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(choice.label, style = MaterialTheme.typography.titleMedium, color = if (chosen) Ink else Color.Unspecified, modifier = Modifier.weight(1f))
                                Text(choice.own, style = MaterialTheme.typography.bodyMedium, color = if (chosen) Ink else Color.Unspecified)
                            }
                        }
                    }
                    Text(
                        "A note already recorded is heard again in the new language with Redo the text.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } },
        )
    }
}

/**
 * The phone asking back. When a note leaves out where the problem is, who should deal with it or by
 * when, DeskLess asks one question aloud, listens, and fills those fields from the answer. The card
 * shows the question, the listening and what was filled; it can also be started by hand.
 */
@Composable
fun AskBackCard(state: AppState, issue: Issue) {
    val asking = state.askBack?.takeIf { it.id == issue.id }
    val gaps = state.gapsOf(issue)
    val busy = issue.id in state.transcribing || issue.id in state.structuring
    if (asking == null && (gaps.isEmpty() || issue.note.isBlank() || busy)) return

    val live = asking != null && asking.phase != AskPhase.DONE
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Coal,
        border = BorderStroke(1.dp, if (live) SafetyYellow.copy(alpha = 0.7f) else Line.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(16.dp))
                Text("DESKLESS ASKS", style = MaterialTheme.typography.labelSmall, color = Fog, modifier = Modifier.weight(1f))
                // Whether the phone speaks up by itself, or only when told to.
                Surface(
                    onClick = { state.letAskBack(!state.asksBack) },
                    shape = RoundedCornerShape(50),
                    color = Steel,
                    border = BorderStroke(1.dp, Line),
                ) {
                    Text(
                        text = if (state.asksBack) "Asks by itself: on" else "Asks by itself: off",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.asksBack) GoGreen else Fog,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            when (asking?.phase) {
                null -> {
                    Text(
                        text = "Your note does not say " + gaps.joinToString(", ") { it.word }.replaceLast(", ", " or ") + ".",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Chalk,
                    )
                    BrandButton(
                        text = "Ask me",
                        onClick = { state.askBack(issue.id) },
                        icon = Icons.Rounded.Mic,
                        kind = ButtonKind.Secondary,
                        enabled = state.speechReady,
                        height = 46.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!state.speechReady) {
                        Text("Speech is not on this phone yet: type them in below.", style = MaterialTheme.typography.bodySmall, color = Fog)
                    }
                }
                AskPhase.SPEAKING -> {
                    Text("“${asking.question}”", style = MaterialTheme.typography.titleMedium, color = Chalk)
                    val beat = pulse(0.45f, 1f, 650)
                    Text("Asking…", style = MaterialTheme.typography.bodyMedium, color = SafetyYellow, modifier = Modifier.graphicsLayer { alpha = beat })
                    AskActions(second = "Skip", onSecond = { state.cancelAskBack() })
                }
                AskPhase.LISTENING -> {
                    Text("“${asking.question}”", style = MaterialTheme.typography.titleMedium, color = Chalk)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Steel)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((asking.level * 3f).coerceIn(0.03f, 1f))
                                .height(8.dp)
                                .background(SignalRed)
                        )
                    }
                    Text("Listening. Answer in a few words; it stops when you do.", style = MaterialTheme.typography.bodyMedium, color = SignalRed)
                    AskActions(first = "Done", onFirst = { state.endAnswer() }, second = "Skip", onSecond = { state.cancelAskBack() })
                }
                AskPhase.THINKING -> {
                    val beat = pulse(0.45f, 1f, 650)
                    Text(
                        "Filling the fields from your answer, on this phone…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SafetyYellow,
                        modifier = Modifier.graphicsLayer { alpha = beat },
                    )
                }
                AskPhase.UNHEARD -> {
                    Text("No answer was heard.", style = MaterialTheme.typography.bodyLarge, color = Chalk)
                    AskActions(first = "Ask again", onFirst = { state.askBack(issue.id) }, second = "Close", onSecond = { state.closeAskBack() })
                }
                AskPhase.DONE -> {
                    Text("You answered: “${asking.heard}”", style = MaterialTheme.typography.bodyLarge, color = Chalk)
                    Text(
                        text = if (asking.filled.isEmpty()) "Nothing in that for what was asked, so nothing was filled." else "Filled from it: ${asking.filled.joinToString(", ")}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (asking.filled.isEmpty()) Fog else GoGreen,
                    )
                    AskActions(second = "Close", onSecond = { state.closeAskBack() })
                }
            }
        }
    }
}

private fun String.replaceLast(old: String, new: String): String =
    lastIndexOf(old).let { at -> if (at < 0) this else substring(0, at) + new + substring(at + old.length) }

@Composable
private fun AskActions(first: String? = null, onFirst: () -> Unit = {}, second: String, onSecond: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (first != null) {
            BrandButton(text = first, onClick = onFirst, icon = Icons.Rounded.Check, height = 44.dp, modifier = Modifier.weight(1f))
        }
        BrandButton(text = second, onClick = onSecond, kind = ButtonKind.Secondary, height = 44.dp, modifier = Modifier.weight(1f))
    }
}

/**
 * A value the model offered for a field and the guard kept out, because nobody said it. One tap takes it
 * after all, another puts it away.
 */
@Composable
fun HeldLine(what: String, value: String, onUse: () -> Unit, onDrop: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SeverityMedium.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Kept out: the AI offered “$value” as the $what, but it is not in your note.",
            style = MaterialTheme.typography.bodySmall,
            color = Chalk,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUse) { Text("Use it", color = SafetyYellow) }
        RoundIconButton(Icons.Rounded.Close, "Put it away", onClick = onDrop, container = Color.Transparent, content = Fog, size = 32.dp)
    }
}

/** Names this phone has met, offered for an empty field: one tap writes the name in. */
@Composable
fun KnownChips(label: String, names: List<String>, onPick: (String) -> Unit) {
    if (names.isEmpty()) return
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Psychology, contentDescription = null, tint = Fog, modifier = Modifier.size(15.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Fog)
        names.forEach { name ->
            Surface(onClick = { onPick(name) }, shape = RoundedCornerShape(50), color = Steel, border = BorderStroke(1.dp, Line)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    color = Chalk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/**
 * What the phone has learned from use: who gets given work and what the places are called. A tap on a
 * name forgets it.
 */
@Composable
fun MemoryCard(state: AppState) {
    // Read so the card draws again when the memory changes.
    state.memoryChanged
    val people = state.memory.people()
    val places = state.memory.allPlaces()
    Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.dp, Line.copy(alpha = 0.6f)), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Psychology, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(16.dp))
                Text("WHAT THIS PHONE HAS LEARNED", style = MaterialTheme.typography.labelSmall, color = Fog)
            }
            if (people.isEmpty() && places.isEmpty()) {
                Text(
                    "Nothing yet. As you file issues, DeskLess learns who gets given work and what the places are called, " +
                        "and spells a name the way you last corrected it. It stays on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Chalk,
                )
            } else {
                Text(
                    "Learned from the fields as you left them. A name heard a little wrong is spelt the way it stands here. Tap one to forget it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Chalk,
                )
                if (people.isNotEmpty()) {
                    Text("PEOPLE AND TEAMS", style = MaterialTheme.typography.labelSmall, color = Fog)
                    Forgettable(people.map { it to it }) { state.forgetPerson(it) }
                }
                if (places.isNotEmpty()) {
                    Text("PLACES", style = MaterialTheme.typography.labelSmall, color = Fog)
                    Forgettable(places.map { known -> known to (known.name + if (known.site.isBlank()) "" else " · ${known.site}") }) {
                        state.forgetPlace(it.name, it.site)
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> Forgettable(items: List<Pair<T, String>>, onForget: (T) -> Unit) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (item, words) ->
            Surface(onClick = { onForget(item) }, shape = RoundedCornerShape(50), color = Steel, border = BorderStroke(1.dp, Line)) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(words, style = MaterialTheme.typography.labelMedium, color = Chalk, maxLines = 1)
                    Box(Modifier.size(16.dp).background(Line, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, contentDescription = "Forget", tint = Fog, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
    }
}

/**
 * The people signed in on this phone, as chips: the one in use first, then the others, then a way to add
 * one. A phone shared on a site is handed from a manager to a technician and back; a tap changes whose
 * phone it is, with no password, and their desk is fetched and read out.
 */
@Composable
fun AccountSwitcher(state: AppState, modifier: Modifier = Modifier) {
    val me = state.profile ?: return
    Row(modifier = modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(text = "${me.name.substringBefore(' ')} \u00B7 ${me.roleName}", selected = true, onClick = {})
        state.otherAccounts.forEach { other ->
            Pill(text = "${other.name.substringBefore(' ')} \u00B7 ${other.roleName}", selected = false, onClick = { state.switchAccount(other.uniqueId) })
        }
        Pill(text = "+ Another person", selected = false, onClick = { state.addingAccount = true })
    }
}
