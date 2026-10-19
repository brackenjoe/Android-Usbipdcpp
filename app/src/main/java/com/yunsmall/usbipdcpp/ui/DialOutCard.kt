package com.yunsmall.usbipdcpp.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yunsmall.usbipdcpp.AppSettings
import com.yunsmall.usbipdcpp.DialInKey
import com.yunsmall.usbipdcpp.DialOut
import com.yunsmall.usbipdcpp.KeyHex
import com.yunsmall.usbipdcpp.LoopStatus
import com.yunsmall.usbipdcpp.R
import com.yunsmall.usbipdcpp.notificationsBlocked
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

/**
 * The fork's settings and status card: dial-out (switch, host, port, one line
 * per loop), the loopback switch. Collapsed it is one
 * line, so the device list and the log keep their room on a small screen.
 */
@Composable
fun DialOutCard(
    settings: AppSettings.Values,
    loops: List<LoopStatus>,
    serverRunning: Boolean,
    // The listen scope the running server was started with
    loopbackInForce: Boolean,
    onDialOutEnabled: (Boolean) -> Unit,
    onHost: (String) -> Unit,
    onPort: (Int) -> Unit,
    onLoopbackOnly: (Boolean) -> Unit,
    // Fork: the dial key. Only whether it is present is shown, never the key
    keyState: DialInKey.State,
    keyProtection: DialInKey.Protection,
    onSaveKey: (String) -> DialInKey.ImportResult,
    onForgetKey: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Drafts: the port field may hold something not yet valid; only valid
    // values reach the settings
    var portText by rememberSaveable { mutableStateOf(settings.dialPort.toString()) }
    var hostText by rememberSaveable { mutableStateOf(settings.dialHost) }
    val portValid = portText.toIntOrNull()?.let { it in 1..65535 } == true
    val canEnable = hostText.isNotBlank() && portValid

    // Fork: re-read on every resume, so coming back from the permission dialog or
    // from the system's notification settings updates the warning
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsOff by remember { mutableStateOf(notificationsBlocked(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsOff = notificationsBlocked(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dialout_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (settings.dialOutEnabled && keyState != DialInKey.State.PRESENT) {
                            stringResource(R.string.dialout_no_key)
                        } else if (settings.dialOutEnabled) {
                            stringResource(R.string.dialout_summary_on, loops.count { !it.ending })
                        } else {
                            stringResource(R.string.dialout_summary_off)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.collapse else R.string.expand))
                }
            }

            // Shown collapsed too: a reconnect waiting for a fingerprint must be
            // announced, and with notifications off it silently is not
            if (settings.dialOutEnabled && notificationsOff) {
                Text(
                    stringResource(R.string.notify_blocked_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                    }
                }) { Text(stringResource(R.string.notify_settings)) }
            }

            if (expanded) {
                Column(
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingSwitch(
                        label = stringResource(R.string.dialout_enable),
                        checked = settings.dialOutEnabled,
                        // Turning it on needs a host and a valid port; turning it off always works
                        enabled = settings.dialOutEnabled || canEnable,
                        onChange = onDialOutEnabled
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = hostText,
                            onValueChange = {
                                hostText = it.trim()
                                onHost(hostText)
                            },
                            label = { Text(stringResource(R.string.dialout_host)) },
                            modifier = Modifier.weight(1f),
                            // Editing while dialing would redial on every keystroke
                            enabled = !settings.dialOutEnabled,
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { text ->
                                portText = text.filter(Char::isDigit)
                                portText.toIntOrNull()?.takeIf { it in 1..65535 }?.let(onPort)
                            },
                            label = { Text(stringResource(R.string.port)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(100.dp),
                            enabled = !settings.dialOutEnabled,
                            isError = !portValid,
                            singleLine = true
                        )
                    }
                    Text(
                        stringResource(R.string.dialout_hint),
                        style = MaterialTheme.typography.bodySmall
                    )

                    LoopList(loops = loops, enabled = settings.dialOutEnabled, serverRunning = serverRunning)

                    HorizontalDivider()
                    KeySection(keyState, keyProtection, onSaveKey, onForgetKey)

                    HorizontalDivider()
                    SettingSwitch(
                        label = stringResource(R.string.loopback_only),
                        checked = settings.loopbackOnly,
                        onChange = onLoopbackOnly
                    )
                    // Fork: the filter admits only dial-out's own connection from
                    // 127.0.0.1. With the switch off the server is open to the
                    // network, and local apps can come in over the phone's own
                    // WiFi address, which is not loopback: say so
                    Text(
                        stringResource(if (settings.loopbackOnly) R.string.loopback_on_note else R.string.loopback_off_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (settings.loopbackOnly) Color.Unspecified else MaterialTheme.colorScheme.error
                    )
                    // The server binds once, at start: say when the switch is ahead of it
                    if (serverRunning && loopbackInForce != settings.loopbackOnly) {
                        Text(
                            stringResource(R.string.loopback_restart),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/**
 * Key entry. The field is `remember`, never `rememberSaveable`: saved state is a
 * Bundle, which would be the key kept somewhere else. It is cleared the moment
 * Save is pressed. (A JVM String cannot be zeroed; the decoded bytes are, in
 * [DialInKey.importKey].)
 */
@Composable
private fun KeySection(
    keyState: DialInKey.State,
    protection: DialInKey.Protection,
    onSave: (String) -> DialInKey.ImportResult,
    onForget: () -> Unit
) {
    var keyText by remember { mutableStateOf("") }
    // The last result: its text and whether it is an error
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val valid = KeyHex.isValid(keyText)
    val protName = stringResource(
        when (protection) {
            DialInKey.Protection.STRONGBOX -> R.string.key_prot_strongbox
            DialInKey.Protection.HARDWARE -> R.string.key_prot_hardware
            DialInKey.Protection.SOFTWARE -> R.string.key_prot_software
            DialInKey.Protection.UNKNOWN -> R.string.key_prot_unknown
        }
    )

    Text(stringResource(R.string.key_title), style = MaterialTheme.typography.titleSmall)
    Text(
        when (keyState) {
            DialInKey.State.NONE -> stringResource(R.string.key_none)
            DialInKey.State.PRESENT -> stringResource(R.string.key_present, protName)
            DialInKey.State.INVALIDATED -> stringResource(R.string.key_invalidated)
            DialInKey.State.UNSUPPORTED -> stringResource(R.string.key_unsupported)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (keyState == DialInKey.State.INVALIDATED || keyState == DialInKey.State.UNSUPPORTED) {
            MaterialTheme.colorScheme.error
        } else {
            Color.Unspecified
        }
    )
    if (keyState != DialInKey.State.UNSUPPORTED) {
        OutlinedTextField(
            value = keyText,
            onValueChange = { keyText = it },
            label = { Text(stringResource(R.string.key_field)) },
            supportingText = { Text(stringResource(R.string.key_count, keyText.trim().length)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = keyText.isNotEmpty() && !valid,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = valid,
                onClick = {
                    val result = onSave(keyText)
                    keyText = ""
                    message = when (result) {
                        is DialInKey.ImportResult.Ok -> {
                            // The protection is read back after the import; name it from the result
                            val name = context.getString(
                                when (result.protection) {
                                    DialInKey.Protection.STRONGBOX -> R.string.key_prot_strongbox
                                    DialInKey.Protection.HARDWARE -> R.string.key_prot_hardware
                                    DialInKey.Protection.SOFTWARE -> R.string.key_prot_software
                                    DialInKey.Protection.UNKNOWN -> R.string.key_prot_unknown
                                }
                            )
                            context.getString(
                                if (result.strongBoxFellBack) R.string.key_saved_fallback else R.string.key_saved, name
                            ) to false
                        }
                        DialInKey.ImportResult.BadKey -> context.getString(R.string.key_bad) to true
                        DialInKey.ImportResult.Unsupported -> context.getString(R.string.key_unsupported) to true
                        is DialInKey.ImportResult.Failed -> context.getString(R.string.key_failed, result.reason) to true
                    }
                }
            ) { Text(stringResource(R.string.key_save)) }
            OutlinedButton(
                enabled = keyState != DialInKey.State.NONE,
                onClick = {
                    onForget()
                    keyText = ""
                    message = context.getString(R.string.key_forgotten) to false
                }
            ) { Text(stringResource(R.string.key_forget)) }
        }
        message?.let { (text, isError) ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified
            )
        }
        Text(stringResource(R.string.key_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun LoopList(loops: List<LoopStatus>, enabled: Boolean, serverRunning: Boolean) {
    if (loops.isEmpty()) {
        if (enabled) {
            Text(
                stringResource(if (serverRunning) R.string.dialout_no_loops else R.string.dialout_need_server),
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }
    // A clock for the "next dial in" countdown; ticks only while some loop waits
    val waiting = loops.any { it.phase == LoopStatus.Phase.WAITING || it.phase == LoopStatus.Phase.NO_VPN }
    var now by remember { mutableLongStateOf(DialOut.defaultClockMs()) }
    LaunchedEffect(waiting) {
        while (waiting) {
            now = DialOut.defaultClockMs()
            delay(1_000)
        }
    }
    val current = if (waiting) now else DialOut.defaultClockMs()
    loops.forEach { loop ->
        Column {
            val state = when (loop.phase) {
                LoopStatus.Phase.CONNECTING -> stringResource(R.string.loop_connecting)
                LoopStatus.Phase.CONNECTED -> stringResource(
                    R.string.loop_connected, formatBytes(loop.bytesIn), formatBytes(loop.bytesOut)
                )
                LoopStatus.Phase.WAITING -> stringResource(R.string.loop_waiting, loop.waitSecondsLeft(current))
                LoopStatus.Phase.LOCKED -> stringResource(R.string.loop_locked)
                LoopStatus.Phase.NO_VPN -> stringResource(R.string.loop_no_vpn, loop.waitSecondsLeft(current))
            }
            val ending = if (loop.ending) " (" + stringResource(R.string.loop_ending) + ")" else ""
            Text(
                stringResource(R.string.loop_line, loop.id, state) + ending,
                style = MaterialTheme.typography.bodyMedium
            )
            loop.lastError?.let {
                Text(
                    stringResource(R.string.loop_last_error, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KiB", n / 1024.0)
    else -> String.format(java.util.Locale.ROOT, "%.1f MiB", n / (1024.0 * 1024.0))
}
