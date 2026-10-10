package com.techazsure.deskless.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.techazsure.deskless.AppState
import com.techazsure.deskless.account.Checks
import com.techazsure.deskless.account.Profile as Person
import com.techazsure.deskless.account.Unlock
import com.techazsure.deskless.capture.normalizeJpeg
import com.techazsure.deskless.ui.theme.Chalk
import com.techazsure.deskless.ui.theme.Coal
import com.techazsure.deskless.ui.theme.Fog
import com.techazsure.deskless.ui.theme.GoGreen
import com.techazsure.deskless.ui.theme.Ink
import com.techazsure.deskless.ui.theme.Line
import com.techazsure.deskless.ui.theme.SafetyYellow
import com.techazsure.deskless.ui.theme.SignalRed
import com.techazsure.deskless.ui.theme.Steel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private enum class AccountStep { Welcome, SignUp, Code, Waiting, SignIn, Forgot }

/**
 * Everything before the app proper: creating an account, confirming the email address with a code, waiting
 * for the administrator, and signing in with the unique ID. The network is needed for these steps and for
 * nothing after them.
 */
@Composable
fun AccountFlow(state: AppState) {
    // A sign-up that was already sent is picked up where it was left.
    // Someone signing in beside the person in use goes straight to the sign-in form.
    var step by rememberSaveable {
        mutableStateOf(if (state.profile != null) AccountStep.SignIn else if (state.pending != null) AccountStep.Waiting else AccountStep.Welcome)
    }
    var knownId by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = step != AccountStep.Welcome) { step = if (step == AccountStep.Forgot) AccountStep.SignIn else AccountStep.Welcome }
    // Back from the first of these screens returns the phone to whoever was using it.
    BackHandler(enabled = step == AccountStep.Welcome && state.profile != null) { state.addingAccount = false }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "account",
        ) { shown ->
            when (shown) {
                AccountStep.Welcome -> Welcome(state, onSignIn = { step = AccountStep.SignIn }, onSignUp = { step = AccountStep.SignUp })
                AccountStep.SignUp -> SignUp(state, onBack = { step = AccountStep.Welcome }, onSent = { step = AccountStep.Code })
                AccountStep.Code -> Code(state, onBack = { step = AccountStep.Welcome }, onConfirmed = { step = AccountStep.Waiting })
                AccountStep.Waiting -> Waiting(
                    state,
                    onNeedsCode = { step = AccountStep.Code },
                    onSignIn = { id ->
                        knownId = id
                        step = AccountStep.SignIn
                    },
                    onStartAgain = {
                        state.forgetSignUp()
                        step = AccountStep.SignUp
                    },
                    onLeave = { step = AccountStep.Welcome },
                )
                AccountStep.SignIn -> SignIn(state, knownId, onBack = { step = AccountStep.Welcome }, onForgot = { step = AccountStep.Forgot }, onSignUp = { step = AccountStep.SignUp })
                AccountStep.Forgot -> Forgot(state, knownId, onBack = { step = AccountStep.SignIn })
            }
        }
    }
}

/** The frame the account screens share: a back arrow, a heading, and a column that scrolls above the keyboard. */
@Composable
private fun Page(title: String, about: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack, container = Steel)
                Spacer(Modifier.width(14.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Chalk)
        }
        Text(about, style = MaterialTheme.typography.bodyMedium, color = Fog)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

/** What went wrong, said once in red above the button that was pressed. */
@Composable
private fun Problem(text: String?) {
    if (text == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SignalRed.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = SignalRed, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Chalk)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    capitals: KeyboardCapitalization = KeyboardCapitalization.None,
    secret: Boolean = false,
    help: String? = null,
    shown: VisualTransformation = VisualTransformation.None,
    last: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fog)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Chalk),
            cursorBrush = SolidColor(SafetyYellow),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else keyboard,
                capitalization = capitals,
                autoCorrectEnabled = false,
                imeAction = if (last) ImeAction.Done else ImeAction.Next,
            ),
            visualTransformation = if (secret) PasswordVisualTransformation() else shown,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { field ->
                Box(
                    modifier = Modifier
                        .background(Steel, RoundedCornerShape(14.dp))
                        .border(1.5.dp, if (focused) SafetyYellow else Line, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                ) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Fog.copy(alpha = 0.5f))
                    field()
                }
            },
        )
        if (help != null) Text(help, style = MaterialTheme.typography.bodySmall, color = Fog)
    }
}

