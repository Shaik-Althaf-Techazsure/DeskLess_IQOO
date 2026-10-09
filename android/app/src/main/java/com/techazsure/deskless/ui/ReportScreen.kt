package com.techazsure.deskless.ui

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.techazsure.deskless.AppState
import com.techazsure.deskless.Screen
import androidx.compose.material.icons.rounded.Groups
import com.techazsure.deskless.report.Handoff
import com.techazsure.deskless.report.ReportPack
import com.techazsure.deskless.ui.theme.Chalk
import com.techazsure.deskless.ui.theme.Fog
import com.techazsure.deskless.ui.theme.GoGreen
import com.techazsure.deskless.ui.theme.Ink
import com.techazsure.deskless.ui.theme.SafetyYellow
import com.techazsure.deskless.ui.theme.Steel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ReportScreen(state: AppState, file: File, fromLibrary: Boolean = false) {
    val context = LocalContext.current
    var savedTo by remember(file) { mutableStateOf<String?>(null) }
    val back = { state.screen = if (fromLibrary) Screen.Reports else Screen.Home }
    val entry = state.reports.firstOrNull { it.fileName == file.name }
    BackHandler { back() }

    // The tick draws itself, then the page rises into place.
    val tick = remember(file) { Animatable(0f) }
    val page = remember(file) { Animatable(0f) }
    LaunchedEffect(file) {
        tick.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        page.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 180f))
    }
    val preview by produceState<ImageBitmap?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) { runCatching { firstPage(file) }.getOrNull() }
    }

    fun open() {
        try {
            context.startActivity(Handoff.viewIntent(context, file))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app on this phone can open a PDF.", Toast.LENGTH_LONG).show()
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
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = back, container = Steel)
            Spacer(Modifier.width(14.dp))
            Text("REPORT", style = MaterialTheme.typography.headlineSmall, color = Chalk)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(46.dp)) {
                    drawCircle(GoGreen.copy(alpha = 0.18f))
                    drawArc(
                        color = GoGreen,
                        startAngle = -90f,
                        sweepAngle = 360f * tick.value,
                        useCenter = false,
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                    )
                    val s = size.minDimension
                    val mark = Path().apply {
                        moveTo(s * 0.30f, s * 0.52f)
                        lineTo(s * 0.44f, s * 0.66f)
                        lineTo(s * 0.71f, s * 0.36f)
                    }
                    val measure = PathMeasure().apply { setPath(mark, false) }
                    val shown = Path()
                    measure.getSegment(0f, measure.length * ((tick.value - 0.4f) / 0.6f).coerceIn(0f, 1f), shown, true)
                    drawPath(shown, GoGreen, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("REPORT DONE.", style = MaterialTheme.typography.headlineMedium, color = SafetyYellow)
                    Text(
                        // What the library recorded about this report; an older one has only its size.
                        text = listOfNotNull(
                            entry?.template?.title,
                            entry?.takeIf { it.template != null }?.let { if (it.issues == 1) "1 issue" else "${it.issues} issues" },
                            "${file.length() / 1024} KB",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Fog,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))

            // The real first page of the PDF, tappable.
            val interaction = remember { MutableInteractionSource() }
            Surface(
                onClick = { open() },
                interactionSource = interaction,
                shape = RoundedCornerShape(10.dp),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .aspectRatio(595f / 842f)
                    .graphicsLayer {
                        alpha = page.value.coerceIn(0f, 1f)
                        translationY = (1f - page.value) * 70.dp.toPx()
                        rotationZ = (1f - page.value) * -4f
                    }
                    .shadow(18.dp, RoundedCornerShape(10.dp))
                    .bouncy(interaction, pressedScale = 0.97f),
            ) {
                preview?.let {
                    Image(bitmap = it, contentDescription = "First page of the report", modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(file.name, style = MaterialTheme.typography.bodySmall, color = Fog)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .background(Steel, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AirplanemodeActive, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(16.dp))
                Text("Built on this phone. Nothing was uploaded.", style = MaterialTheme.typography.labelMedium, color = Chalk)
            }
            Spacer(Modifier.height(16.dp))
        }

        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(visible = savedTo != null, enter = fadeIn() + expandVertically()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GoGreen.copy(alpha = 0.14f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = GoGreen, modifier = Modifier.size(20.dp))
                    Text(
                        text = "Saved in $savedTo. Pick the folder up on the laptop with Office Kit file transfer.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Chalk,
                    )
                }
            }
            // The report travels with its working files: a spreadsheet, a calendar and the owners' lists.
            val pack = remember(file) { ReportPack.files(file) }
            val owners = pack.firstOrNull { it.name.endsWith("-owners.txt") }
            if (savedTo == null) {
                Text(
                    text = "The pack: " + pack.joinToString(", ") {
                        when (it.extension) {
                            "pdf" -> "report (PDF)"
                            "csv" -> "spreadsheet of issues"
                            "ics" -> "calendar of deadlines"
                            else -> "task list per owner"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Fog,
                )
                BrandButton(
                    text = if (pack.size > 1) "Save the pack for Office Kit" else "Save for Office Kit",
                    onClick = {
                        savedTo = Handoff.savePack(context, pack)?.let { (folder, count) -> "$folder ($count files)" }
                            ?: Handoff.saveToDocuments(context, file)
                        if (savedTo == null) {
                            Toast.makeText(context, "Could not save to Documents.", Toast.LENGTH_LONG).show()
                        }
                    },
                    icon = Icons.Rounded.SaveAlt,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandButton(
                    text = "Open",
                    onClick = { open() },
                    icon = Icons.Rounded.OpenInNew,
                    kind = ButtonKind.Secondary,
                    modifier = Modifier.weight(1f),
                )
                BrandButton(
                    text = "Share",
                    onClick = { context.startActivity(Handoff.shareIntent(context, file)) },
                    icon = Icons.Rounded.Share,
                    kind = ButtonKind.Secondary,
                    modifier = Modifier.weight(1f),
                )
            }
            if (owners != null) {
                BrandButton(
                    text = "Send owners their tasks",
                    onClick = {
                        context.startActivity(Handoff.shareText(owners.readText(), "Send the task lists"))
                    },
                    icon = Icons.Rounded.Groups,
                    kind = ButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                    height = 50.dp,
                )
            }
        }
    }
}

/** Renders page one of the PDF so the screen can show the actual report, not a placeholder. */
private fun firstPage(file: File): ImageBitmap {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(0).use { page ->
                val width = 900
                val bitmap = Bitmap.createBitmap(width, width * page.height / page.width, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap.asImageBitmap()
            }
        }
    }
}
