package com.shade.panel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// The panel is a dedicated always-on display, not a regular app screen — it
// always uses this dark scheme regardless of the system theme.
private val ShadeColorScheme = darkColorScheme(
    background = PanelBackground,
    surface = PanelSurface,
    primary = PanelAccent,
    onBackground = PanelOnBackground,
    onSurface = PanelOnBackground,
    error = PanelError,
)

@Composable
fun ShadeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShadeColorScheme,
        typography = Typography,
        content = content,
    )
}
