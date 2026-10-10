package com.techazsure.deskless

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.nfc.NfcAdapter
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import com.techazsure.deskless.capture.Haptics
import com.techazsure.deskless.capture.Locator
import com.techazsure.deskless.capture.SiteSensors
import com.techazsure.deskless.capture.TagBook
import com.techazsure.deskless.data.IssueStore
import com.techazsure.deskless.link.Pairing
import com.techazsure.deskless.link.Voice
import com.techazsure.deskless.models.ModelStore
import com.techazsure.deskless.report.ReportLibrary
import com.techazsure.deskless.ui.AccountFlow
import com.techazsure.deskless.ui.LockScreen
import com.techazsure.deskless.ui.ReportsScreen
import com.techazsure.deskless.ui.CaptureScreen
import com.techazsure.deskless.ui.EditScreen
import com.techazsure.deskless.ui.HomeScreen
import com.techazsure.deskless.ui.IntroScreen
import com.techazsure.deskless.ui.ModelsScreen
import com.techazsure.deskless.ui.ReportScreen
import com.techazsure.deskless.ui.ScanScreen
import com.techazsure.deskless.ui.SensorsScreen
import com.techazsure.deskless.ui.theme.DesklessTheme
import com.techazsure.deskless.ui.theme.Ink
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState
    private var nfc: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark in every light, so the system bars always carry light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        state = AppState(
            applicationContext,
            IssueStore(applicationContext),
            ModelStore(applicationContext),
            ReportLibrary(applicationContext),
            Locator(applicationContext),
            SiteSensors(applicationContext),
            Haptics(applicationContext),
            TagBook(applicationContext),
            Voice(applicationContext),
            cacheDir,
        )
        state.sensors.countSteps()
        nfc = NfcAdapter.getDefaultAdapter(this)
        setContent {
            DesklessTheme {
                DeskLessApp(state, lifecycleOwner = this)
            }
        }
        simulatedTag(intent)
        scannedCode(intent)
    }

    // The link to other phones runs for as long as DeskLess is on screen. A system prompt over the app
    // does not stop it; leaving the app does.
    override fun onStart() {
        super.onStart()
        state.link.start()
        // Reports built with no network go to the office as soon as there is one.
        state.checkIn()
        state.sendToOffice()
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network) }
    }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(found: Network) {
            runOnUiThread {
                state.checkIn()
                state.sendToOffice()
            }
        }
    }

    override fun onStop() {
        state.link.stop()
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        super.onStop()
    }

    override fun onDestroy() {
        state.voice.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // While DeskLess is on screen every tag tapped comes here, whatever kind it is; a tag is known by
        // its serial number, so a plain sticker or any chip card will do.
        runCatching {
            nfc?.enableReaderMode(
                this,
                { tag ->
                    val id = TagBook.idOf(tag)
                    val written = TagBook.textOn(tag)
                    runOnUiThread { state.onTag(id, written) }
                },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                null,
            )
        }
    }

    override fun onPause() {
        runCatching { nfc?.disableReaderMode(this) }
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        simulatedTag(intent)
        scannedCode(intent)
    }

    /** Another phone's DeskLess code, scanned with the phone's own camera app, opens DeskLess and links the two. */
    private fun scannedCode(intent: Intent?) {
        intent?.dataString?.let(Pairing::parse)?.let(state::pairWith)
    }

    /**
     * A test build can be handed a tag from a computer, since an emulator has no NFC:
     * `adb shell am start -n com.techazsure.deskless/.MainActivity --es tag 04A1B2C3`.
     */
    private fun simulatedTag(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        intent?.getStringExtra("tag")?.takeIf { it.isNotBlank() }?.let { state.onTag(it, intent.getStringExtra("tagText")) }
    }

    /** Whether the phone has NFC and it is switched on; null when it has none. */
    val nfcOn: Boolean?
        get() = nfc?.isEnabled
}

