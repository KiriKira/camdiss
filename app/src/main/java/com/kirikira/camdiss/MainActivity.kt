package com.kirikira.camdiss

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private var flowState by mutableStateOf(FlowState(FlowStatus.IDLE))
    private var wirelessSettingsOpenedForRun = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val newState = FlowStateStore.read(this@MainActivity)
            flowState = newState
            if (newState.status == FlowStatus.WAITING_FOR_WIRELESS && !wirelessSettingsOpenedForRun) {
                wirelessSettingsOpenedForRun = true
                openWirelessDebugging()
            }
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (requiredPermissions().all(::hasPermission)) {
            startConnectionFlow()
        } else {
            val state = FlowState(
                FlowStatus.ERROR,
                getString(R.string.permission_needed),
            )
            FlowStateStore.write(this, state)
            flowState = state
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        flowState = FlowStateStore.read(this)

        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(PairingService.ACTION_STATUS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            CamDissTheme {
                CamDissScreen(
                    state = flowState,
                    onRun = ::startFromUi,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        flowState = FlowStateStore.read(this)
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    private fun startFromUi() {
        wirelessSettingsOpenedForRun = false
        val missing = requiredPermissions().filterNot(::hasPermission)
        if (missing.isNotEmpty()) {
            permissionsLauncher.launch(missing.toTypedArray())
        } else {
            startConnectionFlow()
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startConnectionFlow() {
        if (isWirelessDebuggingEnabled()) {
            PairingService.start(this)
        } else {
            // Do not wait for mDNS to time out when Wireless debugging is clearly off.
            // Shizuku uses the same Global setting to decide whether ADB Wi-Fi is enabled.
            wirelessSettingsOpenedForRun = true
            PairingService.startWaitingForWireless(this)
            openWirelessDebugging()
        }
    }

    private fun isWirelessDebuggingEnabled(): Boolean =
        runCatching {
            Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false)

    private fun openWirelessDebugging() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        }
        runCatching { startActivity(intent) }.onFailure {
            val state = FlowState(FlowStatus.ERROR, it.message.orEmpty())
            FlowStateStore.write(this, state)
            flowState = state
        }
    }
}

@Composable
private fun CamDissTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun CamDissScreen(
    state: FlowState,
    onRun: () -> Unit,
) {
    val busy = state.status in setOf(
        FlowStatus.SEARCHING,
        FlowStatus.WAITING_FOR_WIRELESS,
        FlowStatus.CODE_REQUIRED,
        FlowStatus.PAIRING,
        FlowStatus.APPLYING,
    )

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(22.dp)),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        } else {
                            Text(
                                text = when (state.status) {
                                    FlowStatus.SUCCESS -> "✓"
                                    FlowStatus.ERROR -> "!"
                                    else -> "•"
                                },
                                style = MaterialTheme.typography.headlineMedium,
                                color = when (state.status) {
                                    FlowStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                                    FlowStatus.ERROR -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }

                        Text(
                            text = stateTitle(state.status),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )

                        val detail = when {
                            state.detail.isNotBlank() -> state.detail
                            state.status == FlowStatus.SUCCESS ->
                                androidx.compose.ui.res.stringResource(R.string.details_success)
                            else -> ""
                        }
                        if (detail.isNotBlank()) {
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        FilledTonalButton(
                            onClick = onRun,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (state.status == FlowStatus.IDLE)
                                    androidx.compose.ui.res.stringResource(R.string.action_disable)
                                else
                                    androidx.compose.ui.res.stringResource(R.string.action_run_again)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun stateTitle(status: FlowStatus): String = when (status) {
    FlowStatus.IDLE -> androidx.compose.ui.res.stringResource(R.string.status_idle)
    FlowStatus.SEARCHING -> androidx.compose.ui.res.stringResource(R.string.status_searching)
    FlowStatus.WAITING_FOR_WIRELESS -> androidx.compose.ui.res.stringResource(R.string.status_waiting_wireless)
    FlowStatus.CODE_REQUIRED -> androidx.compose.ui.res.stringResource(R.string.status_code)
    FlowStatus.PAIRING -> androidx.compose.ui.res.stringResource(R.string.status_pairing)
    FlowStatus.APPLYING -> androidx.compose.ui.res.stringResource(R.string.status_applying)
    FlowStatus.SUCCESS -> androidx.compose.ui.res.stringResource(R.string.status_success)
    FlowStatus.ERROR -> androidx.compose.ui.res.stringResource(R.string.status_error)
}
