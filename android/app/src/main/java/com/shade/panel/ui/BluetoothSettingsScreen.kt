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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shade.panel.R
import com.shade.panel.data.ConnectionState
import com.shade.panel.data.ShadePreferences
import com.shade.panel.data.Transport

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

    HifiPage(model = "Link", title = stringResource(R.string.connection_title), onBack = onBack) {
        VfdWindow(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VfdText(if (transport == Transport.BLUETOOTH) "BLUETOOTH" else "USB", 16.sp)
                Spacer(Modifier.weight(1f))
                VfdText(
                    when (uiState.connection) {
                        ConnectionState.CONNECTED -> "LINKED"
                        ConnectionState.CONNECTING -> "SEARCHING"
                        ConnectionState.DISCONNECTED -> "NO LINK"
                    },
                    16.sp,
                    lit = uiState.connection != ConnectionState.DISCONNECTED,
                )
            }
        }

        EngravedGroup(stringResource(R.string.connection_section_transport)) {
            ChoiceRow(
                title = stringResource(R.string.connection_transport_usb),
                description = stringResource(R.string.connection_transport_usb_desc),
                selected = transport == Transport.WEBSOCKET,
                cap = "USB",
                onClick = { if (transport != Transport.WEBSOCKET) applyTransport(Transport.WEBSOCKET) },
            )
            RowDivider()
            ChoiceRow(
                title = stringResource(R.string.connection_transport_bluetooth),
                description = stringResource(R.string.connection_transport_bluetooth_desc),
                selected = transport == Transport.BLUETOOTH,
                cap = "BT",
                onClick = { if (transport != Transport.BLUETOOTH) applyTransport(Transport.BLUETOOTH) },
            )
        }

        AnimatedVisibility(visible = transport == Transport.BLUETOOTH) {
            EngravedGroup(stringResource(R.string.connection_paired_devices)) {
                if (!hasPermission) {
                    Text(stringResource(R.string.connection_permission_explainer), style = descStyle)
                    Spacer(Modifier.height(12.dp))
                    HwButton(
                        onClick = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                        width = 120.dp,
                        height = 38.dp,
                    ) { CapLabel(stringResource(R.string.connection_grant_permission).uppercase(), 10.sp) }
                } else {
                    val bondedDevices = remember { bondedDevices(context) }
                    if (bondedDevices.isEmpty()) {
                        Text(stringResource(R.string.connection_no_bonded_devices), style = descStyle)
                    } else {
                        bondedDevices.forEachIndexed { index, device ->
                            if (index > 0) RowDivider()
                            ChoiceRow(
                                title = device.name ?: device.address,
                                description = device.address,
                                selected = device.address == selectedAddress,
                                cap = "PC",
                                onClick = {
                                    selectedAddress = device.address
                                    preferences.pairedDeviceAddress = device.address
                                    viewModel.restartTransport()
                                },
                            )
                        }
                    }
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
