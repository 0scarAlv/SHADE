package com.shade.panel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shade.panel.data.NavScreen
import com.shade.panel.data.ShadePreferences
import com.shade.panel.music.MusicViewModel
import com.shade.panel.ui.AppSettings
import com.shade.panel.ui.BluetoothSettingsScreen
import com.shade.panel.ui.GestureNavShell
import com.shade.panel.ui.GestureSettingsScreen
import com.shade.panel.ui.HomeScreen
import com.shade.panel.ui.LocalAppSettings
import com.shade.panel.ui.LocalArtColors
import com.shade.panel.ui.InkColor
import com.shade.panel.ui.LocalInk
import com.shade.panel.ui.MusicScreen
import com.shade.panel.ui.PanelViewModel
import com.shade.panel.ui.SettingsScreen
import com.shade.panel.ui.panelViewModelFactory
import com.shade.panel.ui.rememberArtPalette
import com.shade.panel.ui.theme.ShadeTheme

// MUSIC (the phone's own player) is where the app opens; HOME and everything
// reached from it make up the deck — the PC panel side.
private enum class Screen { MUSIC, HOME, PANEL, CONNECTION, GESTURE_SETTINGS, SETTINGS }

class MainActivity : ComponentActivity() {

    // Mirrors AppSettings.immersive so onWindowFocusChanged can re-hide the
    // system bars after a dialog/permission prompt or app switch shows them.
    private var immersiveEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val settings = remember { AppSettings(ShadePreferences(context)) }
            // One ViewModel (and one agent connection) for the whole app, so
            // Home can show live data and every screen shares the same state.
            val viewModel: PanelViewModel = viewModel(factory = panelViewModelFactory(context))
            val uiState by viewModel.uiState.collectAsState()
            val musicViewModel: MusicViewModel = viewModel()

            // Most screens use the fixed accent ink (LocalInk); the clock
            // keeps its accent from the current cover, animated between
            // palettes so a track change washes the color over.
            val palette = rememberArtPalette(uiState.art)
            val accent by animateColorAsState(palette.accent, tween(900), label = "accent")
            val second by animateColorAsState(palette.second, tween(900), label = "second")
            val third by animateColorAsState(palette.third, tween(900), label = "third")
            val fourth by animateColorAsState(palette.fourth, tween(900), label = "fourth")
            val ink = if (settings.inkColor == InkColor.AUTO) accent else settings.inkColor.color

            val view = LocalView.current
            DisposableEffect(settings.keepScreenOn) {
                view.keepScreenOn = settings.keepScreenOn
                onDispose { view.keepScreenOn = false }
            }
            LaunchedEffect(settings.lyricsOffsetMs) { viewModel.lyricsOffsetMs = settings.lyricsOffsetMs }
            LaunchedEffect(settings.immersive) {
                immersiveEnabled = settings.immersive
                applyImmersive(settings.immersive)
            }

            CompositionLocalProvider(
                LocalAppSettings provides settings,
                LocalInk provides ink,
                LocalArtColors provides listOf(ink, second, third, fourth),
            ) {
                ShadeTheme(accent = accent) {
                    // Without a Surface, LocalContentColor never gets set from the
                    // theme's onBackground — icons/text quietly default to black
                    // and disappear against the dark panel background.
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        // Still no navigation library — a flat enum + AnimatedContent
                        // covers this handful of screens with less ceremony.
                        var screen by rememberSaveable { mutableStateOf(Screen.MUSIC) }
                        var panelStart by rememberSaveable { mutableStateOf(NavScreen.PLAYER) }
                        val goHome = { screen = Screen.HOME }
                        // Only the device making sound records listens, so the
                        // phone's own music stops when it turns into the deck.
                        val goDeck = {
                            musicViewModel.pause()
                            screen = Screen.HOME
                        }
                        val goMusic = { screen = Screen.MUSIC }
                        BackHandler(enabled = screen == Screen.HOME, onBack = goMusic)

                        AnimatedContent(
                            targetState = screen,
                            transitionSpec = {
                                (fadeIn(tween(280)) + scaleIn(tween(280), initialScale = 0.97f))
                                    .togetherWith(fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 1.02f))
                            },
                            label = "screen",
                        ) { target ->
                            when (target) {
                                Screen.PANEL -> GestureNavShell(viewModel = viewModel, initialScreen = panelStart, onExit = goHome)
                                Screen.CONNECTION -> BluetoothSettingsScreen(viewModel = viewModel, onBack = goHome)
                                Screen.GESTURE_SETTINGS -> GestureSettingsScreen(onBack = goHome)
                                Screen.SETTINGS -> SettingsScreen(onBack = goHome)
                                Screen.MUSIC -> MusicScreen(vm = musicViewModel, onOpenDeck = goDeck)
                                Screen.HOME -> HomeScreen(
                                    viewModel = viewModel,
                                    onOpenPanel = { start ->
                                        panelStart = start
                                        screen = Screen.PANEL
                                    },
                                    onOpenConnection = { screen = Screen.CONNECTION },
                                    onOpenGestureSettings = { screen = Screen.GESTURE_SETTINGS },
                                    onOpenSettings = { screen = Screen.SETTINGS },
                                    onOpenMusic = goMusic,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive(immersiveEnabled)
    }

    private fun applyImmersive(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
