package com.shade.panel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.shade.panel.ui.BluetoothSettingsScreen
import com.shade.panel.ui.GestureNavShell
import com.shade.panel.ui.GestureSettingsScreen
import com.shade.panel.ui.HomeScreen
import com.shade.panel.ui.theme.ShadeTheme

private enum class Screen { HOME, PANEL, CONNECTION, GESTURE_SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShadeTheme {
                // Without a Surface, LocalContentColor never gets set from the
                // theme's onBackground — icons/text quietly default to black
                // and disappear against the dark panel background.
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // No navigation library yet — just Home <-> the couple of
                    // real screens. Worth switching to Navigation-Compose once
                    // more than one of the placeholder tiles actually does
                    // something.
                    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                    when (screen) {
                        Screen.PANEL -> GestureNavShell(onExit = { screen = Screen.HOME })
                        Screen.CONNECTION -> BluetoothSettingsScreen(onBack = { screen = Screen.HOME })
                        Screen.GESTURE_SETTINGS -> GestureSettingsScreen(onBack = { screen = Screen.HOME })
                        Screen.HOME -> HomeScreen(
                            onOpenPlayer = { screen = Screen.PANEL },
                            onOpenConnection = { screen = Screen.CONNECTION },
                            onOpenGestureSettings = { screen = Screen.GESTURE_SETTINGS },
                        )
                    }
                }
            }
        }
    }
}
