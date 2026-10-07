package com.theundefined.eanalizer.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SolarPower
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.ui.EanalizerViewModel
import com.theundefined.eanalizer.ui.EanalizerViewModel.ExportKind
import com.theundefined.eanalizer.ui.EanalizerViewModel.UiEvent
import com.theundefined.eanalizer.ui.ErrorKind
import com.theundefined.eanalizer.ui.UiState
import com.theundefined.eanalizer.ui.XLSX_MIME
import com.theundefined.eanalizer.ui.dateTime
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: EanalizerViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var currentScreen by rememberSaveable { mutableStateOf("main") }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is UiEvent.SyncFinished ->
                    snackbarHostState.showSnackbar(
                        if (event.downloadedYears > 0)
                            resources.getQuantityString(
                                R.plurals.sync_done,
                                event.downloadedYears,
                                event.downloadedYears,
                            )
                        else resources.getString(R.string.sync_up_to_date)
                    )
                is UiEvent.Saved ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.export_saved))
                is UiEvent.LoginRequired -> currentScreen = "login"
                is UiEvent.Error ->
                    snackbarHostState.showSnackbar(
                        when (event.kind) {
                            ErrorKind.SESSION_EXPIRED -> resources.getString(R.string.error_session)
                            ErrorKind.NETWORK -> resources.getString(R.string.error_network)
                            ErrorKind.PROTOCOL ->
                                resources.getString(R.string.error_protocol, event.detail ?: "")
                            ErrorKind.NO_METER_DATA ->
                                resources.getString(R.string.error_no_meter_data)
                            ErrorKind.PRICES ->
                                resources.getString(R.string.error_prices, event.detail ?: "")
                            ErrorKind.UNKNOWN ->
                                resources.getString(R.string.error_generic, event.detail ?: "")
                        }
                    )
                is UiEvent.Share -> {
                    val uri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            event.file,
                        )
                    val send =
                        Intent(Intent.ACTION_SEND).apply {
                            type = event.mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(
                        Intent.createChooser(send, resources.getString(R.string.share_title))
                    )
                }
            }
        }
    }

    // Tariffs are opened from Settings, so back returns there.
    val back = { currentScreen = if (currentScreen == "tariffs") "settings" else "main" }
    BackHandler(enabled = currentScreen != "main") { back() }

    state.customerChoice?.let { customers ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissCustomerChoice() },
            title = { Text(stringResource(R.string.choose_customer)) },
            text = {
                Column {
                    MutedText(stringResource(R.string.choose_customer_info))
                    customers.forEach { c ->
                        TextButton(onClick = { viewModel.chooseCustomer(c.number) }) {
                            Text(c.number)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCustomerChoice() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    when (currentScreen) {
        "login" -> {
            EneaLoginScreen(
                viewModel = viewModel,
                onLoggedIn = {
                    currentScreen = "main"
                    viewModel.onWebLoginFinished()
                },
                onBack = back,
            )
            return
        }
        "settings" -> {
            SettingsScreen(
                state = state,
                viewModel = viewModel,
                onLogin = { currentScreen = "login" },
                onTariffs = { currentScreen = "tariffs" },
                onBack = back,
            )
            return
        }
        "tariffs" -> {
            TariffsScreen(state = state, viewModel = viewModel, onBack = back)
            return
        }
        "compare" -> {
            CompareScreen(state = state, viewModel = viewModel, onBack = back)
            return
        }
        "bills" -> {
            BillsScreen(state = state, onBack = back)
            return
        }
        "storage" -> {
            StorageScreen(state = state, viewModel = viewModel, onBack = back)
            return
        }
        "yoy" -> {
            YearOverYearScreen(state = state, onBack = back)
            return
        }
        "profile" -> {
            ProfileScreen(state = state, onBack = back)
            return
        }
        "selfuse" -> {
            SelfUseScreen(state = state, viewModel = viewModel, onBack = back)
            return
        }
        "rce" -> {
            RceScreen(state = state, onLoad = { viewModel.loadRce() }, onBack = back)
            return
        }
        "monthly" -> {
            MonthlyScreen(state = state, onBack = back)
            return
        }
        "data" -> {
            DataScreen(state = state, onBack = back)
            return
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (state.hasData) ExportMenu(state, viewModel)
                    IconButton(onClick = { viewModel.sync() }, enabled = !state.syncing) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                    }
                    IconButton(onClick = { currentScreen = "settings" }) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.syncing,
            onRefresh = { viewModel.sync() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    StatusCard(
                        state,
                        onLogin = { currentScreen = "login" },
                        onSync = { viewModel.sync() },
                        onRefreshPrices = { viewModel.refreshPrices() },
                    )
                }
                if (state.hasData) {
                    item { ParamsCard(state = state, onChange = { viewModel.updatePrefs(it) }) }
                    item {
                        SummaryCard(
                            state,
                            onRetryPrices = { viewModel.refreshPrices() },
                            onOpenStorage = { currentScreen = "storage" },
                        )
                    }
                    NAV_GROUPS.forEach { group ->
                        item(key = group.title) { NavGroup(group) { currentScreen = it } }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: UiState,
    onLogin: () -> Unit,
    onSync: () -> Unit,
    onRefreshPrices: () -> Unit,
) {
    if (state.loadingLocal) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        return
    }
    if (!state.hasData) {
        SectionCard(title = stringResource(R.string.welcome_title)) {
            Text(stringResource(R.string.welcome_text))
            if (state.syncing) SyncProgress(state)
            else Button(onClick = onLogin) { Text(stringResource(R.string.login)) }
        }
        return
    }
    SectionCard {
        Text(
            stringResource(
                R.string.data_range,
                state.dataStart.toString(),
                state.dataEnd.toString()
            ),
            style = MaterialTheme.typography.titleSmall,
        )
        state.customerNumber?.let { MutedText(stringResource(R.string.customer_label, it)) }
        RefreshRow(
            text =
                stringResource(
                    R.string.last_sync,
                    if (state.lastSync > 0) dateTime(state.lastSync)
                    else stringResource(R.string.never),
                ),
            description = stringResource(R.string.refresh_enea),
            enabled = !state.syncing,
            onClick = onSync,
        )
        RefreshRow(
            text =
                if (state.pricesRefreshing) stringResource(R.string.prices_refreshing)
                else
                    stringResource(
                        R.string.prices_fetched,
                        if (state.pricesFetchedAt > 0) dateTime(state.pricesFetchedAt)
                        else stringResource(R.string.never),
                    ),
            description = stringResource(R.string.refresh_prices),
            enabled = !state.pricesRefreshing,
            onClick = onRefreshPrices,
        )
        if (state.pricesRefreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (state.syncing) SyncProgress(state)
        else if (!state.loggedIn) {
            Text(
                stringResource(R.string.session_expired_card),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onLogin) { Text(stringResource(R.string.login_again)) }
        }
    }
}

/** Muted status line with a small refresh button on the right. */
@Composable
private fun RefreshRow(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MutedText(text, modifier = Modifier.weight(1f))
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Refresh, description, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SyncProgress(state: UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MutedText(
            state.syncYear?.let { stringResource(R.string.syncing_year, it) }
                ?: stringResource(R.string.syncing)
        )
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** Export menu; each export can be shared (e.g. to Drive/Sheets) or saved to a file. */
@Composable
private fun ExportMenu(state: UiState, viewModel: EanalizerViewModel) {
    var open by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<ExportKind?>(null) }
    var saving by remember { mutableStateOf<ExportKind?>(null) }
    fun onSaveResult(uri: Uri?) {
        val kind = saving
        saving = null
        if (uri != null && kind != null) viewModel.export(kind, uri)
    }
    val saveCsv = rememberLauncherForActivityResult(CreateDocument("text/csv")) { onSaveResult(it) }
    val saveXlsx = rememberLauncherForActivityResult(CreateDocument(XLSX_MIME)) { onSaveResult(it) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Share, stringResource(R.string.export))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val items =
                listOf(
                    ExportKind.WORKBOOK to R.string.export_workbook,
                    ExportKind.HOURLY to R.string.export_hourly,
                ) +
                    if (state.analysis == null) emptyList()
                    else
                        listOf(
                            ExportKind.SIMULATION to R.string.export_simulation,
                            ExportKind.DAILY to R.string.export_daily,
                            ExportKind.MONTHLY to R.string.export_monthly,
                        )
            items.forEachIndexed { i, (kind, label) ->
                if (i == 2) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        open = false
                        chosen = kind
                    },
                )
            }
        }
    }
    chosen?.let { kind ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(stringResource(R.string.export)) },
            text = { Text(viewModel.exportName(kind) ?: "") },
            confirmButton = {
                TextButton(
                    onClick = {
                        chosen = null
                        viewModel.export(kind)
                    }
                ) {
                    Text(stringResource(R.string.export_share))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        chosen = null
                        val name = viewModel.exportName(kind) ?: return@TextButton
                        saving = kind
                        if (kind == ExportKind.WORKBOOK) saveXlsx.launch(name)
                        else saveCsv.launch(name)
                    }
                ) {
                    Text(stringResource(R.string.export_save))
                }
            },
        )
    }
}

