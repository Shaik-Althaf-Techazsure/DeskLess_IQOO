package com.techazsure.deskless.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.techazsure.deskless.AppState
import com.techazsure.deskless.Screen
import com.techazsure.deskless.UserSide
import com.techazsure.deskless.account.Place
import com.techazsure.deskless.account.Sent
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.structure.Ask
import com.techazsure.deskless.structure.UserAsks
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
import kotlinx.coroutines.launch
import java.io.File

/**
 * The first screen of someone who uses a place. They choose the place, tap the microphone and say what
 * they noticed or want to know. The phone words it, reads it back and listens: "make it high", "it is
 * by the lift on the third floor", "send it". Below is everything they have sent, with where each stands
 * and what the place has answered; an answer is also said aloud when it comes.
 */
@Composable
fun UserHome(state: AppState) {
    val context = LocalContext.current
    val user = state.user
    val me = state.profile ?: return
    var typed by rememberSaveable { mutableStateOf("") }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> if (allowed) user.tell() }
    fun microphone(then: () -> Unit) {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (allowed) then() else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }

    // A picture for what is being told: taken now with the phone's camera, or chosen from its pictures.
    val shot = remember { File(context.cacheDir, "signup/user-shot.jpg").apply { parentFile?.mkdirs() } }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> if (taken) user.addPhoto(shot) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) {
            val copied = runCatching { context.contentResolver.openInputStream(picked)!!.use { input -> shot.outputStream().use { input.copyTo(it) } } }.isSuccess
            if (copied) user.addPhoto(shot)
        }
    }
    fun openCamera() {
        runCatching { take.launch(FileProvider.getUriForFile(context, context.packageName + ".fileprovider", shot)) }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> if (allowed) openCamera() }
    fun camera() {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (allowed) openCamera() else askCamera.launch(Manifest.permission.CAMERA)
    }
    // "Add a photo", said aloud, opens the camera.
    LaunchedEffect(user.wantsCamera) {
        if (user.wantsCamera) {
            user.wantsCamera = false
            camera()
        }
    }
    LaunchedEffect(me.uniqueId) { user.comeOnline() }

    // The side bar: this phone's QR code and the phones it is linked with. Through a linked phone, what
    // is told here reaches the place when this phone has no network.
    val scope = rememberCoroutineScope()
    val sideBar = rememberDrawerState(DrawerValue.Closed)
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
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Opens the side bar. A green dot says a linked phone is in reach right now.
            Box {
                RoundIconButton(
                    icon = Icons.Rounded.QrCode2,
                    label = "Link phones: this phone's code",
                    onClick = { scope.launch { sideBar.open() } },
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
            // With the language and three buttons beside it there is no room for the name.
            Spacer(Modifier.weight(1f))
            LanguageChip(state)
            RoundIconButton(
                icon = if (user.speaks) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                label = if (user.speaks) "The phone speaks and listens by itself. Tap to stop that." else "The phone is silent. Tap to let it speak.",
                onClick = { user.letSpeak(!user.speaks) },
                container = Steel,
                content = if (user.speaks) SafetyYellow else Fog,
                size = 38.dp,
            )
            RoundIconButton(icon = Icons.Rounded.AutoAwesome, label = "The AI on this phone", onClick = { state.screen = Screen.Models }, container = Steel, size = 38.dp)
            RoundIconButton(icon = Icons.AutoMirrored.Rounded.Logout, label = "Sign out", onClick = { leaving = true }, container = Steel, size = 38.dp)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AccountSwitcher(state)
            Text("Hello, ${me.name.substringBefore(' ')}", style = MaterialTheme.typography.headlineMedium, color = Chalk)
            Text(
                text = "Tell the people who look after a place what you noticed, or ask them something. Say it in your own words: " +
                    "it is put into a short entry on this phone, read back to you, and sent only when you say so.",
                style = MaterialTheme.typography.bodyMedium,
                color = Fog,
            )

            PlaceCard(user, choosing || user.place == null, onChoose = { choosing = true }, onChosen = { choosing = false })

            if (!state.speechReady || !state.gemmaReady) {
                Surface(
                    onClick = { state.screen = Screen.Models },
                    shape = RoundedCornerShape(16.dp),
                    color = Coal,
                    border = BorderStroke(1.dp, SeverityMedium.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = (if (!state.speechReady) "The model that listens is not on this phone yet, so type what you want to say. " else "") +
                            (if (!state.gemmaReady) "The model that words it is not on this phone yet, so it goes in your own words. " else "") +
                            "Tap to see them arrive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Chalk,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            Talk(state, user, onMicrophone = { microphone { user.tell() } })

            user.trouble?.let { words ->
                Text(
                    text = words,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Chalk,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SignalRed.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                )
            }

            user.draft?.let { draft ->
                DraftCard(
                    state, user, draft,
                    onCamera = { camera() },
                    onChoose = { choose.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
            }

            if (user.news.isNotBlank()) {
                Surface(shape = RoundedCornerShape(16.dp), color = Coal, border = BorderStroke(1.dp, GoGreen.copy(alpha = 0.55f)), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("JUST IN", style = MaterialTheme.typography.labelSmall, color = GoGreen)
                        Text(user.news, style = MaterialTheme.typography.bodyLarge, color = Chalk)
                    }
                }
            }

            val mine = user.waiting.filter { it.owner == me.uniqueId }
            if (mine.isNotEmpty()) {
                Text("WAITING FOR A NETWORK", style = MaterialTheme.typography.labelSmall, color = Fog)
                mine.forEach { one ->
                    Surface(shape = RoundedCornerShape(14.dp), color = Steel, border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(one.ask.title, style = MaterialTheme.typography.titleMedium, color = Chalk)
                            // Where it is: still here, or with a linked phone that sends it on.
                            val handed = user.handedLine(one.clientId)
                            Text(
                                text = "For ${one.place.name}. " + (handed ?: "It goes by itself when the phone has a network, or through a linked phone in reach."),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (handed != null) GoGreen else Fog,
                            )
                        }
                    }
                }
            }

            // A report a phone of the place handed to this one over the link, with no network.
            val handedOver = state.reports.filter { it.from.isNotBlank() }.take(4)
            if (handedOver.isNotEmpty()) {
                Text("REPORTS HANDED TO THIS PHONE", style = MaterialTheme.typography.labelSmall, color = Fog)
                handedOver.forEach { report ->
                    Surface(
                        onClick = { state.screen = Screen.Report(state.reportFile(report).absolutePath, fromLibrary = true) },
                        shape = RoundedCornerShape(14.dp),
                        color = Steel,
                        border = BorderStroke(1.dp, if (report.unread) GoGreen.copy(alpha = 0.6f) else Line),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(report.place.ifBlank { "A report" }, style = MaterialTheme.typography.titleMedium, color = Chalk)
                            Text(
                                text = "From ${report.from}. ${report.issues} issue${if (report.issues == 1) "" else "s"}." + if (report.unread) " Not opened yet." else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (report.unread) GoGreen else Fog,
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WHAT YOU SENT", style = MaterialTheme.typography.labelSmall, color = Fog, modifier = Modifier.weight(1f))
                RoundIconButton(icon = Icons.Rounded.Refresh, label = "Look again", onClick = { user.refresh() }, container = Steel, size = 32.dp)
            }
            if (user.sent.isEmpty()) {
                Text("Nothing yet. What you send appears here, with each step until it is put right or answered.", style = MaterialTheme.typography.bodyMedium, color = Fog)
            }
            user.sent.forEach { one -> SentCard(user, one, onMicrophone = { microphone { user.sayMoreAloud(one.id) } }) }
            Spacer(Modifier.height(6.dp))
        }

        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            fun send() {
                if (typed.isBlank()) return
                user.type(typed)
                typed = ""
            }
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.take(600) },
                placeholder = { Text(if (user.draft == null) "Or type it" else "Type a change, or \"send it\"", color = Fog) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = fieldColors(),
                modifier = Modifier.weight(1f),
            )
            RoundIconButton(icon = Icons.AutoMirrored.Rounded.Send, label = "Take it", onClick = { send() }, container = SafetyYellow, content = Ink, size = 52.dp)
        }
    }
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Sign out?") },
            text = { Text("What you sent stays with the place. Anything still waiting for a network stays on this phone and goes when you sign in again.") },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    state.signOut()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Stay") } },
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Chalk, unfocusedTextColor = Chalk, cursorColor = SafetyYellow,
    focusedBorderColor = SafetyYellow, unfocusedBorderColor = Line,
    focusedContainerColor = Steel, unfocusedContainerColor = Steel,
)

/** Where things are sent: the place chosen, or the kinds of place and the places of each to choose from. */
@Composable
private fun PlaceCard(user: UserSide, open: Boolean, onChoose: () -> Unit, onChosen: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(user.place?.kind.orEmpty()) }
    Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.dp, Line.copy(alpha = 0.7f)), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val chosen = user.place
            if (!open && chosen != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("IT GOES TO", style = MaterialTheme.typography.labelSmall, color = Fog)
                        Text(chosen.name, style = MaterialTheme.typography.titleLarge, color = Chalk)
                        Text(chosen.kindName, style = MaterialTheme.typography.bodySmall, color = SafetyYellow)
                    }
                    BrandButton(text = "Change", onClick = { onChoose(); user.refreshPlaces() }, kind = ButtonKind.Secondary, height = 42.dp)
                }
                return@Column
            }
            Text("WHICH KIND OF PLACE?", style = MaterialTheme.typography.labelSmall, color = Fog)
            val kinds = user.kinds.filter { it.places > 0 }
            if (kinds.isEmpty()) {
                Text(
                    text = user.placesTrouble ?: "No place takes what its users send in yet. A place appears here once its managing director opens it to the people who use it, on the DeskLess dashboard.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Chalk,
                )
                BrandButton(text = "Look again", onClick = { user.refreshPlaces() }, icon = Icons.Rounded.Refresh, kind = ButtonKind.Secondary, height = 44.dp)
                return@Column
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEach { one -> Pill(text = one.name, selected = kind == one.key, onClick = { kind = one.key }, count = one.places) }
            }
            val listed: List<Place> = user.places.filter { it.kind == kind }
            if (kind.isBlank() || listed.isEmpty()) {
                Text("Choose the kind of place, then the place.", style = MaterialTheme.typography.bodySmall, color = Fog)
            } else {
                Text("WHICH ONE?", style = MaterialTheme.typography.labelSmall, color = Fog)
                listed.forEach { one ->
                    val mine = one.id == chosen?.id
                    Surface(
                        onClick = {
                            user.choose(one)
                            onChosen()
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = if (mine) SafetyYellow else Steel,
                        border = if (mine) null else BorderStroke(1.dp, Line),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(one.name, style = MaterialTheme.typography.titleMedium, color = if (mine) Ink else Chalk, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                    }
                }
            }
            if (chosen != null) TextButton(onClick = onChosen) { Text("Keep ${chosen.name}", color = Fog) }
        }
    }
}

