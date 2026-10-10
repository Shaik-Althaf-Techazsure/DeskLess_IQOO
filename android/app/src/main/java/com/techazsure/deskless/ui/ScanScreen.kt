package com.techazsure.deskless.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.techazsure.deskless.AppState
import com.techazsure.deskless.Screen
import com.techazsure.deskless.link.Pairing
import com.techazsure.deskless.link.Qr
import com.techazsure.deskless.ui.theme.SafetyYellow
import java.util.concurrent.Executors

/** The camera looking for another phone's DeskLess code. Reading one links the two phones and goes back. */
@Composable
fun ScanScreen(state: AppState, lifecycleOwner: LifecycleOwner) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askCamera.launch(Manifest.permission.CAMERA) }
    val leave = { state.screen = Screen.Home }
    BackHandler { leave() }

    var hint by remember { mutableStateOf<String?>(null) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val reader = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        val pending = ProcessCameraProvider.getInstance(context)
        pending.addListener({ provider = runCatching { pending.get() }.getOrNull() }, ContextCompat.getMainExecutor(context))
    }
    LaunchedEffect(provider) {
        val ready = provider ?: return@LaunchedEffect
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        // Only the newest frame is looked at; a frame that arrives while one is being read is dropped.
        val frames = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        var done = false
        frames.setAnalyzer(reader) { image ->
            val text = if (done) {
                null
            } else {
                // The first plane of a camera frame is its brightness, which is all a QR code needs.
                val plane = image.planes[0]
                val width = image.width
                val height = image.height
                val luminance = ByteArray(width * height)
                val buffer = plane.buffer
                for (row in 0 until height) {
                    buffer.position(row * plane.rowStride)
                    buffer.get(luminance, row * width, minOf(width, buffer.remaining()))
                }
                Qr.read(luminance, width, height)
            }
            image.close()
            if (text != null) {
                val peer = Pairing.parse(text)
                ContextCompat.getMainExecutor(context).execute {
                    if (peer == null) {
                        hint = "That is not a DeskLess phone code. Open the side bar on the other phone."
                    } else if (!done) {
                        done = true
                        state.pairWith(peer)
                        leave()
                    }
                }
            }
        }
        runCatching {
            ready.unbindAll()
            ready.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, frames)
        }.onFailure { hint = "Could not start the camera: ${it.message}" }
    }
    DisposableEffect(Unit) {
        onDispose {
            provider?.unbindAll()
            reader.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (granted) AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
        )

        // The square to hold the other phone's code in.
        Canvas(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.72f)
                .aspectRatio(1f)
        ) {
            val arm = 38.dp.toPx()
            val width = 5.dp.toPx()
            listOf(
                Offset(0f, 0f) to Offset(1f, 1f),
                Offset(size.width, 0f) to Offset(-1f, 1f),
                Offset(0f, size.height) to Offset(1f, -1f),
                Offset(size.width, size.height) to Offset(-1f, -1f),
            ).forEach { (at, dir) ->
                drawLine(SafetyYellow, at, Offset(at.x + arm * dir.x, at.y), width, StrokeCap.Round)
                drawLine(SafetyYellow, at, Offset(at.x, at.y + arm * dir.y), width, StrokeCap.Round)
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
            Column {
                Text("SCAN A PHONE", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text("Its code is in the DeskLess side bar", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .systemBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = hint ?: if (granted) "Hold the other phone's code inside the square" else "The camera is needed to read the other phone's code.",
                style = MaterialTheme.typography.titleMedium,
                color = if (hint != null) SafetyYellow else Color.White,
                textAlign = TextAlign.Center,
            )
            if (!granted) BrandButton("Allow camera", onClick = { askCamera.launch(Manifest.permission.CAMERA) }, icon = Icons.Rounded.CameraAlt)
        }
    }
}
