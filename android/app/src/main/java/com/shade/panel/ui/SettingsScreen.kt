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

    HifiPage(model = "Setup", title = stringResource(R.string.settings_title), onBack = onBack) {
        EngravedGroup(stringResource(R.string.settings_section_vfd)) {
            Text(stringResource(R.string.settings_vfd_desc), style = descStyle)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VfdColor.entries.forEach { option ->
                    HwButton(
                        onClick = { settings.vfdColor = option },
                        width = 60.dp,
                        height = 40.dp,
                        led = settings.vfdColor == option,
                        ledColor = option.color,
                        caption = stringResource(option.labelRes),
                    ) {
                        Box(
                            Modifier
                                .size(width = 22.dp, height = 8.dp)
                                .background(option.color.copy(alpha = if (settings.vfdColor == option) 1f else 0.35f)),
                        )
                    }
                }
            }
        }
        EngravedGroup(stringResource(R.string.settings_section_display)) {
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
        }
        EngravedGroup(stringResource(R.string.settings_section_clock)) {
            SwitchRow(stringResource(R.string.settings_clock_seconds), stringResource(R.string.settings_clock_seconds_desc), settings.clockSeconds) {
                settings.clockSeconds = it
            }
        }
        Silk(stringResource(R.string.settings_about, versionName), color = Hifi.SilkscreenDim, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

// ------------------------------------------------------------------ shared page parts

val descStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Color(0xFF7D776E), lineHeight = 16.sp)
private val titleStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFD8D1C5))

// Faceplate page for the setup-type screens: a BACK button and the screen
// name on the brand strip, then engraved groups of controls.
@Composable
fun HifiPage(model: String, title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Faceplate(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                BrandStrip(model = model) {
                    HwButton(onClick = onBack, width = 64.dp, height = 34.dp, contentDescription = stringResource(R.string.action_back)) {
                        CapLabel("BACK", 11.sp)
                    }
                }
                VfdWindow(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                    VfdText(title.uppercase(), 22.sp)
                }
                content()
            }
        }
    }
}

// A group of controls framed by an engraved line on the faceplate, with its
// name silkscreened into the top edge.
@Composable
fun EngravedGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Box {
        Column(
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .border(1.dp, Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(1.dp)
                .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(5.dp))
                .padding(horizontal = 16.dp, vertical = 16.dp),
            content = content,
        )
        Box(Modifier.padding(start = 12.dp).background(Hifi.Faceplate).padding(horizontal = 6.dp)) {
            Silk(label)
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
        HwButton(onClick = { onCheckedChange(!checked) }, width = 60.dp, height = 36.dp, led = checked) {
            CapLabel(if (checked) "ON" else "OFF", 11.sp)
        }
    }
}

// Radio-style row: the whole set of options is a strip of buttons and the
// selected one has its LED lit.
@Composable
fun ChoiceRow(title: String, description: String?, selected: Boolean, cap: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = titleStyle)
            if (description != null) Text(description, style = descStyle)
        }
        Spacer(Modifier.width(16.dp))
        HwButton(onClick = onClick, width = 60.dp, height = 36.dp, led = selected) {
            CapLabel(cap, 11.sp)
        }
    }
}

@Composable
fun RowDivider() {
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black.copy(alpha = 0.5f)))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.03f)))
    Spacer(Modifier.height(12.dp))
}