/** The microphone, and in words what the phone is doing with it. */
@Composable
private fun Talk(state: AppState, user: UserSide, onMicrophone: () -> Unit) {
    val phase = user.phase
    val listening = phase == UserSide.Phase.LISTENING && user.replyingTo == null
    val working = phase == UserSide.Phase.HEARING || phase == UserSide.Phase.WORDING || phase == UserSide.Phase.SENDING
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // While listening the button swells with the voice, so the person sees they are heard.
        val swell = if (listening) 1f + (user.level * 0.35f).coerceAtMost(0.2f) else if (phase == UserSide.Phase.SPEAKING) pulse(0.96f, 1.04f, 700) else 1f
        RoundIconButton(
            icon = when {
                listening -> Icons.Rounded.Stop
                phase == UserSide.Phase.SPEAKING -> Icons.AutoMirrored.Rounded.VolumeUp
                working -> Icons.Rounded.HourglassTop
                else -> Icons.Rounded.Mic
            },
            label = if (listening) "I have finished" else "Speak",
            onClick = onMicrophone,
            container = when {
                listening -> SignalRed
                phase == UserSide.Phase.SPEAKING -> Steel
                else -> SafetyYellow
            },
            content = if (phase == UserSide.Phase.SPEAKING) SafetyYellow else Ink,
            size = 112.dp,
            modifier = Modifier
                .padding(top = 4.dp)
                .graphicsLayer {
                    scaleX = swell
                    scaleY = swell
                    alpha = if (working) 0.4f else 1f
                },
        )
        Text(
            text = when (phase) {
                UserSide.Phase.LISTENING -> if (user.replyingTo != null) "Listening for your answer…" else "Listening… it stops when you do. Tap if it does not."
                UserSide.Phase.HEARING -> "Writing down what you said, on this phone…"
                UserSide.Phase.WORDING -> "Putting it into words, on this phone…"
                UserSide.Phase.SPEAKING -> "Reading it back. Then say \"send it\", or what to change."
                UserSide.Phase.SENDING -> "Sending…"
                UserSide.Phase.IDLE -> when {
                    user.place == null -> "Choose the place above, then tap and say it."
                    user.draft != null -> "Tap to change it by voice, or press Send."
                    !state.speechReady -> "Type it below."
                    else -> "Tap and say it: ${state.spoken.hint}."
                }
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (listening) SignalRed else Chalk,
            textAlign = TextAlign.Center,
        )
    }
}

