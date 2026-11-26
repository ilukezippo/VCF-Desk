package com.ilukezippo.vcfdesk.ui.io

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilukezippo.vcfdesk.R
import com.ilukezippo.vcfdesk.core.export.ExportFormat
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.data.ContactSource

private fun formatLabel(f: ExportFormat): Int = when (f) {
    ExportFormat.ANDROID_VCF -> R.string.export_fmt_android_vcf
    ExportFormat.IPHONE_VCF -> R.string.export_fmt_iphone_vcf
    ExportFormat.VCARD4 -> R.string.export_fmt_vcard4
    ExportFormat.GOOGLE_CSV -> R.string.export_fmt_google_csv
    ExportFormat.OUTLOOK_CSV -> R.string.export_fmt_outlook_csv
    ExportFormat.EXCEL -> R.string.export_fmt_excel
    ExportFormat.CSV -> R.string.export_fmt_csv
    ExportFormat.SPLIT_VCF_ZIP -> R.string.export_fmt_split_zip
}

/** Pick which contacts and which format, then save to a file the user names, or share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    vm: ExportViewModel,
    source: ContactSource,
    /** The contacts the list currently shows (the search results). */
    results: List<Contact>,
    selected: Set<String>,
    onBack: () -> Unit,
) {
    LaunchedEffect(source.id, results, selected) { vm.configure(source, results, selected) }
    val state by vm.state.collectAsStateWithLifecycle()
    val share by vm.pendingShare.collectAsStateWithLifecycle()
    val allCount by vm.allCount.collectAsStateWithLifecycle()
    val includeReadOnly by vm.includeReadOnly.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.export_share_title)
    LaunchedEffect(share) {
        share?.let { context.startShare(it.file, it.mime, shareTitle); vm.shareHandled() }
    }

    var scope by rememberSaveable { mutableStateOf(if (selected.isNotEmpty()) ExportScope.SELECTION else ExportScope.RESULTS) }
    var format by rememberSaveable { mutableStateOf(ExportFormat.ANDROID_VCF) }
    val create = rememberLauncherForActivityResult(CreateDocumentFor()) { uri -> uri?.let { vm.export(scope, format, it) } }
    val working = state is ExportState.Working

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.export_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text(stringResource(R.string.export_scope), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                Column(Modifier.selectableGroup()) {
                    ScopeRow(ExportScope.ALL, R.string.export_scope_all, allCount, scope, !working) { scope = it }
                    if (scope == ExportScope.ALL) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 36.dp)
                                .toggleable(includeReadOnly, enabled = !working, role = Role.Checkbox) { vm.setIncludeReadOnly(it) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(includeReadOnly, onCheckedChange = null, enabled = !working)
                            Text(stringResource(R.string.export_include_read_only), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    ScopeRow(ExportScope.RESULTS, R.string.export_scope_results, results.size, scope, !working && results.isNotEmpty()) { scope = it }
                    ScopeRow(ExportScope.SELECTION, R.string.export_scope_selection, selected.size, scope, !working && selected.isNotEmpty()) { scope = it }
                }
            }
            item { Text(stringResource(R.string.export_format), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp)) }
            items(ExportFormat.entries) { f ->
                Row(
                    Modifier.fillMaxWidth().selectable(format == f, enabled = !working, role = Role.RadioButton) { format = f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(format == f, onClick = null, enabled = !working)
                    Text(stringResource(formatLabel(f)), Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
                }
            }
            item {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !working, onClick = { create.launch(format.mime() to format.fileName()) }) { Text(stringResource(R.string.export_save)) }
                    OutlinedButton(enabled = !working, onClick = { vm.share(scope, format) }) { Text(stringResource(R.string.export_share)) }
                }
            }
            item {
                when (val s = state) {
                    ExportState.Idle -> {}
                    is ExportState.Working -> Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.export_working, s.done, s.total))
                        LinearProgressIndicator(progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = vm::cancel) { Text(stringResource(R.string.bulk_stop)) }
                    }
                    is ExportState.Failed -> Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            s.tooLargeFor?.let { stringResource(R.string.export_too_large, stringResource(formatLabel(it))) }
                                ?: stringResource(R.string.export_failed, s.message),
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (s.partialFile) Text(stringResource(R.string.export_partial_file), color = MaterialTheme.colorScheme.error)
                    }
                    is ExportState.Done -> Report(s.report)
                }
            }
        }
    }
}

@Composable
private fun ScopeRow(value: ExportScope, label: Int, count: Int?, current: ExportScope, enabled: Boolean, onPick: (ExportScope) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(current == value, enabled = enabled, role = Role.RadioButton) { onPick(value) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(current == value, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(stringResource(label))
            count?.let { Text(stringResource(R.string.export_scope_count, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Report(r: ExportReport) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.export_done_title), style = MaterialTheme.typography.titleMedium)
        Text(
            if (r.exported == 0) stringResource(R.string.export_nothing) else pluralStringResource(R.plurals.export_exported, r.exported, r.exported),
        )
        if (r.excelPhotosOmitted > 0) {
            Text(pluralStringResource(R.plurals.export_excel_photos, r.excelPhotosOmitted, r.excelPhotosOmitted), color = MaterialTheme.colorScheme.tertiary)
        }
        if (r.skipped.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.export_skipped_header, r.skipped.size, r.skipped.size),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error,
            )
            r.skipped.take(50).forEach { (who, why) ->
                Text("$who: $why", style = MaterialTheme.typography.bodySmall.merge(TextStyle(textDirection = TextDirection.Content)))
            }
        }
    }
}
