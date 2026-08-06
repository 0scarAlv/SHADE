package com.shade.panel.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.shade.panel.R
import com.shade.panel.data.ShadePreferences
import com.shade.panel.data.Transport
import com.shade.panel.ui.theme.PanelAccent
import com.shade.panel.ui.theme.PanelOnBackgroundMuted
import com.shade.panel.ui.theme.PanelSurface

@SuppressLint("MissingPermission") // gated by hasBluetoothPermission() below before any bonded-device access
@Composable
fun BluetoothSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { ShadePreferences(context) }
    // PanelViewModel (and the transport it opened) is cached for the whole
    // Activity — otherwise reopening the player wouldn't pick up a changed
    // setting at all. This app only ever puts that one ViewModel in this
    // store, so clearing it here is exactly "forget the old connection,
    // build a fresh one next time the player opens."
    val viewModelStoreOwner = LocalViewModelStoreOwner.current

    var transport by remember { mutableStateOf(preferences.transport) }
    var selectedAddress by remember { mutableStateOf(preferences.pairedDeviceAddress) }
    var hasPermission by remember { mutableStateOf(hasBluetoothPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.connection_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(24.dp))

        TransportOption(
            label = stringResource(R.string.connection_transport_usb),
            selected = transport == Transport.WEBSOCKET,
            onClick = {
                transport = Transport.WEBSOCKET
                preferences.transport = Transport.WEBSOCKET
                viewModelStoreOwner?.viewModelStore?.clear()
            },
        )
        Spacer(Modifier.height(12.dp))
        TransportOption(
            label = stringResource(R.string.connection_transport_bluetooth),
            selected = transport == Transport.BLUETOOTH,
            onClick = {
                transport = Transport.BLUETOOTH
                preferences.transport = Transport.BLUETOOTH
                viewModelStoreOwner?.viewModelStore?.clear()
            },
        )

        if (transport == Transport.BLUETOOTH) {
            Spacer(Modifier.height(24.dp))
            if (!hasPermission) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                    Text(stringResource(R.string.connection_grant_permission))
                }
            } else {
                val bondedDevices = remember { bondedDevices(context) }
                Text(
                    text = stringResource(R.string.connection_paired_devices),
                    style = MaterialTheme.typography.labelLarge,
                    color = PanelOnBackgroundMuted,
                )
                Spacer(Modifier.height(8.dp))
                if (bondedDevices.isEmpty()) {
                    Text(
                        text = stringResource(R.string.connection_no_bonded_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PanelOnBackgroundMuted,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(bondedDevices, key = { it.address }) { device ->
                            DeviceRow(
                                device = device,
                                selected = device.address == selectedAddress,
                                onClick = {
                                    selectedAddress = device.address
                                    preferences.pairedDeviceAddress = device.address
                                    viewModelStoreOwner?.viewModelStore?.clear()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (selected) PanelAccent.copy(alpha = 0.15f) else PanelSurface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = MaterialTheme.colorScheme.onBackground)
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = PanelAccent)
        }
    }
}

@Composable
private fun DeviceRow(device: BluetoothDevice, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (selected) PanelAccent.copy(alpha = 0.15f) else PanelSurface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = device.name ?: device.address, color = MaterialTheme.colorScheme.onBackground)
                Text(text = device.address, style = MaterialTheme.typography.labelSmall, color = PanelOnBackgroundMuted)
            }
            if (selected) {
                Box {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.connection_selected_badge), tint = PanelAccent)
                }
            }
        }
    }
}

private fun hasBluetoothPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        true // legacy BLUETOOTH/BLUETOOTH_ADMIN are install-time "normal" permissions
    }

@SuppressLint("MissingPermission") // caller already checked hasBluetoothPermission()
private fun bondedDevices(context: Context): List<BluetoothDevice> {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    return adapter?.bondedDevices?.toList().orEmpty()
}
