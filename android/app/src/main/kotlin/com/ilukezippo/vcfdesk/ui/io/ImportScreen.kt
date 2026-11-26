package com.ilukezippo.vcfdesk.ui.io

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilukezippo.vcfdesk.R
import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.table.Field
import com.ilukezippo.vcfdesk.data.ContactSource
import com.ilukezippo.vcfdesk.data.SourceId
import com.ilukezippo.vcfdesk.ui.bulk.Picker
import com.ilukezippo.vcfdesk.ui.bulk.writableDeviceAccounts

private fun fieldLabel(f: Field): Int = when (f) {
    Field.SKIP -> R.string.col_skip
    Field.FULL_NAME -> R.string.col_full_name
    Field.PREFIX -> R.string.col_prefix
    Field.FIRST -> R.string.col_first
    Field.MIDDLE -> R.string.col_middle
    Field.LAST -> R.string.col_last
    Field.SUFFIX -> R.string.col_suffix
    Field.NICKNAME -> R.string.col_nickname
    Field.MOBILE -> R.string.col_mobile
    Field.PHONE_HOME -> R.string.col_phone_home
    Field.PHONE_WORK -> R.string.col_phone_work
    Field.PHONE_OTHER -> R.string.col_phone_other
    Field.EMAIL -> R.string.col_email
    Field.EMAIL_WORK -> R.string.col_email_work
    Field.COMPANY -> R.string.col_company
    Field.DEPARTMENT -> R.string.col_department
    Field.TITLE -> R.string.col_title
    Field.STREET -> R.string.col_street
    Field.CITY -> R.string.col_city
    Field.REGION -> R.string.col_region
    Field.POSTCODE -> R.string.col_postcode
    Field.COUNTRY -> R.string.col_country
    Field.BIRTHDAY -> R.string.col_birthday
    Field.NOTES -> R.string.col_notes
    Field.GROUPS -> R.string.col_groups
    Field.WEBSITE -> R.string.col_website
    Field.PHOTO -> R.string.col_photo
}

private val ContentLeading = TextStyle(textDirection = TextDirection.Content)

/** Pick a file (or arrive with one from "Open with"), see what it holds, map columns, choose where it goes, import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    vm: ImportViewModel,
    /** A file handed in by another app; opened once, then [onUriTaken] is called. */
    incoming: Uri?,
    onUriTaken: () -> Unit,
    googleSources: List<SourceId.Google>,
    sourceFor: (SourceId) -> ContactSource,
    onBack: () -> Unit,
    deviceAccounts: List<AccountTag> = com.ilukezippo.vcfdesk.ui.bulk.rememberWritableDeviceAccounts(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val mapping by vm.mapping.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::open) }
    LaunchedEffect(incoming) {
        if (incoming != null) { vm.open(incoming); onUriTaken() }
    }
    val choose = { picker.launch(arrayOf("*/*")) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.import_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val s = state) {
                ImportState.Idle -> {
                    Text(stringResource(R.string.import_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = choose) { Text(stringResource(R.string.import_choose)) }
                }
                ImportState.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(stringResource(R.string.import_reading))
                }
                is ImportState.Failed -> {
                    Text(errorText(s.error), color = MaterialTheme.colorScheme.error)
                    Button(onClick = choose) { Text(stringResource(R.string.import_choose_other)) }
                }
                is ImportState.Ready -> ReadyForm(vm, s, mapping, googleSources, sourceFor, deviceAccounts, choose)
                is ImportState.Importing -> {
                    Text(stringResource(R.string.import_running, s.done, s.total))
                    LinearProgressIndicator(progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                }
                is ImportState.Done -> DoneReport(s.report, onAnother = { vm.reset() }, onClose = onBack)
            }
        }
    }
}

@Composable
private fun errorText(e: ImportError): String = when (e) {
    is ImportError.TooLarge -> stringResource(R.string.import_err_too_large, e.limitMb)
    ImportError.NoContacts -> stringResource(R.string.import_err_no_contacts)
    ImportError.OldExcel -> stringResource(R.string.import_err_old_excel)
    is ImportError.Unreadable -> stringResource(R.string.import_err_unreadable, e.detail?.let { ": $it" } ?: ".")
    is ImportError.WriteFailed -> stringResource(R.string.import_err_write, e.detail ?: "")
}

