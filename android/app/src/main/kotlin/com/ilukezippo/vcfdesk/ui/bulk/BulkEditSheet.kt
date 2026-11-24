package com.ilukezippo.vcfdesk.ui.bulk

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilukezippo.vcfdesk.R
import com.ilukezippo.vcfdesk.core.bulk.BulkChange
import com.ilukezippo.vcfdesk.core.bulk.TextField
import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.data.BulkOp
import com.ilukezippo.vcfdesk.data.ContactSource
import com.ilukezippo.vcfdesk.data.SourceId
import com.ilukezippo.vcfdesk.data.device.SystemDeviceAccounts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the one bulk change in progress. The same selection keeps its view model; a different one
 * starts fresh, unless a change is already past choosing (preview, running, result), which must
 * outlive the selection it was made from.
 */
class BulkHolder(private val create: (SourceId, List<String>) -> BulkViewModel) {
    private val _current = MutableStateFlow<BulkViewModel?>(null)
    val current: StateFlow<BulkViewModel?> = _current.asStateFlow()

    fun forSelection(source: SourceId, ids: Collection<String>): BulkViewModel {
        val cur = _current.value
        if (cur != null && (cur.stage.value != Stage.Compose || (cur.sourceId == source && cur.ids.toSet() == ids.toSet()))) return cur
        cur?.dispose()
        return create(source, ids.toList()).also { _current.value = it }
    }

    fun release() {
        _current.value?.dispose()
        _current.value = null
    }
}

private enum class Option { SET_FIELD, FIND_REPLACE, GROUPS, FIRST_MIDDLE, COUNTRY_CODE, COPY_MOVE, DUPLICATES, DELETE }

private fun fieldLabel(f: TextField): Int = when (f) {
    TextField.COMPANY -> R.string.bulk_field_company
    TextField.DEPARTMENT -> R.string.bulk_field_department
    TextField.TITLE -> R.string.bulk_field_title
    TextField.NOTES -> R.string.bulk_field_notes
    TextField.CITY -> R.string.bulk_field_city
    TextField.COUNTRY -> R.string.bulk_field_country
    TextField.PREFIX -> R.string.bulk_field_prefix
    TextField.SUFFIX -> R.string.bulk_field_suffix
    TextField.FIRST -> R.string.bulk_field_first
    TextField.MIDDLE -> R.string.bulk_field_middle
    TextField.LAST -> R.string.bulk_field_last
    TextField.NICKNAME -> R.string.bulk_field_nickname
}

/** A place to copy to: a device account (null: no account) or a Google account. */
private data class Target(val source: SourceId, val account: AccountTag?)

/** The writable accounts that hold contacts on this phone. */
fun writableDeviceAccounts(context: Context): List<AccountTag> = try {
    val types = ContentResolver.getSyncAdapterTypes()
        .filter { it.authority == ContactsContract.AUTHORITY }.mapTo(HashSet()) { it.accountType }
    val tagger = SystemDeviceAccounts(context)
    AccountManager.get(context).accounts.filter { it.type in types }.map { tagger.tag(it.type, it.name) }.filter { !it.readOnly }
} catch (e: SecurityException) {
    emptyList()
}

private fun sameAccount(a: AccountTag?, b: AccountTag?) =
    (a == null && b == null) || (a != null && b != null && a.type == b.type && a.name == b.name)

/**
 * The bulk editor for a selection: pick one change, preview it from here. Previews, progress and
 * results are shown by [BulkStageDialogs].
 */
