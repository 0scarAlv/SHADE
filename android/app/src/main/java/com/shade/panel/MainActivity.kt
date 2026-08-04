package com.shade.panel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.shade.panel.ui.PanelScreen
import com.shade.panel.ui.theme.ShadeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShadeTheme {
                // Without a Surface, LocalContentColor never gets set from the
                // theme's onBackground — icons/text quietly default to black
                // and disappear against the dark panel background.
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PanelScreen()
                }
            }
        }
    }
}
