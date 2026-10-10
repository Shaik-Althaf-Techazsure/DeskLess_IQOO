package com.techazsure.deskless.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.PrecisionManufacturing
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warehouse
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Help
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.Verified
import com.techazsure.deskless.capture.decodeSampled
import com.techazsure.deskless.data.Check
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.data.Template
import com.techazsure.deskless.ui.theme.Fog
import com.techazsure.deskless.ui.theme.Chalk
import com.techazsure.deskless.ui.theme.GoGreen
import com.techazsure.deskless.ui.theme.Ink
import com.techazsure.deskless.ui.theme.Line
import com.techazsure.deskless.ui.theme.SafetyYellow
import com.techazsure.deskless.ui.theme.SeverityCritical
import com.techazsure.deskless.ui.theme.SeverityHigh
import com.techazsure.deskless.ui.theme.SeverityLow
import com.techazsure.deskless.ui.theme.SeverityMedium
import com.techazsure.deskless.ui.theme.SignalRed
import com.techazsure.deskless.ui.theme.Steel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

fun severityColor(severity: Severity): Color = when (severity) {
    Severity.LOW -> SeverityLow
    Severity.MEDIUM -> SeverityMedium
    Severity.HIGH -> SeverityHigh
    Severity.CRITICAL -> SeverityCritical
}

/**
 * The touch feel shared by everything tappable: it sinks a little under a finger and lifts a little under a
 * mouse pointer (the phone can be driven with one over Office Kit), with a springy return.
 */
@Composable
fun Modifier.bouncy(interaction: InteractionSource, pressedScale: Float = 0.95f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> pressedScale
            hovered -> 1.03f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 650f),
        label = "bouncy",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

enum class ButtonKind { Primary, Secondary, Danger }

@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    height: Dp = 56.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val fade = if (enabled) 1f else 0.35f
    val container = when (kind) {
        ButtonKind.Primary -> SafetyYellow
        ButtonKind.Secondary -> Steel
        ButtonKind.Danger -> SignalRed
    }
    val content = when (kind) {
        ButtonKind.Primary -> Ink
        ButtonKind.Secondary -> Chalk
        ButtonKind.Danger -> Color.White
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(18.dp),
        color = container.copy(alpha = fade),
        contentColor = content.copy(alpha = if (enabled) 1f else 0.6f),
        border = if (kind == ButtonKind.Secondary) BorderStroke(1.dp, Line) else null,
        modifier = modifier
            .height(height)
            .bouncy(interaction),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A round icon button on a translucent disc, for use over photos and the camera. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Ink.copy(alpha = 0.55f),
    content: Color = Chalk,
    size: Dp = 44.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = modifier
            .size(size)
            .bouncy(interaction, pressedScale = 0.88f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
fun SeverityPill(severity: Severity, modifier: Modifier = Modifier) {
    val color = severityColor(severity)
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
        Text(
            text = severity.label.uppercase(Locale.ENGLISH),
            color = color,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

fun templateIcon(template: Template?): ImageVector = when (template?.name) {
    "GENERAL" -> Icons.Rounded.FactCheck
    "SITE" -> Icons.Rounded.Construction
    "FACTORY" -> Icons.Rounded.PrecisionManufacturing
    "WAREHOUSE" -> Icons.Rounded.Warehouse
    "CAMPUS" -> Icons.Rounded.School
    "OFFICE" -> Icons.Rounded.AccountBalance
    "RESIDENTIAL" -> Icons.Rounded.Apartment
    "RETAIL" -> Icons.Rounded.Storefront
    "HOTEL" -> Icons.Rounded.Hotel
    "HOSPITAL" -> Icons.Rounded.LocalHospital
    "MEDICATION" -> Icons.Rounded.Medication
    "COMMUNITY" -> Icons.Rounded.Groups
    null -> Icons.Rounded.Description
    // A type the company made for itself.
    else -> Icons.Rounded.Tune
}

/** A rounded choice chip, used for filters and for picking an issue's type. */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    count: Int? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (selected) SafetyYellow else Steel, label = "pill")
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(50),
        color = fill,
        border = if (selected) null else BorderStroke(1.dp, Line),
        modifier = modifier.bouncy(interaction),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dot != null) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (selected) Ink else dot, CircleShape)
                )
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Ink else Chalk, maxLines = 1)
            if (count != null) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Ink.copy(alpha = 0.65f) else Fog,
                )
            }
        }
    }
}