@Composable
fun DeskLessApp(state: AppState, lifecycleOwner: LifecycleOwner) {
    val context = LocalContext.current
    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            state.notice = null
        }
    }

    // A tag the phone has not met before is named once, wherever in the app it was tapped.
    state.newTag?.let { id ->
        var name by remember(id) { mutableStateOf(state.newTagHint) }
        AlertDialog(
            onDismissRequest = { state.nameNewTag("") },
            title = { Text("New tag") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("What is it on?") },
                    placeholder = { Text("Pump room, Fridge 2, Gate B…") },
                    singleLine = true,
                    supportingText = { Text("The phone remembers this tag. Tap it again and new issues are filed there.") },
                )
            },
            confirmButton = { TextButton(onClick = { state.nameNewTag(name) }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { state.nameNewTag("") }) { Text("Ignore") } },
        )
    }

    var introDone by rememberSaveable { mutableStateOf(false) }

    // As the app opens it gets the link ready: Android asks once to let DeskLess find nearby phones, and
    // offers to switch Bluetooth on if it is off. No app is allowed to switch it on without that one tap.
    val turnBluetoothOn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { state.link.start() }
    val allowLink = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (state.link.allowed && state.link.bluetoothOn == false) {
            runCatching { turnBluetoothOn.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        }
        state.link.start()
    }
    // Nothing of the app is reachable until somebody is signed in and, on a phone with a fingerprint set
    // up, has unlocked it.
    val inside = introDone && state.profile != null && state.unlocked
    LaunchedEffect(inside) {
        if (!inside) return@LaunchedEffect
        when {
            !state.link.allowed -> allowLink.launch(state.link.permissions)
            state.link.bluetoothOn == false -> runCatching { turnBluetoothOn.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            else -> state.link.start()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
    ) {
        if (introDone && state.profile == null) {
            AccountFlow(state)
        } else if (introDone && !state.unlocked) {
            LockScreen(state)
        } else AnimatedContent(
            targetState = if (introDone) state.screen else null,
            contentKey = { it?.let(::keyOf) ?: "intro" },
            transitionSpec = {
                val from = initialState
                val to = targetState
                when {
                    // The intro opens on to the home screen.
                    from == null -> (fadeIn(tween(320)) + scaleIn(tween(320), initialScale = 0.96f)) togetherWith fadeOut(tween(200))
                    to == null -> fadeIn() togetherWith fadeOut()
                    // Deeper screens come in from the right; going back, they leave to the right.
                    depthOf(to) >= depthOf(from) ->
                        (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(240))) togetherWith
                            (fadeOut(tween(180)) + scaleOut(tween(300), targetScale = 0.97f))
                    else ->
                        (fadeIn(tween(240)) + scaleIn(tween(300), initialScale = 0.97f)) togetherWith
                            (slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 4 } + fadeOut(tween(200)))
                }
            },
            label = "screens",
        ) { screen ->
            when (screen) {
                null -> IntroScreen(onFinished = { introDone = true })
                Screen.Home -> HomeScreen(state)
                is Screen.Capture -> CaptureScreen(state, lifecycleOwner, screen.retakeId, screen.fix)
                Screen.Models -> ModelsScreen(state)
                Screen.Sensors -> SensorsScreen(state)
                Screen.Reports -> ReportsScreen(state)
                Screen.Scan -> ScanScreen(state, lifecycleOwner)
                is Screen.Edit -> {
                    val issue = state.issues.firstOrNull { it.id == screen.id }
                    if (issue == null) {
                        // The issue was deleted; if this is still the current screen, leave it.
                        LaunchedEffect(screen) { if (state.screen == screen) state.screen = Screen.Home }
                    } else {
                        EditScreen(state, issue)
                    }
                }
                is Screen.Report -> ReportScreen(state, File(screen.path), screen.fromLibrary)
            }
        }
    }
}

private fun keyOf(screen: Screen): String = when (screen) {
    Screen.Home -> "home"
    is Screen.Capture -> "capture-${screen.retakeId}-${screen.fix}"
    Screen.Models -> "models"
    Screen.Sensors -> "sensors"
    Screen.Reports -> "reports"
    Screen.Scan -> "scan"
    is Screen.Edit -> "edit-${screen.id}"
    is Screen.Report -> "report-${screen.path}"
}

private fun depthOf(screen: Screen): Int = when (screen) {
    Screen.Home -> 0
    Screen.Models, Screen.Sensors, Screen.Reports, Screen.Scan -> 1
    // A retake is opened from an issue, so it sits above it; a new capture sits below.
    is Screen.Capture -> if (screen.retakeId != null) 3 else 1
    is Screen.Edit -> 2
    is Screen.Report -> 2
}
