package com.techazsure.deskless.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.techazsure.deskless.AppState
import com.techazsure.deskless.Screen
import com.techazsure.deskless.capture.Locator
import com.techazsure.deskless.capture.WavRecorder
import com.techazsure.deskless.capture.extractFrames
import com.techazsure.deskless.capture.normalizeJpeg
import com.techazsure.deskless.data.Readings
import com.techazsure.deskless.data.Stamp
import com.techazsure.deskless.structure.Structurer
import com.techazsure.deskless.ui.theme.Chalk
import com.techazsure.deskless.ui.theme.Fog
import com.techazsure.deskless.ui.theme.Ink
import com.techazsure.deskless.ui.theme.SafetyYellow
import com.techazsure.deskless.ui.theme.SignalRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A clip is evidence, not a film: long enough to pan across a problem and say what it is. */
private const val MAX_CLIP_SECONDS = 20

/** The position and step-count prompts are shown once per run of the app, not at every capture. */
private var extrasAsked = false

/** How the torch is run: by the light sensor, or by hand. */
private enum class Torch { Auto, On, Off }

@Composable
fun CaptureScreen(state: AppState, lifecycleOwner: LifecycleOwner, retakeId: String? = null, fix: Boolean = false) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    // Position and step count are a bonus, not a condition: the answer is not waited for, and a refusal
    // changes nothing.
    val askExtras = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state.sensors.countSteps()
        state.watchPosition(true)
    }
    // The phone starts finding its position as the camera opens, so the picture is stamped without a wait.
    DisposableEffect(Unit) {
        state.watchPosition(true)
        onDispose { state.watchPosition(false) }
    }
    val leave = { state.screen = if (retakeId != null) Screen.Edit(retakeId) else Screen.Home }
    LaunchedEffect(Unit) { if (!granted) askCamera.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(granted) {
        val wanted = buildList {
            if (!state.locationAllowed) addAll(Locator.PERMISSIONS)
            if (state.sensors.hasStepCounter && !state.sensors.stepsAllowed) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (granted && wanted.isNotEmpty() && !extrasAsked) {
            extrasAsked = true
            askExtras.launch(wanted.toTypedArray())
        }
    }
    BackHandler { leave() }

    if (granted) {
        Viewfinder(state, lifecycleOwner, retakeId, fix, leave)
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Ink)
                .systemBarsPadding()
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.CameraAlt, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(56.dp))
            Text("CAMERA NEEDED", style = MaterialTheme.typography.headlineMedium, color = Chalk)
            Text(
                text = "DeskLess photographs each issue for the report. Photos stay on this phone.",
                style = MaterialTheme.typography.bodyLarge,
                color = Fog,
                textAlign = TextAlign.Center,
            )
            BrandButton("Allow camera", onClick = { askCamera.launch(Manifest.permission.CAMERA) }, icon = Icons.Rounded.CameraAlt)
            BrandButton("Back", onClick = leave, kind = ButtonKind.Secondary)
        }
    }
}