/** The colour, icon and short wording for the AI's verdict on a picture; null when it has none. */
fun checkLook(check: Check): Triple<Color, ImageVector, String>? = when (check) {
    Check.NONE -> null
    Check.MATCH -> Triple(GoGreen, Icons.Rounded.Verified, "Picture matches")
    Check.CONFIRMED -> Triple(GoGreen, Icons.Rounded.Verified, "Confirmed by you")
    Check.UNSURE -> Triple(SeverityMedium, Icons.Rounded.Help, "AI not sure")
    Check.MISMATCH -> Triple(SignalRed, Icons.Rounded.ErrorOutline, "Picture may not match")
}

/** The small verdict shown on an issue card. */
@Composable
fun CheckMark(check: Check, modifier: Modifier = Modifier) {
    val (color, icon, label) = checkLook(check) ?: return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The DeskLess mark: a clipboard with a tick on a yellow tile. [check] draws the tick from 0 to 1;
 * [corner] is the tile's corner radius as a share of its size (0.5 makes it the round launch icon).
 */
@Composable
fun LogoMark(modifier: Modifier = Modifier, check: Float = 1f, corner: Float = 0.24f) {
    Canvas(modifier) {
        val s = size.minDimension
        drawRoundRect(SafetyYellow, cornerRadius = CornerRadius(s * corner))
        val stroke = s * 0.075f
        drawRoundRect(
            color = Ink,
            topLeft = Offset(s * 0.27f, s * 0.25f),
            size = Size(s * 0.46f, s * 0.56f),
            cornerRadius = CornerRadius(s * 0.08f),
            style = Stroke(stroke),
        )
        drawRoundRect(
            color = Ink,
            topLeft = Offset(s * 0.39f, s * 0.17f),
            size = Size(s * 0.22f, s * 0.15f),
            cornerRadius = CornerRadius(s * 0.045f),
        )
        val tick = Path().apply {
            moveTo(s * 0.385f, s * 0.545f)
            lineTo(s * 0.47f, s * 0.63f)
            lineTo(s * 0.625f, s * 0.45f)
        }
        val measure = PathMeasure().apply { setPath(tick, false) }
        val shown = Path()
        measure.getSegment(0f, measure.length * check.coerceIn(0f, 1f), shown, true)
        drawPath(shown, Ink, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The black-and-yellow site tape. [shift] slides the stripes sideways, in stripe widths. */
@Composable
fun HazardStripes(modifier: Modifier = Modifier, stripe: Dp = 12.dp, shift: Float = 0f) {
    Canvas(modifier.clipToBounds()) {
        drawRect(SafetyYellow)
        val w = stripe.toPx()
        val h = size.height
        var x = -h - 2 * w + (shift % 2f) * w
        while (x < size.width + h) {
            val band = Path().apply {
                moveTo(x, h)
                lineTo(x + w, h)
                lineTo(x + w + h, 0f)
                lineTo(x + h, 0f)
                close()
            }
            drawPath(band, Ink)
            x += 2 * w
        }
    }
}

/** A value that breathes between two numbers for ever; used for glows, pulses and "working" text. */
@Composable
fun pulse(from: Float, to: Float, millis: Int = 900): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val value by transition.animateFloat(
        initialValue = from,
        targetValue = to,
        animationSpec = infiniteRepeatable(tween(millis), RepeatMode.Reverse),
        label = "pulse",
    )
    return value
}

/** Shows a stored photo, decoded off the main thread at roughly [maxEdge] pixels. */
@Composable
fun Photo(file: File, maxEdge: Int, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file.absolutePath, file.lastModified()) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, maxEdge)?.asImageBitmap() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