@Composable
fun BulkEditSheet(
    vm: BulkViewModel,
    groups: List<String>,
    googleSources: List<SourceId.Google>,
    /** The accounts the selected contacts are in (null entry: no account). */
    selectionAccounts: Set<AccountTag?>,
    sourceFor: (SourceId) -> ContactSource,
    deviceAccounts: List<AccountTag> = writableDeviceAccounts(LocalContext.current),
) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val country by vm.country.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<Option?>(null) }
    fun toggle(o: Option) { open = if (open == o) null else o }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.select_edit, vm.ids.size), style = MaterialTheme.typography.titleLarge)
        if (busy) {
            Text(stringResource(R.string.bulk_working), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Section(R.string.bulk_set_field, open == Option.SET_FIELD, { toggle(Option.SET_FIELD) }) {
            SetFieldForm(!busy) { vm.preview(BulkOp.Edit(listOf(it))) }
        }
        Section(R.string.bulk_find_replace, open == Option.FIND_REPLACE, { toggle(Option.FIND_REPLACE) }) {
            FindReplaceForm(!busy) { vm.preview(BulkOp.Edit(listOf(it))) }
        }
        Section(R.string.bulk_groups, open == Option.GROUPS, { toggle(Option.GROUPS) }) {
            GroupsForm(groups, !busy) { vm.preview(BulkOp.Edit(listOf(it))) }
        }
        Section(R.string.bulk_first_middle, open == Option.FIRST_MIDDLE, { toggle(Option.FIRST_MIDDLE) }) {
            Text(stringResource(R.string.bulk_first_middle_hint), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { vm.preview(BulkOp.Edit(listOf(BulkChange.FirstMiddleOnly))) }, enabled = !busy) {
                Text(stringResource(R.string.bulk_preview))
            }
        }
        Section(R.string.bulk_country_code, open == Option.COUNTRY_CODE, { toggle(Option.COUNTRY_CODE) }) {
            var code by remember(country) { mutableStateOf(country) }
            Text(stringResource(R.string.bulk_country_hint), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = code, onValueChange = { code = it }, singleLine = true,
                label = { Text(stringResource(R.string.bulk_country_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                textStyle = TextStyle(textDirection = TextDirection.Ltr),
            )
            Button(onClick = { vm.preview(BulkOp.Edit(listOf(BulkChange.AddCountryCode(code.trim())))) }, enabled = !busy && code.isNotBlank()) {
                Text(stringResource(R.string.bulk_preview))
            }
        }
        Section(R.string.bulk_copy_move, open == Option.COPY_MOVE, { toggle(Option.COPY_MOVE) }) {
            CopyMoveForm(vm.sourceId, googleSources, deviceAccounts, selectionAccounts, sourceFor, !busy) { vm.preview(it) }
        }
        Section(R.string.bulk_remove_dupes, open == Option.DUPLICATES, { toggle(Option.DUPLICATES) }) {
            Text(stringResource(R.string.bulk_remove_dupes_hint), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { vm.preview(BulkOp.RemoveExactDuplicates) }, enabled = !busy) { Text(stringResource(R.string.bulk_preview)) }
        }
        Section(R.string.bulk_delete, open == Option.DELETE, { toggle(Option.DELETE) }, danger = true) {
            Button(
                onClick = { vm.preview(BulkOp.Delete) }, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(stringResource(R.string.bulk_preview)) }
        }
    }
}

@Composable
private fun Section(title: Int, expanded: Boolean, onToggle: () -> Unit, danger: Boolean = false, body: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 14.dp),
            )
            if (expanded) Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { body() }
        }
    }
}

@Composable
private fun <T> Picker(label: String, value: String, options: List<T>, text: @Composable (T) -> String, onPick: (T) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text(value) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { menu = false; onPick(o) }) }
        }
    }
}

private enum class SetMode { REPLACE, CLEAR, APPEND }

