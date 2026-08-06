package com.shade.panel.data

import android.content.Context
import androidx.core.content.edit

enum class Transport { WEBSOCKET, BLUETOOTH }

// Which transport to use and which paired device is "the PC" — plain
// SharedPreferences is enough here, no need for DataStore for two values.
class ShadePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shade", Context.MODE_PRIVATE)

    var transport: Transport
        get() = runCatching { Transport.valueOf(prefs.getString(KEY_TRANSPORT, null) ?: "") }
            .getOrDefault(Transport.WEBSOCKET)
        set(value) = prefs.edit { putString(KEY_TRANSPORT, value.name) }

    var pairedDeviceAddress: String?
        get() = prefs.getString(KEY_DEVICE_ADDRESS, null)
        set(value) = prefs.edit { putString(KEY_DEVICE_ADDRESS, value) }

    // Panel use case: the phone usually sits mounted, so keeping the screen
    // awake is opt-in rather than forced on — see PanelScreen's toggle.
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }

    private companion object {
        const val KEY_TRANSPORT = "transport"
        const val KEY_DEVICE_ADDRESS = "device_address"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    }
}
