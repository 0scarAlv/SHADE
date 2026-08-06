package com.shade.panel.data

import android.content.Context
import androidx.core.content.edit

enum class Transport { WEBSOCKET, BLUETOOTH }

enum class NavScreen { PLAYER, STATS, CLOCK }

enum class SwipeDirection { RIGHT, LEFT, UP, DOWN }

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

    var swipeRight: NavScreen?
        get() = getDirection(KEY_SWIPE_RIGHT, NavScreen.STATS)
        set(value) = setDirection(KEY_SWIPE_RIGHT, value)

    var swipeLeft: NavScreen?
        get() = getDirection(KEY_SWIPE_LEFT, NavScreen.PLAYER)
        set(value) = setDirection(KEY_SWIPE_LEFT, value)

    var swipeUp: NavScreen?
        get() = getDirection(KEY_SWIPE_UP, NavScreen.CLOCK)
        set(value) = setDirection(KEY_SWIPE_UP, value)

    // No default destination — reserved for a future screen/action slot.
    var swipeDown: NavScreen?
        get() = getDirection(KEY_SWIPE_DOWN, null)
        set(value) = setDirection(KEY_SWIPE_DOWN, value)

    fun screenFor(direction: SwipeDirection): NavScreen? = when (direction) {
        SwipeDirection.RIGHT -> swipeRight
        SwipeDirection.LEFT -> swipeLeft
        SwipeDirection.UP -> swipeUp
        SwipeDirection.DOWN -> swipeDown
    }

    // NONE is a stored sentinel distinct from "key absent" — without it there's
    // no way to represent "the user explicitly turned this direction off" once
    // a direction has a non-null factory default.
    private fun getDirection(key: String, default: NavScreen?): NavScreen? {
        val stored = prefs.getString(key, null) ?: return default
        if (stored == NONE) return null
        return runCatching { NavScreen.valueOf(stored) }.getOrDefault(default)
    }

    private fun setDirection(key: String, value: NavScreen?) = prefs.edit { putString(key, value?.name ?: NONE) }

    private companion object {
        const val KEY_TRANSPORT = "transport"
        const val KEY_DEVICE_ADDRESS = "device_address"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_SWIPE_RIGHT = "swipe_right"
        const val KEY_SWIPE_LEFT = "swipe_left"
        const val KEY_SWIPE_UP = "swipe_up"
        const val KEY_SWIPE_DOWN = "swipe_down"
        const val NONE = "NONE"
    }
}