/** The entry as it stands: what it is, in whose words, and the buttons that send or drop it. */
@Composable
private fun DraftCard(state: AppState, user: UserSide, draft: Ask, onCamera: () -> Unit, onChoose: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Coal, border = BorderStroke(1.5.dp, SafetyYellow.copy(alpha = 0.6f)), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("READY TO SEND TO ${(user.place?.name ?: "the place").uppercase()}", style = MaterialTheme.typography.labelSmall, color = SafetyYellow)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UserAsks.KINDS.forEach { (key, label) ->
                    Pill(text = label, selected = draft.kind == key, onClick = {
                        user.edit { it.copy(kind = key, severity = if (key == "enquiry") Severity.LOW else it.severity, category = if (key == "enquiry") "" else it.category.ifBlank { "Other" }) }
                    })
                }
            }
            Text(draft.title, style = MaterialTheme.typography.titleLarge, color = Chalk)
            if (draft.summary.isNotBlank() && draft.summary != draft.title) Text(draft.summary, style = MaterialTheme.typography.bodyLarge, color = Chalk)
            if (draft.location.isNotBlank()) Text("Where: ${draft.location}", style = MaterialTheme.typography.bodyMedium, color = Fog)
            if (draft.kind != "enquiry") {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Severity.entries.forEach { level ->
                        Pill(text = level.label, selected = draft.severity == level, onClick = { user.edit { it.copy(severity = level) } }, dot = severityColor(level))
                    }
                }
                if (draft.category.isNotBlank()) Text("Sorted under: ${draft.category}", style = MaterialTheme.typography.bodySmall, color = Fog)
            }

            if (user.photoVersion > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Keyed on the version, so a picture taken again is shown anew.
                    androidx.compose.runtime.key(user.photoVersion) {
                        Photo(user.photo, maxEdge = 320, modifier = Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)))
                    }
                    BrandButton(text = "Remove", onClick = { user.dropPhoto() }, icon = Icons.Rounded.Close, kind = ButtonKind.Secondary, height = 42.dp)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrandButton(text = "Add a photo", onClick = onCamera, icon = Icons.Rounded.CameraAlt, kind = ButtonKind.Secondary, height = 44.dp)
                    BrandButton(text = "Choose", onClick = onChoose, icon = Icons.Rounded.Image, kind = ButtonKind.Secondary, height = 44.dp)
                }
            }

            if (user.heard.isNotBlank()) {
                Text("YOU SAID", style = MaterialTheme.typography.labelSmall, color = Fog)
                Text(user.heard, style = MaterialTheme.typography.bodyMedium, color = Fog)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandButton(
                    text = if (user.phase == UserSide.Phase.SENDING) "Sending…" else "Send",
                    onClick = { user.sendNow() },
                    icon = Icons.AutoMirrored.Rounded.Send,
                    enabled = user.phase != UserSide.Phase.SENDING && user.phase != UserSide.Phase.WORDING,
                    height = 50.dp,
                    modifier = Modifier.weight(1f),
                )
                BrandButton(text = "Drop it", onClick = { user.discard() }, kind = ButtonKind.Secondary, height = 50.dp, modifier = Modifier.weight(1f))
            }
            Text(
                text = (if (user.seconds > 0f) "Worded in %.1f s on this phone".format(user.seconds) + (state.gemmaBackend?.let { "'s $it" } ?: "") + ". " else "") +
                    "Nothing has been sent yet. Change anything by saying it, typing it or tapping it.",
                style = MaterialTheme.typography.bodySmall,
                color = Fog,
            )
        }
    }
}

