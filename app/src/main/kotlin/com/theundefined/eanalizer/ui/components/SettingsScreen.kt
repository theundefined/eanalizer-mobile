package com.theundefined.eanalizer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.theundefined.eanalizer.BuildConfig
import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.ui.EanalizerViewModel
import com.theundefined.eanalizer.ui.UiState

@Composable
fun SettingsScreen(
    state: UiState,
    viewModel: EanalizerViewModel,
    onLogin: () -> Unit,
    onBack: () -> Unit,
) {
    var email by remember(state.email) { mutableStateOf(state.email) }
    var password by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    SubScreen(stringResource(R.string.settings), onBack) {
        item {
            SectionCard(title = stringResource(R.string.account)) {
                Text(
                    stringResource(if (state.loggedIn) R.string.logged_in else R.string.logged_out),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                    modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = email.trim() != state.email || password.isNotEmpty(),
                    onClick = {
                        // An empty password field keeps the stored one unless the e-mail changed.
                        viewModel.saveCredentials(
                            email,
                            password,
                            keepPassword = password.isEmpty()
                        )
                        password = ""
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
        if (state.customers.size > 1) {
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
            SectionCard {
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
