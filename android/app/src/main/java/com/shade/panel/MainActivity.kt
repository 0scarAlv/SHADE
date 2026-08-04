package com.shade.panel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.shade.panel.ui.PanelScreen
import com.shade.panel.ui.theme.ShadeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShadeTheme {
                PanelScreen()
            }
        }
    }
}