@Composable
private fun SetFieldForm(enabled: Boolean, onPreview: (BulkChange) -> Unit) {
    var field by rememberSaveable { mutableStateOf(TextField.COMPANY) }
    var mode by rememberSaveable { mutableStateOf(SetMode.REPLACE) }
    var value by rememberSaveable { mutableStateOf("") }
    Picker(stringResource(R.string.bulk_field), stringResource(fieldLabel(field)), TextField.entries, { stringResource(fieldLabel(it)) }) {
        field = it
        if (it != TextField.NOTES && mode == SetMode.APPEND) mode = SetMode.REPLACE
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(mode == SetMode.REPLACE, { mode = SetMode.REPLACE }, { Text(stringResource(R.string.bulk_mode_replace)) })
        FilterChip(mode == SetMode.CLEAR, { mode = SetMode.CLEAR }, { Text(stringResource(R.string.bulk_mode_clear)) })
        if (field == TextField.NOTES) FilterChip(mode == SetMode.APPEND, { mode = SetMode.APPEND }, { Text(stringResource(R.string.bulk_mode_append)) })
    }
    if (mode != SetMode.CLEAR) {
        OutlinedTextField(
            value = value, onValueChange = { value = it },
            label = { Text(stringResource(if (mode == SetMode.APPEND) R.string.bulk_append_text else R.string.bulk_value)) },
            textStyle = TextStyle(textDirection = TextDirection.Content),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(
        enabled = enabled && (mode == SetMode.CLEAR || value.isNotEmpty()),
        onClick = {
            onPreview(
                when (mode) {
                    SetMode.REPLACE -> BulkChange.SetField(field, value)
                    SetMode.CLEAR -> BulkChange.SetField(field, "")
                    SetMode.APPEND -> BulkChange.AppendNotes(value)
                },
            )
        },
    ) { Text(stringResource(R.string.bulk_preview)) }
}

@Composable
private fun FindReplaceForm(enabled: Boolean, onPreview: (BulkChange) -> Unit) {
    var field by rememberSaveable { mutableStateOf(TextField.COMPANY) }
    var find by rememberSaveable { mutableStateOf("") }
    var replace by rememberSaveable { mutableStateOf("") }
    var ignoreCase by rememberSaveable { mutableStateOf(true) }
    Picker(stringResource(R.string.bulk_field), stringResource(fieldLabel(field)), TextField.entries, { stringResource(fieldLabel(it)) }) { field = it }
    OutlinedTextField(find, { find = it }, label = { Text(stringResource(R.string.bulk_find)) },
        textStyle = TextStyle(textDirection = TextDirection.Content), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(replace, { replace = it }, label = { Text(stringResource(R.string.bulk_replace_with)) },
        textStyle = TextStyle(textDirection = TextDirection.Content), modifier = Modifier.fillMaxWidth())
    Row(Modifier.clickable { ignoreCase = !ignoreCase }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(ignoreCase, { ignoreCase = it })
        Text(stringResource(R.string.bulk_ignore_case))
    }
    Button(enabled = enabled && find.isNotEmpty(), onClick = { onPreview(BulkChange.FindReplace(field, find, replace, ignoreCase)) }) {
        Text(stringResource(R.string.bulk_preview))
    }
}

@Composable
private fun GroupsForm(groups: List<String>, enabled: Boolean, onPreview: (BulkChange) -> Unit) {
    var add by rememberSaveable { mutableStateOf(true) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) } // null: "New group…"
    var typed by rememberSaveable { mutableStateOf("") }
    // removing needs an existing group; adding may also make a new one
    val newLabel = stringResource(R.string.bulk_group_new)
    val options: List<String?> = if (add) groups + listOf<String?>(null) else groups
    val shown = picked ?: if (add) null else groups.firstOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(add, { add = true }, { Text(stringResource(R.string.bulk_group_add)) })
        FilterChip(!add, { add = false }, { Text(stringResource(R.string.bulk_group_remove)) })
    }
    Picker(stringResource(R.string.bulk_group_pick), shown ?: newLabel, options, { it ?: newLabel }) { picked = it }
    if (add && shown == null) {
        OutlinedTextField(typed, { typed = it }, label = { Text(stringResource(R.string.bulk_group_name)) },
            textStyle = TextStyle(textDirection = TextDirection.Content), modifier = Modifier.fillMaxWidth())
    }
    val name = (shown ?: typed).trim()
    Button(
        enabled = enabled && name.isNotEmpty(),
        onClick = { onPreview(if (add) BulkChange.AddGroup(name) else BulkChange.RemoveGroup(name)) },
    ) { Text(stringResource(R.string.bulk_preview)) }
}

@Composable
private fun CopyMoveForm(
    current: SourceId,
    googleSources: List<SourceId.Google>,
    deviceAccounts: List<AccountTag>,
    selectionAccounts: Set<AccountTag?>,
    sourceFor: (SourceId) -> ContactSource,
    enabled: Boolean,
    onPreview: (BulkOp) -> Unit,
) {
    var move by rememberSaveable { mutableStateOf(false) }
    var targetSource by remember { mutableStateOf<SourceId>(SourceId.Device) }
    var account by remember { mutableStateOf<AccountTag?>(deviceAccounts.firstOrNull()) }
    val sources: List<SourceId> = listOf(SourceId.Device) + googleSources
    val phone = stringResource(R.string.source_device)
    fun name(s: SourceId) = if (s is SourceId.Google) s.email else phone
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!move, { move = false }, { Text(stringResource(R.string.bulk_copy)) })
        FilterChip(move, { move = true }, { Text(stringResource(R.string.bulk_move)) })
    }
    Picker(stringResource(R.string.bulk_target_label), name(targetSource), sources, { name(it) }) {
        targetSource = it
        account = if (it == SourceId.Device) deviceAccounts.firstOrNull() else null
    }
    if (targetSource == SourceId.Device) {
        val none = stringResource(R.string.bulk_account_none)
        val choices: List<AccountTag?> = deviceAccounts + listOf<AccountTag?>(null)
        Picker(stringResource(R.string.bulk_account_label), account?.let { "${it.label} · ${it.name}" } ?: none, choices,
            { a -> a?.let { "${it.label} · ${it.name}" } ?: none }) { account = it }
    }
    // the exact same place the contacts are already in
    val same = targetSource == current && (targetSource is SourceId.Google ||
        (selectionAccounts.size == 1 && sameAccount(selectionAccounts.first(), account)))
    if (same) Text(stringResource(R.string.bulk_same_target), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    Button(enabled = enabled && !same, onClick = { onPreview(BulkOp.CopyTo(sourceFor(targetSource), account, move)) }) {
        Text(stringResource(R.string.bulk_preview))
    }
}
