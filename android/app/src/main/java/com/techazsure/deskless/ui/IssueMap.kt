package com.techazsure.deskless.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techazsure.deskless.AppState
import com.techazsure.deskless.Screen
import com.techazsure.deskless.capture.Geo
import com.techazsure.deskless.ui.theme.Chalk
import com.techazsure.deskless.ui.theme.Coal
import com.techazsure.deskless.ui.theme.Fog
import com.techazsure.deskless.ui.theme.GoGreen
import com.techazsure.deskless.ui.theme.Ink
import com.techazsure.deskless.ui.theme.Line
import com.techazsure.deskless.ui.theme.SafetyYellow
import com.techazsure.deskless.ui.theme.Steel
import kotlin.math.hypot
import kotlin.math.min

/**
 * Where the issues are, drawn from their own positions: no map tiles, so it works with no network. Dots
 * are numbered as in the walk and coloured by severity (green once fixed); a tap opens the issue.
 */
@Composable
fun IssueMapCard(state: AppState) {
    val located = state.issues.withIndex().filter { it.value.located }
    if (located.size < 2) return
    val points = Geo.project(located.map { it.value.lat!! to it.value.lon!! })
    val span = Geo.span(points)
    val bar = Geo.scaleBar(span)
    val measurer = rememberTextMeasurer()
    val number = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val caption = TextStyle(color = Fog, fontSize = 11.sp, fontWeight = FontWeight.Bold)

    Surface(shape = RoundedCornerShape(24.dp), color = Coal, border = BorderStroke(1.dp, Line.copy(alpha = 0.6f))) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = SafetyYellow, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                Text("WHERE THEY ARE", style = MaterialTheme.typography.labelSmall, color = Fog)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${located.size} of ${state.issues.size} located · no network used",
                    style = MaterialTheme.typography.bodySmall,
                    color = Fog,
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .pointerInput(located.map { it.value.id }) {
                        detectTapGestures { tap ->
                            val radius = 16.dp.toPx()
                            val scale = (min(size.width, size.height) - 4 * radius) / span
                            val nearest = located.indices.minByOrNull { i ->
                                hypot(size.width / 2f + points[i].east * scale - tap.x, size.height / 2f - points[i].north * scale - tap.y)
                            } ?: return@detectTapGestures
                            val x = size.width / 2f + points[nearest].east * scale
                            val y = size.height / 2f - points[nearest].north * scale
                            if (hypot(x - tap.x, y - tap.y) <= radius * 2f) {
                                state.screen = Screen.Edit(located[nearest].value.id)
                            }
                        }
                    },
            ) {
                drawRect(Steel)
                val radius = 16.dp.toPx()
                val scale = (min(size.width, size.height) - 4 * radius) / span
                val centre = Offset(size.width / 2f, size.height / 2f)

                // A grid one scale-bar apart, so distance can be read off anywhere.
                val step = bar * scale
                var x = centre.x % step
                while (x < size.width) {
                    drawLine(Line.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, size.height), 1f)
                    x += step
                }
                var y = centre.y % step
                while (y < size.height) {
                    drawLine(Line.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), 1f)
                    y += step
                }

                // The route walked, in capture order.
                val spots = points.map { Offset(centre.x + it.east * scale, centre.y - it.north * scale) }
                spots.zipWithNext().forEach { (from, to) ->
                    drawLine(SafetyYellow.copy(alpha = 0.35f), from, to, 2.dp.toPx(), StrokeCap.Round)
                }

                spots.forEachIndexed { i, spot ->
                    val issue = located[i].value
                    val colour = if (issue.resolved) GoGreen else severityColor(issue.severity)
                    // The ring is how far off the position may be.
                    val doubt = issue.accuracy * scale
                    if (doubt > radius) drawCircle(colour.copy(alpha = 0.12f), doubt, spot)
                    drawCircle(Ink, radius + 2.dp.toPx(), spot)
                    // A fixed issue is an open ring, so it is not taken for a low-severity one.
                    if (issue.resolved) {
                        drawCircle(Coal, radius, spot)
                        drawCircle(colour, radius - 1.5.dp.toPx(), spot, style = Stroke(3.dp.toPx()))
                    } else {
                        drawCircle(colour, radius, spot)
                    }
                    val label = measurer.measure("${located[i].index + 1}", if (issue.resolved) number.copy(color = GoGreen) else number)
                    drawText(label, topLeft = Offset(spot.x - label.size.width / 2f, spot.y - label.size.height / 2f))
                }

                // Scale bar, and which way is north.
                val left = 14.dp.toPx()
                val bottom = size.height - 14.dp.toPx()
                drawLine(Chalk, Offset(left, bottom), Offset(left + step, bottom), 2.dp.toPx(), StrokeCap.Round)
                drawText(measurer.measure(Geo.distance(bar), caption), topLeft = Offset(left, bottom - 20.dp.toPx()))
                val north = measurer.measure("N", caption)
                val tip = Offset(size.width - 22.dp.toPx(), 14.dp.toPx())
                drawLine(Chalk, Offset(tip.x, tip.y + 18.dp.toPx()), tip, 2.dp.toPx(), StrokeCap.Round)
                drawLine(Chalk, tip, Offset(tip.x - 5.dp.toPx(), tip.y + 7.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                drawLine(Chalk, tip, Offset(tip.x + 5.dp.toPx(), tip.y + 7.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                drawText(north, topLeft = Offset(tip.x - north.size.width / 2f, tip.y + 20.dp.toPx()))
                drawRoundRect(Line, style = Stroke(1.dp.toPx()))
            }
        }
    }
}