/** Shows twelve digits as three groups of four, the way an Aadhaar number is printed. */
/**
 * Shows an ID or a code in capitals while leaving what was typed as it is. Rewriting the text on every
 * keystroke instead makes some keyboards lose letters.
 */
private object InCapitals : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(AnnotatedString(text.text.uppercase(Locale.ROOT)), OffsetMapping.Identity)
}

/** Keeps the letters, digits and dashes an ID or a code is made of. All are plain ones, which capitals leave the same length. */
private fun plain(typed: String, length: Int): String =
    typed.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }.take(length)

private object InFours : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val spaced = buildString {
            digits.forEachIndexed { i, digit ->
                if (i == 4 || i == 8) append(' ')
                append(digit)
            }
        }
        val places = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = (offset + if (offset > 8) 2 else if (offset > 4) 1 else 0).coerceAtMost(spaced.length)
            override fun transformedToOriginal(offset: Int) = (offset - if (offset > 9) 2 else if (offset > 4) 1 else 0).coerceIn(0, digits.length)
        }
        return TransformedText(AnnotatedString(spaced), places)
    }
}

@Composable
private fun Welcome(state: AppState, onSignIn: () -> Unit, onSignUp: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        LogoMark(Modifier.size(92.dp))
        Spacer(Modifier.height(18.dp))
        Text(if (state.userOnly) "DeskLess User" else "DeskLess", style = MaterialTheme.typography.displaySmall, color = Chalk)
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (state.userOnly) {
                "Tell the people who look after your building, campus or office what you notice, or ask them something. Say it: the phone puts it into words and sends it."
            } else {
                "Inspection reports from a photo and your voice, made on the phone. For any kind of place."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Fog,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        BrandButton("Sign in", onClick = onSignIn, icon = Icons.AutoMirrored.Rounded.Login, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        BrandButton("Create an account", onClick = onSignUp, icon = Icons.Rounded.PersonAdd, kind = ButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
        state.profile?.let { person ->
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { state.addingAccount = false }) { Text("Back to ${person.name}", color = SafetyYellow) }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = if (state.userOnly) {
                "It is put into words on this phone, with no network. It is sent to the place when the phone has one."
            } else {
                "Reports are made with no network. They are sent to your office when the phone has one."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Fog,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = { editing = true }) { Text("Server: ${state.server}", color = Fog) }
    }
    if (editing) {
        var address by remember { mutableStateOf(state.server) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Account server") },
            text = {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address") },
                    singleLine = true,
                    supportingText = { Text("Where your organisation's DeskLess account server runs, for example https://deskless.example.com") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.useServer(address)
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SignUp(state: AppState, onBack: () -> Unit, onSent: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The edition for people who use a place signs up nobody else.
    var role by rememberSaveable { mutableStateOf(if (state.userOnly) Person.USER else "inspector") }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var organisation by rememberSaveable { mutableStateOf("") }
    // Everyone below a managing director joins with the code of the person they will answer to.
    var invite by rememberSaveable { mutableStateOf("") }
    var joining by remember { mutableStateOf<String?>(null) }
    var aadhaar by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var again by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Someone who uses a place works for nobody in it: no photo, no code to join with, no Aadhaar number.
    val user = role == Person.USER

    // The photo for the account: taken now with the phone's camera, or chosen from its pictures.
    val photo = remember { File(context.filesDir, "signup-photo.jpg") }
    var photoVersion by remember { mutableIntStateOf(if (photo.isFile) 1 else 0) }
    val shot = remember { File(context.cacheDir, "signup/shot.jpg").apply { parentFile?.mkdirs() } }
    fun keep(copy: () -> Unit) {
        scope.launch {
            val kept = withContext(Dispatchers.IO) {
                runCatching {
                    copy()
                    // Upright and of a modest size, as the server wants it.
                    normalizeJpeg(photo, 1000)
                }.isSuccess && photo.length() > 0
            }
            if (kept) photoVersion++ else problem = "That picture could not be used. Try another."
        }
    }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) keep { shot.copyTo(photo, overwrite = true) }
    }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) keep { context.contentResolver.openInputStream(picked)!!.use { input -> photo.outputStream().use { input.copyTo(it) } } }
    }
    fun openCamera() {
        runCatching { take.launch(FileProvider.getUriForFile(context, context.packageName + ".fileprovider", shot)) }
            .onFailure { problem = "No camera app could be opened. Choose a picture instead." }
    }
    // An app that asks for the camera at all must hold it before it may open the phone's camera app.
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) openCamera() else problem = "Allow the camera to take your photo, or choose a picture instead."
    }

    // As soon as a whole code is typed, the server is asked whose it is, so a slip shows before the form is sent.
    LaunchedEffect(invite, role) {
        joining = null
        if (role != "md" && Checks.invite(invite)) {
            delay(400)
            joining = state.whoseCode(Checks.cleanInvite(invite), role).fold({ it }, { it.message })
        }
    }

    fun firstMistake(): String? = when {
        !user && photoVersion == 0 -> "Add a photo of yourself."
        name.trim().length < 2 -> "Enter your full name."
        role == "md" && organisation.trim().length < 2 -> "Enter your company's name."
        !user && role != "md" && !Checks.invite(invite) -> "Enter the 8-character code from the person you report to."
        !Checks.email(email) -> "That email address does not look right."
        user && phone.isNotBlank() && !Checks.mobile(phone) -> "Enter a 10-digit mobile number, or leave it empty."
        !user && !Checks.mobile(phone) -> "Enter a 10-digit mobile number."
        !user && !Checks.aadhaar(aadhaar) -> "That Aadhaar number is not valid. Check the 12 digits."
        password.length < Checks.MIN_PASSWORD -> "Choose a password of at least ${Checks.MIN_PASSWORD} characters."
        password != again -> "The two passwords are not the same."
        else -> null
    }

    val about = if (user) {
        "A name, an email address and a password are all it takes. A code is emailed to confirm the address is yours, and your DeskLess ID follows at once."
    } else {
        "Your details go to the person you report to, who approves the account and so gives you your DeskLess ID."
    }
    Page("CREATE ACCOUNT", about, onBack) {
        if (!state.userOnly) Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Person.ROLES.forEach { (key, label, _) -> Pill(label, selected = role == key, onClick = { role = key }) }
        }
        Text(Person.ROLES.first { it.first == role }.third, style = MaterialTheme.typography.bodySmall, color = Fog)

        if (!user) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Steel)
                    .border(1.5.dp, if (photoVersion > 0) SafetyYellow else Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (photoVersion > 0) {
                    Photo(photo, maxEdge = 256, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Rounded.Person, contentDescription = null, tint = Fog, modifier = Modifier.size(44.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("YOUR PHOTO", style = MaterialTheme.typography.labelSmall, color = Fog)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrandButton(
                        text = "Take",
                        icon = Icons.Rounded.CameraAlt,
                        kind = ButtonKind.Secondary,
                        height = 46.dp,
                        onClick = {
                            val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            if (allowed) openCamera() else askCamera.launch(Manifest.permission.CAMERA)
                        },
                    )
                    BrandButton(
                        text = "Choose",
                        icon = Icons.Rounded.Image,
                        kind = ButtonKind.Secondary,
                        height = 46.dp,
                        onClick = { choose.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    )
                }
            }
        }

        Field("Full name", name, { name = it }, "As on your ID", capitals = KeyboardCapitalization.Words)
        Field("Email", email, { email = it.trim() }, "you@example.com", keyboard = KeyboardType.Email, help = "A code is sent here to confirm it is yours.")
        Field(
            label = "Mobile number",
            value = phone,
            onChange = { typed -> phone = typed.filter { it.isDigit() || it == '+' }.take(13) },
            placeholder = if (user) "10 digits, or leave it empty" else "10 digits",
            keyboard = KeyboardType.Phone,
            help = if (user) "Not needed. A place you write to may ring you back on it." else null,
        )
        if (user) {
            // Nothing to join: the place is chosen each time something is sent.
        } else if (role == "md") {
            Field(
                label = "Company name",
                value = organisation,
                onChange = { organisation = it },
                placeholder = "As it should appear on reports",
                capitals = KeyboardCapitalization.Words,
                help = "The DeskLess administrator approves a new company.",
            )
        } else {
            Field(
                label = if (role == "manager") "Company code" else "Team code",
                value = invite,
                onChange = { invite = plain(it, 9) },
                shown = InCapitals,
                placeholder = "ABCD-EFGH",
                capitals = KeyboardCapitalization.Characters,
                help = joining ?: if (role == "manager") {
                    "Ask your managing director for it. It is on their DeskLess dashboard."
                } else {
                    "Ask your manager for it. It is on their DeskLess dashboard."
                },
            )
        }
        if (!user) Field(
            label = "Aadhaar number",
            value = aadhaar,
            onChange = { typed -> aadhaar = typed.filter(Char::isDigit).take(12) },
            placeholder = "12 digits",
            keyboard = KeyboardType.Number,
            shown = InFours,
            help = "Stored encrypted. Everyone, the administrator included, sees only the last four digits.",
        )
        Field("Password", password, { password = it }, "At least ${Checks.MIN_PASSWORD} characters", secret = true)
        Field("Password again", again, { again = it }, secret = true, last = true)

        Problem(problem)
        BrandButton(
            text = if (busy) "Sending…" else "Create account",
            icon = Icons.Rounded.PersonAdd,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                problem = firstMistake()
                if (problem == null) {
                    busy = true
                    scope.launch {
                        problem = if (user) state.signUpUser(name.trim(), email.trim(), phone, password) else state.signUp(
                            mapOf(
                                "role" to role, "name" to name.trim(), "email" to email.trim(), "phone" to phone,
                                "organisation" to if (role == "md") organisation.trim() else "",
                                "invite" to if (role == "md") "" else Checks.cleanInvite(invite),
                                "aadhaar" to aadhaar, "password" to password,
                            ),
                            photo,
                        )
                        busy = false
                        if (problem == null) {
                            // The form's work is done; the photo now lives on the server.
                            photo.delete()
                            onSent()
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun Code(state: AppState, onBack: () -> Unit, onConfirmed: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // A new code can be asked for a minute after the last one.
    var wait by remember { mutableIntStateOf(60) }
    LaunchedEffect(wait) {
        if (wait > 0) {
            delay(1000)
            wait--
        }
    }

    Page("CHECK YOUR EMAIL", "A six-digit code was sent to ${state.pending?.email ?: "your email address"}. Type it here to confirm the address is yours.", onBack) {
        Icon(Icons.Rounded.MarkEmailRead, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(48.dp))
        BasicTextField(
            value = code,
            onValueChange = { typed -> code = typed.filter(Char::isDigit).take(6) },
            singleLine = true,
            textStyle = MaterialTheme.typography.displaySmall.copy(color = Chalk, letterSpacing = 10.sp, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(SafetyYellow),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .background(Steel, RoundedCornerShape(16.dp))
                .border(1.5.dp, SafetyYellow, RoundedCornerShape(16.dp))
                .padding(vertical = 18.dp),
        )
        Problem(problem)
        BrandButton(
            text = if (busy) "Checking…" else "Confirm",
            icon = Icons.Rounded.Check,
            enabled = !busy && code.length == 6,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                scope.launch {
                    problem = state.verifyCode(code)
                    busy = false
                    if (problem == null) onConfirmed()
                }
            },
        )
        TextButton(
            enabled = wait == 0 && !busy,
            onClick = {
                scope.launch {
                    problem = state.resendCode()
                    if (problem == null) {
                        code = ""
                        wait = 60
                    }
                }
            },
        ) { Text(if (wait > 0) "Send a new code in $wait s" else "Send a new code") }
    }
}

@Composable
private fun Waiting(state: AppState, onNeedsCode: () -> Unit, onSignIn: (String) -> Unit, onStartAgain: () -> Unit, onLeave: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<AppState.SignUpStatus?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    suspend fun check() {
        checking = true
        state.signUpStatus()
            .onSuccess {
                status = it
                problem = null
                if (it.status == "email_pending") onNeedsCode()
            }
            .onFailure { problem = it.message }
        checking = false
    }
    // The answer is looked for again every few seconds until the sign-up has been decided.
    LaunchedEffect(Unit) {
        while (status?.status != "approved" && status?.status != "rejected") {
            check()
            delay(8000)
        }
    }

    val decided = status?.status
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        when (decided) {
            "approved" -> {
                Icon(Icons.Rounded.Verified, contentDescription = null, tint = GoGreen, modifier = Modifier.size(72.dp))
                Text("APPROVED", style = MaterialTheme.typography.headlineMedium, color = GoGreen)
                Text("Your DeskLess ID", style = MaterialTheme.typography.bodyLarge, color = Fog)
                Text(
                    text = status?.uniqueId.orEmpty(),
                    style = MaterialTheme.typography.displaySmall,
                    color = SafetyYellow,
                    modifier = Modifier
                        .background(Coal, RoundedCornerShape(18.dp))
                        .border(1.5.dp, SafetyYellow.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                )
                Text(
                    text = "Sign in with this ID and the password you chose. It has been emailed to you as well.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Fog,
                    textAlign = TextAlign.Center,
                )
                BrandButton("Sign in", onClick = { onSignIn(status?.uniqueId.orEmpty()) }, icon = Icons.AutoMirrored.Rounded.Login, modifier = Modifier.fillMaxWidth())
            }
            "rejected" -> {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = SignalRed, modifier = Modifier.size(72.dp))
                Text("NOT APPROVED", style = MaterialTheme.typography.headlineMedium, color = SignalRed)
                Text(
                    text = status?.reason?.takeIf { it.isNotBlank() }?.let { "The reason given: $it" } ?: "This sign-up was not approved.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Chalk,
                    textAlign = TextAlign.Center,
                )
                BrandButton("Sign up again", onClick = onStartAgain, icon = Icons.Rounded.PersonAdd, modifier = Modifier.fillMaxWidth())
            }
            else -> {
                val beat = pulse(0.45f, 1f, 900)
                Icon(Icons.Rounded.HourglassTop, contentDescription = null, tint = SafetyYellow.copy(alpha = beat), modifier = Modifier.size(72.dp))
                Text("WAITING FOR APPROVAL", style = MaterialTheme.typography.headlineMedium, color = Chalk, textAlign = TextAlign.Center)
                Text(
                    text = "Your email address is confirmed. Your sign-up now waits for " +
                        "${status?.approver?.takeIf { it.isNotBlank() } ?: "approval"}. " +
                        "Your DeskLess ID appears here, and is emailed to ${state.pending?.email ?: "you"}, as soon as it is approved.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Fog,
                    textAlign = TextAlign.Center,
                )
                Problem(problem)
                BrandButton(
                    text = if (checking) "Checking…" else "Check now",
                    kind = ButtonKind.Secondary,
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { scope.launch { check() } },
                )
                // A sign-up the server no longer knows cannot be waited for.
                if (problem != null) TextButton(onClick = onStartAgain) { Text("Start again") }
                TextButton(onClick = onLeave) { Text("Back to the first screen", color = Fog) }
            }
        }
    }
}

@Composable
private fun SignIn(state: AppState, knownId: String, onBack: () -> Unit, onForgot: () -> Unit, onSignUp: () -> Unit) {
    val scope = rememberCoroutineScope()
    var id by rememberSaveable { mutableStateOf(knownId) }
    var password by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Page("SIGN IN", "Use the DeskLess ID you were given when your account was approved.", onBack) {
        Field("DeskLess ID", id, { id = plain(it, 20) }, if (state.userOnly) "DL-USR-0001" else "DL-INS-0001", capitals = KeyboardCapitalization.Characters, shown = InCapitals)
        Field("Password", password, { password = it }, secret = true, last = true)
        Problem(problem)
        BrandButton(
            text = if (busy) "Signing in…" else "Sign in",
            icon = Icons.AutoMirrored.Rounded.Login,
            enabled = !busy && id.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                scope.launch {
                    problem = state.signIn(id, password)
                    busy = false
                }
            },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onForgot) { Text("Forgot password?") }
            TextButton(onClick = onSignUp) { Text("Create an account") }
        }
    }
}

/**
 * A forgotten password, in three steps: a code is emailed to the address the account was made with, the
 * code is checked, and only then is the new password chosen.
 */
@Composable
private fun Forgot(state: AppState, knownId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var id by rememberSaveable { mutableStateOf(knownId) }
    // 1: who is asking. 2: the code from the email. 3: the new password. 4: done.
    var step by rememberSaveable { mutableStateOf(1) }
    var code by rememberSaveable { mutableStateOf("") }
    var ticket by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var again by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun work(job: suspend () -> Unit) {
        busy = true
        said = null
        scope.launch {
            job()
            busy = false
        }
    }

    val about = when (step) {
        1 -> "Step 1 of 3. Type your DeskLess ID, or the email address of your account. A code is emailed to that address."
        2 -> "Step 2 of 3. Type the 6-digit code from the email. It works for ten minutes."
        3 -> "Step 3 of 3. The code is confirmed. Choose your new password."
        else -> "Your password is changed."
    }
    Page("FORGOT PASSWORD", about, onBack) {
        when (step) {
            1 -> {
                Field(
                    "DeskLess ID or email", id,
                    { typed -> id = typed.filter { it.isLetterOrDigit() || it in "-@._+" }.take(120) },
                    "DL-INS-0001", last = true,
                )
                Problem(problem)
                BrandButton(
                    text = if (busy) "Sending…" else "Email me a code",
                    enabled = !busy && id.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        work {
                            problem = state.forgotPassword(id)
                            if (problem == null) step = 2
                        }
                    },
                )
            }
            2 -> {
                Field("Code from the email", code, { typed -> code = typed.filter(Char::isDigit).take(6) }, "6 digits", keyboard = KeyboardType.Number, last = true)
                Problem(problem)
                said?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = GoGreen) }
                BrandButton(
                    text = if (busy) "Checking…" else "Check the code",
                    enabled = !busy && code.length == 6,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        work {
                            val (pass, trouble) = state.checkResetCode(id, code)
                            problem = trouble
                            if (pass != null) {
                                ticket = pass
                                step = 3
                            }
                        }
                    },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { step = 1; code = ""; problem = null }, enabled = !busy) { Text("Change the ID") }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            work {
                                problem = state.forgotPassword(id)
                                if (problem == null) {
                                    code = ""
                                    said = "A new code is on its way. The earlier one no longer works."
                                }
                            }
                        },
                    ) { Text("Send the code again") }
                }
            }
            3 -> {
                Field("New password", password, { password = it }, "At least ${Checks.MIN_PASSWORD} characters", secret = true)
                Field("New password, once more", again, { again = it }, secret = true, last = true)
                Problem(problem ?: if (again.isNotEmpty() && again != password) "The two passwords are not the same yet." else null)
                BrandButton(
                    text = if (busy) "Changing…" else "Change password",
                    enabled = !busy && password.length >= Checks.MIN_PASSWORD && again == password,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        work {
                            problem = state.resetPassword(ticket, password)
                            if (problem == null) step = 4
                        }
                    },
                )
            }
            else -> {
                Text("Sign in with the new one.", style = MaterialTheme.typography.bodyLarge, color = GoGreen)
                BrandButton("Back to sign in", onClick = onBack, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Shown when the app is opened by someone already signed in: the fingerprint lets them in. A phone with no
 * fingerprint set up has nothing to ask for, and goes straight through.
 */
@Composable
fun LockScreen(state: AppState) {
    val context = LocalContext.current
    val person = state.profile ?: return
    var refused by remember { mutableStateOf(false) }
    fun ask() {
        refused = false
        Unlock.ask(context, person.name) { matched -> if (matched) state.unlocked = true else refused = true }
    }
    LaunchedEffect(Unit) { if (Unlock.available(context)) ask() else state.unlocked = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Profile(state, Modifier.size(112.dp))
        Text(person.name, style = MaterialTheme.typography.headlineMedium, color = Chalk)
        Text("${person.uniqueId} · ${person.roleName}", style = MaterialTheme.typography.titleMedium, color = SafetyYellow)
        if (person.organisation.isNotBlank()) Text(person.organisation, style = MaterialTheme.typography.bodyMedium, color = Fog)
        Spacer(Modifier.height(12.dp))
        BrandButton("Unlock", onClick = { ask() }, icon = Icons.Rounded.Fingerprint, modifier = Modifier.fillMaxWidth())
        if (refused) Text("The fingerprint was not recognised. Try again.", style = MaterialTheme.typography.bodyMedium, color = Fog)
        TextButton(onClick = { state.signOut() }) { Text("Sign out", color = Fog) }
    }
}

/** The signed-in person's photo in a circle, or a plain figure when the phone has none. */
@Composable
fun Profile(state: AppState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Steel)
            .border(1.5.dp, SafetyYellow, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // Reading the number redraws the photo when a new one has been fetched.
        if (state.photoVersion >= 0 && state.accountPhoto.isFile) {
            Photo(state.accountPhoto, maxEdge = 256, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.Person, contentDescription = null, tint = Fog)
        }
    }
}
