package com.shade.panel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shade.panel.R
import com.shade.panel.ui.theme.PanelOnBackgroundSubtle

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = LocalAppSettings.current
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            ScreenHeader(title = stringResource(R.string.settings_title), onBack = onBack)
            Spacer(Modifier.height(28.dp))

            SectionLabel(stringResource(R.string.settings_section_display))
            ToggleRow(
                icon = Icons.Rounded.LightMode,
                title = stringResource(R.string.settings_keep_screen_on),
                subtitle = stringResource(R.string.settings_keep_screen_on_desc),
                checked = settings.keepScreenOn,
                onCheckedChange = { settings.keepScreenOn = it },
            )
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                icon = Icons.Rounded.Fullscreen,
                title = stringResource(R.string.settings_immersive),
                subtitle = stringResource(R.string.settings_immersive_desc),
                checked = settings.immersive,
                onCheckedChange = { settings.immersive = it },
            )
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                icon = Icons.Rounded.DarkMode,
                title = stringResource(R.string.settings_burn_in),
                subtitle = stringResource(R.string.settings_burn_in_desc),
                checked = settings.burnInProtection,
                onCheckedChange = { settings.burnInProtection = it },
            )

            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.settings_section_clock))
            ToggleRow(
                icon = Icons.Rounded.Timer,
                title = stringResource(R.string.settings_clock_seconds),
                subtitle = stringResource(R.string.settings_clock_seconds_desc),
                checked = settings.clockSeconds,
                onCheckedChange = { settings.clockSeconds = it },
            )

            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.settings_about, versionName),
                style = MaterialTheme.typography.labelMedium,
                color = PanelOnBackgroundSubtle,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
