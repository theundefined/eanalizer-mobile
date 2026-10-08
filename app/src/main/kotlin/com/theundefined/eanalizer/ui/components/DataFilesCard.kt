package com.theundefined.eanalizer.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.data.local.DataFileInfo
import com.theundefined.eanalizer.ui.EanalizerViewModel
import com.theundefined.eanalizer.ui.UiState
import com.theundefined.eanalizer.ui.dateTime

/** Opens the system picker for CSV files to import; returns the launch action. */
@Composable
fun rememberCsvImport(viewModel: EanalizerViewModel): () -> Unit {
    // CSV MIME types differ between apps (text/csv, text/comma-separated-values,
    // application/octet-stream...); the content is validated on import.
    val picker =
        rememberLauncherForActivityResult(OpenMultipleDocuments()) { viewModel.importFiles(it) }
    return { picker.launch(arrayOf("*/*")) }
}

/** Settings: local-only mode, stored files (save, share, delete) and importing CSV files. */
@Composable
fun DataFilesCard(state: UiState, viewModel: EanalizerViewModel) {
    val importCsv = rememberCsvImport(viewModel)
    var saving by remember { mutableStateOf<DataFileInfo?>(null) }
    var deleting by remember { mutableStateOf<DataFileInfo?>(null) }
    val save =
        rememberLauncherForActivityResult(CreateDocument("text/csv")) { uri ->
            val file = saving
            saving = null
            if (uri != null && file != null) viewModel.exportDataFile(file, uri)
        }

    SectionCard(title = stringResource(R.string.files_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.local_only_switch))
                MutedText(stringResource(R.string.local_only_info))
            }
            Switch(checked = state.localOnly, onCheckedChange = { viewModel.setLocalOnly(it) })
        }
        MutedText(stringResource(R.string.files_info))
        if (state.files.isEmpty()) MutedText(stringResource(R.string.files_empty))
        state.files.forEach { f ->
            FileRow(
                f,
                onSave = {
                    saving = f
                    save.launch(f.name)
                },
                onShare = { viewModel.exportDataFile(f) },
                onDelete = { deleting = f },
            )
        }
        if (state.importing) {
            MutedText(stringResource(R.string.importing))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        OutlinedButton(onClick = importCsv, enabled = !state.importing) {
            Text(stringResource(R.string.import_files))
        }
    }

    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = {
                Column {
                    Text(stringResource(R.string.delete_file_confirm, f.name))
                    if (!f.imported && !state.localOnly)
                        MutedText(stringResource(R.string.delete_file_downloaded))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        viewModel.deleteDataFile(f)
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun FileRow(
    f: DataFileInfo,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyMedium)
            MutedText(
                if (f.from != null && f.to != null)
                    stringResource(R.string.file_range, f.from.toString(), f.to.toString(), f.hours)
                else stringResource(R.string.file_invalid)
            )
            MutedText(
                stringResource(
                    if (f.imported) R.string.file_imported else R.string.file_downloaded,
                    dateTime(f.modified),
                )
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.file_menu))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.save)) },
                    onClick = {
                        menu = false
                        onSave()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_share)) },
                    onClick = {
                        menu = false
                        onShare()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}
