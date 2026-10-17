package com.shade.panel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shade.panel.R
import com.shade.panel.ui.theme.Manrope

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = LocalAppSettings.current
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    BackHandler(onBack = onBack)

    SubPage(model = "Setup", title = stringResource(R.string.settings_title), onBack = onBack) {
        Section(stringResource(R.string.settings_section_vfd)) {
            Text(stringResource(R.string.settings_vfd_desc), style = descStyle)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkColor.entries.forEach { option ->
                    KeyButton(
                        onClick = { settings.inkColor = option },
                        width = 60.dp,
                        height = 40.dp,
                        active = settings.inkColor == option,
                        activeColor = option.color,
                        caption = stringResource(option.labelRes),
                    ) {
                        if (option == InkColor.AUTO) {
                            CapLabel("AUTO", 10.sp)
                        } else {
                            Box(
                                Modifier
                                    .size(width = 22.dp, height = 8.dp)
                                    .background(option.color.copy(alpha = if (settings.inkColor == option) 1f else 0.35f)),
                            )
                        }
                    }
                }
            }
        }
        Section(stringResource(R.string.settings_section_lyrics)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_lyrics_offset), style = titleStyle)
                    Text(stringResource(R.string.settings_lyrics_offset_desc), style = descStyle)
                }
                Spacer(Modifier.width(12.dp))
                KeyButton(onClick = { settings.lyricsOffsetMs = (settings.lyricsOffsetMs - LYRICS_STEP_MS).coerceAtLeast(-LYRICS_MAX_MS) }, width = 44.dp, height = 36.dp) {
                    CapLabel("−", 16.sp)
                }
                Readout(
                    "%+.2f".format(java.util.Locale.US, settings.lyricsOffsetMs / 1000f),
                    18.sp,
                    template = "+0.00",
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                KeyButton(onClick = { settings.lyricsOffsetMs = (settings.lyricsOffsetMs + LYRICS_STEP_MS).coerceAtMost(LYRICS_MAX_MS) }, width = 44.dp, height = 36.dp) {
                    CapLabel("+", 16.sp)
                }
            }
        }
        Section(stringResource(R.string.settings_section_display)) {
            SwitchRow(stringResource(R.string.settings_keep_screen_on), stringResource(R.string.settings_keep_screen_on_desc), settings.keepScreenOn) {
                settings.keepScreenOn = it
            }
            RowDivider()
            SwitchRow(stringResource(R.string.settings_immersive), stringResource(R.string.settings_immersive_desc), settings.immersive) {
                settings.immersive = it
            }
            RowDivider()
            SwitchRow(stringResource(R.string.settings_burn_in), stringResource(R.string.settings_burn_in_desc), settings.burnInProtection) {
                settings.burnInProtection = it
            }
            RowDivider()
            SwitchRow(stringResource(R.string.settings_cyberpunk), stringResource(R.string.settings_cyberpunk_desc), settings.cyberpunk) {
                settings.cyberpunk = it
            }
        }
        Section(stringResource(R.string.settings_section_clock)) {
            SwitchRow(stringResource(R.string.settings_clock_seconds), stringResource(R.string.settings_clock_seconds_desc), settings.clockSeconds) {
                settings.clockSeconds = it
            }
        }
        Caption(stringResource(R.string.settings_about, versionName), color = Ink.TextSubtle, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

// ------------------------------------------------------------------ shared page parts

private const val LYRICS_STEP_MS = 250
private const val LYRICS_MAX_MS = 3_000

val descStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Ink.TextMuted, lineHeight = 16.sp)
private val titleStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Ink.Text)

// ScreenBase page for the setup-type screens: a BACK button and the screen
// name on the top strip, then titled groups of controls.
@Composable
fun SubPage(model: String, title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ScreenBase(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                TopStrip(model = model) {
                    KeyButton(onClick = onBack, width = 64.dp, height = 34.dp, contentDescription = stringResource(R.string.action_back)) {
                        CapLabel("BACK", 11.sp)
                    }
                }
                DotText(title.uppercase(), 28.sp, weight = FontWeight.Black)
                content()
            }
        }
    }
}

// A titled group of controls: caption above, flat panel below.
@Composable
fun Section(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Caption(label, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        DisplayPanel(Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable
fun SwitchRow(title: String, description: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = titleStyle)
            if (description != null) Text(description, style = descStyle)
        }
        Spacer(Modifier.width(16.dp))
        KeyButton(onClick = { onCheckedChange(!checked) }, width = 60.dp, height = 36.dp, active = checked) {
            CapLabel(if (checked) "ON" else "OFF", 11.sp)
        }
    }
}

// Radio-style row: the whole set of options is a strip of buttons and the
// selected one is shown active.
@Composable
fun ChoiceRow(title: String, description: String?, selected: Boolean, cap: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = titleStyle)
            if (description != null) Text(description, style = descStyle)
        }
        Spacer(Modifier.width(16.dp))
        KeyButton(onClick = onClick, width = 60.dp, height = 36.dp, active = selected) {
            CapLabel(cap, 11.sp)
        }
    }
}

@Composable
fun RowDivider() {
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.PanelBorder))
    Spacer(Modifier.height(12.dp))
}
