package com.ilukezippo.vcfdesk.ui.backups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilukezippo.vcfdesk.R
import com.ilukezippo.vcfdesk.data.SourceId
import com.ilukezippo.vcfdesk.data.backup.Backup
import com.ilukezippo.vcfdesk.ui.bulk.Failures
import com.ilukezippo.vcfdesk.ui.io.startShare
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val whenFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@Composable
private fun actionLabel(action: String): String = when (action) {
    "edit" -> stringResource(R.string.backups_action_edit)
    "delete" -> stringResource(R.string.backups_action_delete)
    "move" -> stringResource(R.string.backups_action_move)
    "undo" -> stringResource(R.string.backups_action_undo)
    else -> action
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupsScreen(vm: BackupsViewModel, onBack: () -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val restore by vm.restore.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<Backup?>(null) }
    val phone = stringResource(R.string.source_device)
    val shareTitle = stringResource(R.string.backups_share)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.backups_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.backups_intro), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (loaded && items.isEmpty()) Text(stringResource(R.string.backups_empty), style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.file.name }) { b ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(actionLabel(b.action), style = MaterialTheme.typography.titleMedium)
                            Text(whenFormat.format(b.time.atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                (if (b.source is SourceId.Google) (b.source as SourceId.Google).email else phone) + " · " +
                                    pluralStringResource(R.plurals.backups_count, b.count, b.count),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled = restore !is RestoreState.Running, onClick = { confirm = b }) { Text(stringResource(R.string.backups_restore)) }
                                OutlinedButton(onClick = { context.startShare(b.file, "text/x-vcard", shareTitle) }) { Text(stringResource(R.string.backups_share)) }
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { b ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.backups_restore_title)) },
            text = { Text(stringResource(R.string.backups_restore_body, actionLabel(b.action).lowercase())) },
            confirmButton = { TextButton(onClick = { confirm = null; vm.restore(b) }) { Text(stringResource(R.string.backups_restore)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    when (val r = restore) {
        RestoreState.Idle -> {}
        is RestoreState.Running -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.backups_restoring)) },
            text = { CircularProgressIndicator(Modifier.padding(8.dp)) },
        )
        is RestoreState.Done -> AlertDialog(
            onDismissRequest = vm::dismissResult,
            title = { Text(stringResource(R.string.backups_restored_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.bulk_undone, r.report.changed))
                    if (r.report.skippedReadOnly > 0) Text(pluralStringResource(R.plurals.bulk_skipped, r.report.skippedReadOnly, r.report.skippedReadOnly))
                    Failures(r.report.failed, emptyMap())
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissResult) { Text(stringResource(R.string.done)) } },
        )
        is RestoreState.Failed -> AlertDialog(
            onDismissRequest = vm::dismissResult,
            title = { Text(stringResource(R.string.bulk_error_title)) },
            text = {
                Text(
                    when (val e = r.error) {
                        is RestoreError.AccountMissing -> stringResource(R.string.backups_account_missing, e.email)
                        is RestoreError.Failed -> stringResource(R.string.backups_restore_failed, e.message)
                    },
                )
            },
            confirmButton = { TextButton(onClick = vm::dismissResult) { Text(stringResource(R.string.close)) } },
        )
    }
}