private class NavEntry(
    val screen: String,
    val title: Int,
    val description: Int,
    val icon: ImageVector,
)

private class NavGroupDef(val title: Int, val entries: List<NavEntry>)

/** Reports grouped by topic (Material 3 list with section headers). */
private val NAV_GROUPS =
    listOf(
        NavGroupDef(
            R.string.nav_costs,
            listOf(
                NavEntry(
                    "compare",
                    R.string.screen_compare,
                    R.string.nav_compare_desc,
                    Icons.AutoMirrored.Filled.CompareArrows,
                ),
                NavEntry(
                    "bills",
                    R.string.screen_bills,
                    R.string.nav_bills_desc,
                    Icons.AutoMirrored.Filled.ReceiptLong,
                ),
                NavEntry(
                    "storage",
                    R.string.screen_storage,
                    R.string.nav_storage_desc,
                    Icons.Filled.BatteryChargingFull,
                ),
            ),
        ),
        NavGroupDef(
            R.string.nav_usage,
            listOf(
                NavEntry(
                    "monthly",
                    R.string.screen_monthly,
                    R.string.nav_monthly_desc,
                    Icons.Filled.CalendarMonth,
                ),
                NavEntry("yoy", R.string.screen_yoy, R.string.nav_yoy_desc, Icons.Filled.DateRange),
                NavEntry(
                    "profile",
                    R.string.screen_profile,
                    R.string.nav_profile_desc,
                    Icons.Filled.Schedule,
                ),
                NavEntry(
                    "selfuse",
                    R.string.screen_selfuse,
                    R.string.nav_selfuse_desc,
                    Icons.Filled.SolarPower,
                ),
            ),
        ),
        NavGroupDef(
            R.string.nav_data,
            listOf(
                NavEntry(
                    "data",
                    R.string.screen_data,
                    R.string.nav_data_desc,
                    Icons.Filled.TableChart,
                ),
                NavEntry(
                    "rce",
                    R.string.screen_rce,
                    R.string.nav_rce_desc,
                    Icons.AutoMirrored.Filled.ShowChart,
                ),
            ),
        ),
    )

@Composable
private fun NavGroup(group: NavGroupDef, onOpen: (String) -> Unit) {
    SectionCard(title = stringResource(group.title)) {
        group.entries.forEach { e ->
            ListItem(
                headlineContent = { Text(stringResource(e.title)) },
                supportingContent = { Text(stringResource(e.description)) },
                leadingContent = { Icon(e.icon, contentDescription = null) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().clickable { onOpen(e.screen) },
            )
        }
    }
}
