package com.techazsure.deskless

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import com.techazsure.deskless.capture.Locator
import com.techazsure.deskless.data.IssueStore
import com.techazsure.deskless.models.ModelStore
import com.techazsure.deskless.report.ReportLibrary
import com.techazsure.deskless.ui.ReportsScreen
import com.techazsure.deskless.ui.CaptureScreen
import com.techazsure.deskless.ui.EditScreen
import com.techazsure.deskless.ui.HomeScreen
import com.techazsure.deskless.ui.IntroScreen
import com.techazsure.deskless.ui.ModelsScreen
import com.techazsure.deskless.ui.ReportScreen
import com.techazsure.deskless.ui.theme.DesklessTheme
import com.techazsure.deskless.ui.theme.Ink
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark in every light, so the system bars always carry light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val state = AppState(
            IssueStore(applicationContext),
            ModelStore(applicationContext),
            ReportLibrary(applicationContext),
            Locator(applicationContext),
            cacheDir,
        )
        setContent {
            DesklessTheme {
                DeskLessApp(state, lifecycleOwner = this)
            }
        }
    }
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

    var introDone by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
    ) {
        AnimatedContent(
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
                Screen.Reports -> ReportsScreen(state)
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
    Screen.Reports -> "reports"
    is Screen.Edit -> "edit-${screen.id}"
    is Screen.Report -> "report-${screen.path}"
}

private fun depthOf(screen: Screen): Int = when (screen) {
    Screen.Home -> 0
    Screen.Models, Screen.Reports -> 1
    // A retake is opened from an issue, so it sits above it; a new capture sits below.
    is Screen.Capture -> if (screen.retakeId != null) 3 else 1
    is Screen.Edit -> 2
    is Screen.Report -> 2
}