/** One thing sent in: where it stands, what was said about it, and a way to add a word. */
@Composable
private fun SentCard(user: UserSide, one: Sent, onMicrophone: () -> Unit) {
    var open by rememberSaveable(one.id) { mutableStateOf(false) }
    var word by rememberSaveable(one.id) { mutableStateOf("") }
    val closed = one.status == "closed"
    Surface(
        onClick = { open = !open },
        shape = RoundedCornerShape(18.dp),
        color = Coal,
        border = BorderStroke(1.dp, if (closed) GoGreen.copy(alpha = 0.5f) else Line.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = listOfNotNull(one.place, UserAsks.KINDS[one.kind], one.location.takeIf { it.isNotBlank() }).joinToString(" · ").uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Fog,
            )
            Text(one.title, style = MaterialTheme.typography.titleMedium, color = Chalk)
            Text(one.standing, style = MaterialTheme.typography.bodyMedium, color = if (closed) GoGreen else SafetyYellow)
            // What was said since it was sent: the last of it always, all of it when the card is open.
            val said = one.thread.drop(1)
            (if (open) said else said.takeLast(1)).forEach { line ->
                Text(
                    text = (if (line.mine) "You" else listOf(line.from, line.fromRole).filter { it.isNotBlank() }.joinToString(", ")) + ": " + line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (line.mine) Fog else Chalk,
                )
            }
            if (open) {
                if (one.note.isNotBlank() && one.note != one.title) Text(one.note, style = MaterialTheme.typography.bodySmall, color = Fog)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = word,
                        onValueChange = { word = it.take(400) },
                        placeholder = { Text("Add a word", color = Fog) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    val hearing = user.replyingTo == one.id
                    RoundIconButton(
                        icon = if (hearing) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        label = "Say it",
                        onClick = onMicrophone,
                        container = if (hearing) SignalRed else Steel,
                        size = 46.dp,
                    )
                    RoundIconButton(
                        icon = Icons.AutoMirrored.Rounded.Send,
                        label = "Send the word",
                        onClick = {
                            if (word.isNotBlank()) {
                                user.sayMore(one.id, word)
                                word = ""
                            }
                        },
                        container = SafetyYellow,
                        content = Ink,
                        size = 46.dp,
                    )
                }
            } else if (said.size > 1) {
                Text("${said.size} messages. Tap to read them and to answer.", style = MaterialTheme.typography.bodySmall, color = Fog)
            }
        }
    }
}
