package com.theundefined.eanalizer.ui.components

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.theundefined.eanalizer.BuildConfig
import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.data.sync.BackgroundSync
import com.theundefined.eanalizer.ui.EanalizerViewModel
import com.theundefined.eanalizer.ui.UiState
import com.theundefined.eanalizer.ui.dateTime

@Composable
fun SettingsScreen(
    state: UiState,
    viewModel: EanalizerViewModel,
    onLogin: () -> Unit,
    onTariffs: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val autofill = LocalAutofillManager.current
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.setBackgroundSync(true)
        }
    var email by remember(state.email) { mutableStateOf(state.email) }
    var password by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    SubScreen(stringResource(R.string.settings), onBack, snackbarHostState = snackbarHostState) {
        // Local-only mode never contacts Enea: no account, customer or sync settings.
        if (!state.localOnly)
            item {
                SectionCard(title = stringResource(R.string.account)) {
                    Text(
                        stringResource(
                            if (state.loggedIn) R.string.logged_in else R.string.logged_out
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SessionInfo(state)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onLogin) {
                            Text(
                                stringResource(
                                    if (state.loggedIn) R.string.login_again else R.string.login
                                )
                            )
                        }
                        if (state.loggedIn) {
                            OutlinedButton(onClick = { viewModel.logout() }) {
                                Text(stringResource(R.string.logout))
                            }
                        }
                    }
                    MutedText(stringResource(R.string.credentials_info))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.email)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier =
                            Modifier.fillMaxWidth().semantics {
                                contentType = ContentType.Username + ContentType.EmailAddress
                            },
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.password)) },
                        placeholder = {
                            if (state.hasPassword) Text(stringResource(R.string.password_saved))
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier =
                            Modifier.fillMaxWidth().semantics {
                                contentType = ContentType.Password
                            },
                    )
                    TextButton(
                        enabled = email.trim() != state.email || password.isNotEmpty(),
                        onClick = {
                            // An empty password field keeps the stored one unless the e-mail
                            // changed.
                            viewModel.saveCredentials(
                                email,
                                password,
                                keepPassword = password.isEmpty()
                            )
                            // Offers saving the e-mail/password in the user's password manager.
                            if (password.isNotEmpty()) autofill?.commit()
                            password = ""
                        },
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        item { DataFilesCard(state, viewModel) }
        if (!state.localOnly && state.customers.size > 1) {
            item {
                SectionCard(title = stringResource(R.string.customer)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.customers.forEach { c ->
                            FilterChip(
                                selected = c.number == state.customerNumber,
                                onClick = { viewModel.chooseCustomer(c.number) },
                                label = { Text(c.number) },
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionCard(title = stringResource(R.string.billing_title)) {
                BillingSettings(state, viewModel)
            }
        }
        item {
            SectionCard(title = stringResource(R.string.settings_analysis)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.screen_tariffs)) },
                    supportingContent = { Text(stringResource(R.string.nav_tariffs_desc)) },
                    leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onTariffs),
                )
                Text(
                    stringResource(R.string.pv_installation),
                    style = MaterialTheme.typography.titleSmall,
                )
                PvProductionField(state, viewModel)
            }
        }
        if (!state.localOnly)
            item {
                SectionCard(title = stringResource(R.string.background_sync)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.background_sync_switch))
                            MutedText(stringResource(R.string.background_sync_info))
                        }
                        Switch(
                            checked = state.backgroundSync,
                            onCheckedChange = { on ->
                                if (
                                    on &&
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        !BackgroundSync.canNotify(context)
                                )
                                    notificationPermission.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                else viewModel.setBackgroundSync(on)
                            },
                        )
                    }
                    // Re-checked on every composition (the user may change it in system settings).
                    if (state.backgroundSync && !BackgroundSync.canNotify(context))
                        Text(
                            stringResource(R.string.background_sync_no_notifications),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
            }
        item {
            SectionCard {
                if (!state.localOnly)
                    OutlinedButton(
                        onClick = { viewModel.sync(force = true) },
                        enabled = state.loggedIn && !state.syncing,
                    ) {
                        Text(stringResource(R.string.force_sync))
                    }
                OutlinedButton(onClick = { confirmClear = true }) {
                    Text(
                        stringResource(R.string.clear_all),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        item {
            val createBackup =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/zip")
                ) { uri ->
                    if (uri != null) viewModel.createBackup(uri)
                }
            val restoreBackup =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) viewModel.restoreBackup(uri)
                }
            SectionCard(title = stringResource(R.string.backup)) {
                MutedText(stringResource(R.string.backup_hint))
                OutlinedButton(
                    onClick = {
                        createBackup.launch("eanalizer-backup-${java.time.LocalDate.now()}.zip")
                    }
                ) {
                    Text(stringResource(R.string.backup_create))
                }
                OutlinedButton(
                    onClick = { restoreBackup.launch(arrayOf("application/zip", "*/*")) },
                    enabled = !state.importing,
                ) {
                    Text(stringResource(R.string.backup_restore))
                }
            }
        }
        item {
            val context = LocalContext.current
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(context.getString(R.string.privacy_policy_url))
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.privacy_policy))
            }
        }
        item { MutedText(stringResource(R.string.about, BuildConfig.VERSION_NAME)) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.clear_all_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clearAll()
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** What the app knows about the eBOK session (Enea does not expose its expiry time). */
@Composable
private fun SessionInfo(state: UiState) {
    if (state.loggedIn) {
        if (state.loginAt > 0)
            MutedText(stringResource(R.string.session_login_at, dateTime(state.loginAt)))
        if (state.sessionCheckedAt > 0)
            MutedText(stringResource(R.string.session_checked_at, dateTime(state.sessionCheckedAt)))
        state.customerNumber?.let { MutedText(stringResource(R.string.session_customer, it)) }
        MutedText(stringResource(R.string.session_expiry_info))
    } else if (state.sessionCheckedAt > 0) {
        MutedText(stringResource(R.string.session_last_valid, dateTime(state.sessionCheckedAt)))
    }
}
