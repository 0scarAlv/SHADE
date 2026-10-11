package com.shade.panel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The panel is a dedicated always-on display, not a regular app screen — it
// always uses this dark scheme regardless of the system theme. `accent`
// follows the current album art (see ArtPalette) so every screen, not just
// the player, picks up the color of whatever is playing.
@Composable
fun ShadeTheme(accent: Color = PanelAccent, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = PanelBackground,
            surface = PanelSurface,
            surfaceVariant = PanelSurfaceHigh,
            surfaceContainer = PanelSurface,
            surfaceContainerHigh = PanelSurfaceHigh,
            outline = PanelOutline,
            outlineVariant = PanelOutline,
            primary = accent,
            onPrimary = PanelOnAccent,
            secondary = accent,
            onBackground = PanelOnBackground,
            onSurface = PanelOnBackground,
            onSurfaceVariant = PanelOnBackgroundMuted,
            error = PanelError,
        ),
        typography = Typography,
        content = content,
    )
}
