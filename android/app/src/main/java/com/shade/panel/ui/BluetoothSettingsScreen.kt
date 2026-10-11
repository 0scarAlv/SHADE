package com.shade.panel.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shade.panel.R
import com.shade.panel.data.ShadePreferences
import com.shade.panel.data.Transport
import com.shade.panel.ui.theme.PanelOnBackgroundMuted

@SuppressLint("MissingPermission") // gated by hasBluetoothPermission() below before any bonded-device access
@Composable
fun BluetoothSettingsScreen(viewModel: PanelViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { ShadePreferences(context) }
    val uiState by viewModel.uiState.collectAsState()
    BackHandler(onBack = onBack)

    var transport by remember { mutableStateOf(preferences.transport) }
    var selectedAddress by remember { mutableStateOf(preferences.pairedDeviceAddress) }
    var hasPermission by remember { mutableStateOf(hasBluetoothPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    // The ViewModel lives for the whole Activity; tell it to rebuild its
    // transport from the new preference instead of tearing the ViewModel
    // down (every screen keeps observing the same instance).
    fun applyTransport(newTransport: Transport) {
        transport = newTransport
        preferences.transport = newTransport
        viewModel.restartTransport()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    ScreenHeader(title = stringResource(R.string.connection_title), onBack = onBack)
                }
                ConnectionPill(uiState.connection)
            }
            Spacer(Modifier.height(28.dp))

            SectionLabel(stringResource(R.string.connection_section_transport))
            TransportOption(
                icon = Icons.Rounded.Usb,
                label = stringResource(R.string.connection_transport_usb),
                description = stringResource(R.string.connection_transport_usb_desc),
                selected = transport == Transport.WEBSOCKET,
                onClick = { if (transport != Transport.WEBSOCKET) applyTransport(Transport.WEBSOCKET) },
            )
            Spacer(Modifier.height(10.dp))
            TransportOption(
                icon = Icons.Rounded.Bluetooth,
                label = stringResource(R.string.connection_transport_bluetooth),
                description = stringResource(R.string.connection_transport_bluetooth_desc),
                selected = transport == Transport.BLUETOOTH,
                onClick = { if (transport != Transport.BLUETOOTH) applyTransport(Transport.BLUETOOTH) },
            )

            AnimatedVisibility(visible = transport == Transport.BLUETOOTH) {
                Column {
                    Spacer(Modifier.height(24.dp))
                    SectionLabel(stringResource(R.string.connection_paired_devices))
                    if (!hasPermission) {
                        ShadeCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = stringResource(R.string.connection_permission_explainer),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PanelOnBackgroundMuted,
                                )
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                ) {
                                    Text(stringResource(R.string.connection_grant_permission))
                                }
                            }
                        }
                    } else {
                        val bondedDevices = remember { bondedDevices(context) }
                        if (bondedDevices.isEmpty()) {
                            ShadeCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = stringResource(R.string.connection_no_bonded_devices),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PanelOnBackgroundMuted,
                                    modifier = Modifier.padding(18.dp),
                                )
                            }
                        } else {
                            bondedDevices.forEach { device ->
                                DeviceRow(
                                    device = device,
                                    selected = device.address == selectedAddress,
                                    onClick = {
                                        selectedAddress = device.address
                                        preferences.pairedDeviceAddress = device.address
                                        viewModel.restartTransport()
                                    },
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportOption(icon: ImageVector, label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    ShadeCard(onClick = onClick, selected = selected, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon, tint = if (selected) accent else PanelOnBackgroundMuted)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                Text(description, style = MaterialTheme.typography.bodySmall, color = PanelOnBackgroundMuted)
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
        }
    }
}

@SuppressLint("MissingPermission") // only reached once hasBluetoothPermission() passed
@Composable
private fun DeviceRow(device: BluetoothDevice, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    ShadeCard(onClick = onClick, selected = selected, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(Icons.Rounded.Computer, tint = if (selected) accent else PanelOnBackgroundMuted)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name ?: device.address, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                Text(device.address, style = MaterialTheme.typography.labelSmall, color = PanelOnBackgroundMuted)
            }
            if (selected) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.connection_selected_badge),
                    tint = accent,
                    modifier = Modifier.size(24.dp),
                )
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