@Composable
private fun ReadyForm(
    vm: ImportViewModel,
    s: ImportState.Ready,
    mapping: List<Field>,
    googleSources: List<SourceId.Google>,
    sourceFor: (SourceId) -> ContactSource,
    deviceAccounts: List<AccountTag>,
    choose: () -> Unit,
) {
    var targetSource by remember { mutableStateOf<SourceId>(SourceId.Device) }
    var account by remember { mutableStateOf<AccountTag?>(deviceAccounts.firstOrNull()) }
    val phone = stringResource(R.string.source_device)
    val none = stringResource(R.string.bulk_account_none)
    fun accountName(a: AccountTag?) = a?.let { "${it.label} · ${it.name}" } ?: none

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(when (s.kind) { ImportKind.VCF -> R.string.import_kind_vcf; ImportKind.CSV -> R.string.import_kind_csv; ImportKind.XLSX -> R.string.import_kind_xlsx }),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(onClick = choose) { Text(stringResource(R.string.import_choose_other)) }
            }
        }
        if (s.kind == ImportKind.VCF) {
            item {
                s.count?.let { Text(pluralStringResource(R.plurals.import_vcf_found, it, it), style = MaterialTheme.typography.titleMedium) }
            }
        } else {
            if (s.sheets.size > 1) item {
                Picker(stringResource(R.string.import_sheet), s.sheet, s.sheets, { it }) { vm.selectSheet(it) }
            }
            item {
                Text(stringResource(R.string.import_mapping), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.import_mapping_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(s.headers) { i, header ->
                Column {
                    Text(header.ifBlank { "#${i + 1}" }, style = MaterialTheme.typography.labelLarge.merge(ContentLeading))
                    s.samples.getOrNull(i)?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            stringResource(R.string.import_example, it.take(60)),
                            style = MaterialTheme.typography.bodySmall.merge(ContentLeading),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val f = mapping.getOrElse(i) { Field.SKIP }
                    Picker("", stringResource(fieldLabel(f)), Field.entries, { stringResource(fieldLabel(it)) }) { vm.setMapping(i, it) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.import_one_middle), Modifier.weight(1f))
                    Switch(s.oneMiddle, { vm.setOneMiddle(it) })
                }
            }
            item {
                OutlinedTextField(
                    value = s.countryCode,
                    onValueChange = vm::setCountryCode,
                    label = { Text(stringResource(R.string.import_country_code)) },
                    supportingText = { Text(stringResource(if (s.countryCodeValid) R.string.import_country_code_hint else R.string.import_country_code_bad)) },
                    isError = !s.countryCodeValid,
                    singleLine = true,
                    modifier = Modifier.widthIn(max = 280.dp),
                )
            }
            item {
                val n = s.count
                when {
                    n == null -> {}
                    n == 0 -> Text(stringResource(R.string.import_none), color = MaterialTheme.colorScheme.error)
                    else -> Text(pluralStringResource(R.plurals.import_rows_become, n, n), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        item {
            val sources: List<SourceId> = listOf(SourceId.Device) + googleSources
            Picker(
                stringResource(R.string.import_into),
                if (targetSource is SourceId.Google) (targetSource as SourceId.Google).email else phone,
                sources,
                { if (it is SourceId.Google) it.email else phone },
            ) {
                targetSource = it
                account = if (it == SourceId.Device) deviceAccounts.firstOrNull() else null
            }
        }
        if (targetSource == SourceId.Device) item {
            val choices: List<AccountTag?> = deviceAccounts + listOf<AccountTag?>(null)
            Picker(stringResource(R.string.bulk_account_label), accountName(account), choices, { accountName(it) }) { account = it }
        }
        item {
            Button(enabled = s.canImport, onClick = { vm.importInto(sourceFor(targetSource), account) }) {
                Text(stringResource(R.string.import_button))
            }
        }
    }
}

@Composable
private fun DoneReport(r: ImportReport, onAnother: () -> Unit, onClose: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.import_done_title), style = MaterialTheme.typography.titleLarge) }
        item { Text(pluralStringResource(R.plurals.import_written, r.written, r.written), style = MaterialTheme.typography.titleMedium) }
        if (r.skippedReadOnly > 0) item { Text(stringResource(R.string.import_skipped, r.skippedReadOnly)) }
        if (r.failed.isNotEmpty()) {
            item {
                Text(
                    pluralStringResource(R.plurals.import_failed_header, r.failed.size, r.failed.size),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error,
                )
            }
            itemsIndexed(r.failed) { _, (who, why) ->
                Text("$who: $why", style = MaterialTheme.typography.bodySmall.merge(ContentLeading))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onClose) { Text(stringResource(R.string.done)) }
                OutlinedButton(onClick = onAnother) { Text(stringResource(R.string.import_another)) }
            }
        }
    }
}
