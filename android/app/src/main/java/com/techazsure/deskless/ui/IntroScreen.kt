package com.techazsure.deskless.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techazsure.deskless.ui.theme.Chalk
import com.techazsure.deskless.ui.theme.Ink
import com.techazsure.deskless.ui.theme.SafetyYellow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The opening seconds. Android's own launch screen leaves the round app icon in the middle of the screen;
 * this picks it up from there, squares it into the DeskLess tile as it rises, then lands the three-line
 * promise from the video a line at a time while the site tape runs along the bottom. A tap skips it.
 */
@Composable
fun IntroScreen(onFinished: () -> Unit) {
    val rise = remember { Animatable(0f) }
    val name = remember { Animatable(0f) }
    val lines = remember { List(3) { Animatable(0f) } }
    val badge = remember { Animatable(0f) }
    val leave = remember { Animatable(0f) }
    val tape by rememberInfiniteTransition(label = "tape").animateFloat(
        initialValue = 0f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "tape",
    )
    var screenHeight by remember { mutableFloatStateOf(0f) }
    var slotCentre by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        delay(120)
        rise.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 170f))
        launch { name.animateTo(1f, tween(280)) }
        lines.forEach {
            launch { it.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
            delay(200)
        }
        badge.animateTo(1f, tween(260))
        delay(700)
        leave.animateTo(1f, tween(240))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .onSizeChanged { screenHeight = it.height.toFloat() }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onFinished() }
            .graphicsLayer { alpha = 1f - leave.value },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The tile lands here; it is drawn separately below so it can travel from the screen centre.
            Spacer(
                Modifier
                    .size(TILE)
                    .onGloballyPositioned { slotCentre = it.positionInRoot().y + it.size.height / 2f }
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "DeskLess",
                style = MaterialTheme.typography.displaySmall,
                color = Chalk,
                modifier = Modifier.graphicsLayer {
                    alpha = name.value
                    translationY = (1f - name.value) * 12.dp.toPx()
                },
            )
            Spacer(Modifier.height(30.dp))
            listOf("SNAP IT." to Chalk, "SAY IT." to Chalk, "REPORT DONE." to SafetyYellow).forEachIndexed { i, (text, color) ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.displayLarge,
                    color = color,
                    modifier = Modifier.graphicsLayer {
                        alpha = lines[i].value
                        translationY = (1f - lines[i].value) * 30.dp.toPx()
                    },
                )
            }
            Spacer(Modifier.height(26.dp))
            Text(
                text = "WORKS OFFLINE  •  ON-DEVICE AI",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = Ink,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = badge.value
                        scaleX = 0.9f + 0.1f * badge.value
                        scaleY = 0.9f + 0.1f * badge.value
                    }
                    .background(SafetyYellow, RoundedCornerShape(4.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        LogoMark(
            modifier = Modifier
                .align(Alignment.Center)
                .size(TILE)
                .graphicsLayer {
                    val travel = if (screenHeight > 0f && slotCentre > 0f) slotCentre - screenHeight / 2f else 0f
                    translationY = travel * rise.value
                    val scale = LAUNCH_ICON_SCALE + (1f - LAUNCH_ICON_SCALE) * rise.value
                    scaleX = scale
                    scaleY = scale
                },
            corner = 0.5f + (0.24f - 0.5f) * rise.value.coerceIn(0f, 1f),
        )

        HazardStripes(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
                .height(18.dp)
                .graphicsLayer { alpha = rise.value.coerceIn(0f, 1f) },
            shift = tape,
        )
    }
}

private val TILE = 76.dp

/** How much bigger Android's launch icon is than the tile, so the hand-over is seamless. */
private const val LAUNCH_ICON_SCALE = 1.75f