@Composable
private fun Viewfinder(state: AppState, lifecycleOwner: LifecycleOwner, retakeId: String?, fix: Boolean, leave: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
                .build()
        )
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    // The phone's sensors run while the camera is open, to stamp the picture with what they read.
    val sensors = state.sensors
    DisposableEffect(Unit) {
        sensors.watch()
        onDispose { sensors.unwatch() }
    }
    var torch by remember { mutableStateOf(Torch.Auto) }
    var lit by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var widest by remember { mutableFloatStateOf(1f) }
    var longest by remember { mutableFloatStateOf(1f) }

    var video by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // A clip in progress: the CameraX recording, the voice recorder running beside it, and its issue id.
    var recording by remember { mutableStateOf<Recording?>(null) }
    val voice = remember { WavRecorder() }
    var clipId by remember { mutableStateOf("") }
    var seconds by remember { mutableIntStateOf(0) }

    // The brackets close in when the screen opens; the white flash fires on the shutter.
    val brackets = remember { Animatable(1.25f) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(Unit) { brackets.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f)) }

    LaunchedEffect(Unit) {
        val pending = ProcessCameraProvider.getInstance(context)
        pending.addListener({ provider = runCatching { pending.get() }.getOrNull() }, ContextCompat.getMainExecutor(context))
    }
    // Photo and video are bound one at a time: not every phone can run both alongside the preview.
    LaunchedEffect(provider, video) {
        val ready = provider ?: return@LaunchedEffect
        try {
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            ready.unbindAll()
            val bound = ready.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                if (video) videoCapture else imageCapture,
            )
            // A fresh binding starts at the main camera with the torch off.
            zoom = 1f
            lit = false
            bound.cameraInfo.zoomState.value?.let {
                widest = it.minZoomRatio
                longest = it.maxZoomRatio
            }
            camera = bound
        } catch (e: Exception) {
            error = "Could not start the camera: ${e.message}"
        }
    }
    // On Auto the light sensor runs the torch: on in the dark, and off again only once it is clearly
    // bright, so the torch's own light bouncing back does not make it flicker.
    LaunchedEffect(torch, camera) {
        when (torch) {
            Torch.On -> lit = true
            Torch.Off -> lit = false
            Torch.Auto -> snapshotFlow { sensors.lux }.collect { lux ->
                if (lux != null) lit = if (lit) lux < 40f else lux < 8f
            }
        }
    }
    LaunchedEffect(lit, camera) {
        camera?.takeIf { it.cameraInfo.hasFlashUnit() }?.cameraControl?.enableTorch(lit)
    }
    LaunchedEffect(zoom, camera) { camera?.cameraControl?.setZoomRatio(zoom) }

    /** What the sensors read at this moment, for the picture being taken. A repair picture carries none. */
    fun stampNow(): Stamp? =
        if (fix) null else sensors.stamp(zoom, torch = lit && camera?.cameraInfo?.hasFlashUnit() == true)
    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            voice.stop()
            provider?.unbindAll()
        }
    }

    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) video = true else error = "Video notes need the microphone, to hear you describe the problem."
    }

    fun shoot() {
        if (saving) return
        saving = true
        error = null
        state.haptics.click()
        scope.launch {
            flash.snapTo(0.85f)
            flash.animateTo(0f, tween(320))
        }
        val stamp = stampNow()
        val id = retakeId ?: state.newIssueId()
        // A retake or a repair picture gets a new file name so an old picture cannot be shown from a cache.
        val file = state.fileOf(
            when {
                retakeId == null -> "photo_$id.jpg"
                fix -> "fix_${id}_${System.currentTimeMillis()}.jpg"
                else -> "photo_${id}_${System.currentTimeMillis()}.jpg"
            }
        )
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    scope.launch {
                        withContext(Dispatchers.IO) { normalizeJpeg(file) }
                        when {
                            retakeId == null -> state.addIssue(id, file.name, stamp)
                            fix -> state.addFixPhoto(id, file.name)
                            else -> state.replacePhoto(id, file.name, stamp)
                        }
                        state.screen = Screen.Edit(id)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    saving = false
                    error = "Could not take the photo: ${exception.message}"
                }
            },
        )
    }

    var clipStamp by remember { mutableStateOf<Stamp?>(null) }

    fun finishClip(id: String, failed: Boolean) {
        val clip = state.fileOf("video_$id.mp4")
        val sound = state.fileOf("voice_$id.wav")
        if (failed) {
            clip.delete()
            sound.delete()
            saving = false
            error = "Could not save the video. Try again."
            return
        }
        scope.launch {
            val frames = List(Structurer.MAX_PICTURES) { "frame_${id}_$it.jpg" }
            val written = withContext(Dispatchers.IO) {
                runCatching { extractFrames(clip, frames.map(state::fileOf)) }.getOrDefault(0)
            }
            if (written == 0) {
                clip.delete()
                sound.delete()
                saving = false
                error = "That clip was too short to use. Hold it a little longer."
            } else {
                state.addVideoIssue(id, clip.name, frames.take(written), sound.name, WavRecorder.seconds(sound), clipStamp)
                state.screen = Screen.Edit(id)
            }
        }
    }

    fun startClip() {
        error = null
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val id = state.newIssueId()
        clipId = id
        clipStamp = stampNow()
        seconds = 0
        // The voice is recorded beside the silent clip, straight into the format the speech model reads.
        voice.start(state.fileOf("voice_$id.wav"))
        recording = videoCapture.output
            .prepareRecording(context, FileOutputOptions.Builder(state.fileOf("video_$id.mp4")).build())
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) finishClip(id, failed = event.hasError())
            }
    }

    fun stopClip() {
        val active = recording ?: return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        saving = true
        recording = null
        voice.stop()
        active.stop()
    }

    LaunchedEffect(recording) {
        if (recording == null) return@LaunchedEffect
        val started = SystemClock.elapsedRealtime()
        while (true) {
            seconds = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
            if (seconds >= MAX_CLIP_SECONDS) {
                stopClip()
                break
            }
            delay(200)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })

        // Shade top and bottom so the controls read over any scene.
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(350.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
        )

        val bracketColor by animateColorAsState(if (recording != null) SignalRed else SafetyYellow, label = "brackets")
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(start = 36.dp, end = 36.dp, top = 170.dp, bottom = 340.dp)
                .graphicsLayer {
                    scaleX = brackets.value
                    scaleY = brackets.value
                }
        ) {
            val arm = 34.dp.toPx()
            val width = 5.dp.toPx()
            listOf(
                Offset(0f, 0f) to Offset(1f, 1f),
                Offset(size.width, 0f) to Offset(-1f, 1f),
                Offset(0f, size.height) to Offset(1f, -1f),
                Offset(size.width, size.height) to Offset(-1f, -1f),
            ).forEach { (at, dir) ->
                drawLine(bracketColor, at, Offset(at.x + arm * dir.x, at.y), width, StrokeCap.Round)
                drawLine(bracketColor, at, Offset(at.x, at.y + arm * dir.y), width, StrokeCap.Round)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.Rounded.Close, "Cancel", onClick = leave)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        fix -> "AFTER THE FIX"
                        retakeId != null -> "RETAKE"
                        else -> "CAPTURE"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                val number = if (retakeId != null) state.issues.indexOfFirst { it.id == retakeId } + 1 else state.issues.size + 1
                Text(
                    text = listOf(state.inspection.site, "Issue $number").filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
            if (recording == null && camera?.cameraInfo?.hasFlashUnit() == true) {
                RoundIconButton(
                    icon = when (torch) {
                        Torch.Auto -> Icons.Rounded.FlashAuto
                        Torch.On -> Icons.Rounded.FlashOn
                        Torch.Off -> Icons.Rounded.FlashOff
                    },
                    label = "Torch: ${torch.name}",
                    container = if (lit) SafetyYellow else Ink.copy(alpha = 0.55f),
                    content = if (lit) Ink else Chalk,
                    // A tap overrules the light sensor either way: off if it is on, on if it is off.
                    onClick = { torch = if (lit) Torch.Off else Torch.On },
                )
            }
            if (recording != null) {
                val blink = pulse(0.35f, 1f, 500)
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .graphicsLayer { alpha = blink }
                            .background(SignalRed, CircleShape)
                    )
                    Text(
                        text = "0:%02d / 0:%02d".format(seconds, MAX_CLIP_SECONDS),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        }

        if (!fix) {
            SensorReadout(
                state,
                modifier = Modifier
                    .systemBarsPadding()
                    .padding(start = 16.dp, top = 78.dp, end = 16.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = error ?: when {
                    saving && video -> "Saving the clip and picking stills…"
                    saving -> "Saving the photo…"
                    recording != null -> "Filming. Say what is wrong, then tap to stop."
                    video -> "Film the problem and describe it aloud"
                    fix -> "Photograph the same spot, now repaired"
                    retakeId != null -> "Frame the problem again, then tap"
                    else -> "Frame the problem, then tap"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (error != null) SafetyYellow else Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(14.dp))

            // The phone's other lenses: wide for the whole room, long for what is out of reach.
            val stops = listOfNotNull(widest.takeIf { it < 0.95f }, 1f, 3f.takeIf { longest >= 3f })
            if (stops.size > 1 && recording == null && !saving) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    stops.forEach { stop ->
                        ZoomStop(stop, selected = kotlin.math.abs(zoom - stop) < 0.05f) { zoom = stop }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // A retake replaces a photo, so it offers no choice of mode.
            if (retakeId == null && recording == null && !saving) {
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ModeTab(Icons.Rounded.CameraAlt, "Photo", selected = !video) { video = false }
                    ModeTab(Icons.Rounded.Videocam, "Video", selected = video) {
                        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (allowed) video = true else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                Spacer(Modifier.height(16.dp))
            } else {
                Spacer(Modifier.height(60.dp))
            }

            val interaction = remember { MutableInteractionSource() }
            val core by animateColorAsState(
                targetValue = when {
                    saving -> Fog
                    video -> SignalRed
                    else -> SafetyYellow
                },
                label = "shutter",
            )
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .bouncy(interaction, pressedScale = 0.86f)
                    .clip(CircleShape)
                    .border(5.dp, Color.White, CircleShape)
                    // A filming shutter shrinks to the familiar rounded stop square.
                    .padding(if (recording != null) 24.dp else 9.dp)
                    .clip(if (recording != null) RoundedCornerShape(8.dp) else CircleShape)
                    .background(core)
                    .clickable(interactionSource = interaction, indication = null, enabled = !saving) {
                        when {
                            !video -> shoot()
                            recording == null -> startClip()
                            else -> stopClip()
                        }
                    },
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = flash.value }
                .background(Color.White)
        )
    }
}

/** What the sensors read right now, shown over the picture: it is what will be stamped on the issue. */
@Composable
private fun SensorReadout(state: AppState, modifier: Modifier = Modifier) {
    val sensors = state.sensors
    val chips = listOfNotNull(
        state.place.takeIf { it.isNotBlank() }?.let { Icons.Rounded.Nfc to it },
        sensors.heading?.let { Icons.Rounded.Explore to "${Readings.compassShort(it)} ${it.toInt()}°" },
        Readings.aim(sensors.pitch)?.let { Icons.Rounded.SwapVert to it.replaceFirstChar(Char::uppercase) },
        sensors.lux?.let { Icons.Rounded.WbSunny to "${it.toInt()} lux · ${Readings.light(it)}" },
    )
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (icon, text) ->
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(13.dp))
                Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ZoomStop(ratio: Float, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (selected) SafetyYellow else Color.Black.copy(alpha = 0.5f), label = "zoom")
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = CircleShape,
        color = fill,
        contentColor = if (selected) Ink else Color.White,
        modifier = Modifier
            .size(42.dp)
            .bouncy(interaction, pressedScale = 0.88f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = (if (ratio < 1f) "%.1f".format(ratio) else "%.0f".format(ratio)) + "×",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ModeTab(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (selected) SafetyYellow else Color.Transparent, label = "mode")
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(50),
        color = fill,
        contentColor = if (selected) Ink else Color.White,
        modifier = Modifier.bouncy(interaction),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
