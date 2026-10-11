package com.shade.panel.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.shade.panel.data.ShadePreferences

// Observable view over the display-related preferences. Several screens
// toggle the same values (keep-screen-on lives both in the player's top bar
// and the settings screen) and MainActivity applies them to the window, so
// they need to be Compose state rather than plain SharedPreferences reads.
@Stable
class AppSettings(private val preferences: ShadePreferences) {
    private var keepScreenOnState by mutableStateOf(preferences.keepScreenOn)
    private var immersiveState by mutableStateOf(preferences.immersive)
    private var clockSecondsState by mutableStateOf(preferences.clockSeconds)
    private var burnInState by mutableStateOf(preferences.burnInProtection)
    private var inkColorState by mutableStateOf(InkColor.fromName(preferences.inkColor))
    private var lyricsOffsetState by mutableStateOf(preferences.lyricsOffsetMs)

    var keepScreenOn: Boolean
        get() = keepScreenOnState
        set(value) {
            keepScreenOnState = value
            preferences.keepScreenOn = value
        }

    var immersive: Boolean
        get() = immersiveState
        set(value) {
            immersiveState = value
            preferences.immersive = value
        }

    var clockSeconds: Boolean
        get() = clockSecondsState
        set(value) {
            clockSecondsState = value
            preferences.clockSeconds = value
        }

    var inkColor: InkColor
        get() = inkColorState
        set(value) {
            inkColorState = value
            preferences.inkColor = value.name
        }

    var lyricsOffsetMs: Int
        get() = lyricsOffsetState
        set(value) {
            lyricsOffsetState = value
            preferences.lyricsOffsetMs = value
        }

    var burnInProtection: Boolean
        get() = burnInState
        set(value) {
            burnInState = value
            preferences.burnInProtection = value
        }
}

val LocalAppSettings = staticCompositionLocalOf<AppSettings> { error("AppSettings not provided") }
