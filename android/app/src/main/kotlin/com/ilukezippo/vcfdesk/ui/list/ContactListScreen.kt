package com.ilukezippo.vcfdesk.ui.list

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilukezippo.vcfdesk.R
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.data.SourceId
import com.ilukezippo.vcfdesk.ui.AppActions
import com.ilukezippo.vcfdesk.ui.theme.Accent
import com.ilukezippo.vcfdesk.ui.theme.AvatarColors
import com.ilukezippo.vcfdesk.ui.theme.Border
import com.ilukezippo.vcfdesk.ui.theme.Ink
import com.ilukezippo.vcfdesk.ui.theme.Muted
import com.ilukezippo.vcfdesk.ui.theme.Sel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The list pane: top bar with source switcher, search, filters, the contacts and the selection bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(
    state: ListState,
    vm: ContactListViewModel,
    actions: AppActions,
    openId: String?,
    onOpen: (String) -> Unit,
    onEditSelected: () -> Unit,
    onAddGoogle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (state.selected.isEmpty()) {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.app_subtitle), fontSize = 12.sp, color = Muted)
                    }
                },
                actions = {
                    SourceSwitcher(state, onSwitch = vm::switchSource, onAddGoogle = onAddGoogle)
                    OverflowMenu(actions)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        } else {
            SelectionBar(state, vm, onEditSelected)
        }

        if (state.alsoOnPhone) {
            Surface(color = Sel, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.banner_also_on_phone),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                )
            }
        }

        if (state.source == SourceId.Device) SyncOffBanner(state, vm)

        SearchRow(state, vm)
        FilterRow(state, vm)

        val total = state.items.size
        Text(
            stringResource(R.string.contact_count, total),
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        HorizontalDivider(color = Border)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text(
                    stringResource(R.string.list_error, state.error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                state.items.isEmpty() -> Text(
                    stringResource(if (state.query.isEmpty() && state.accountFilter == null && state.groupFilter == null) R.string.list_empty else R.string.list_no_match),
                    color = Muted,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> ContactRows(state, vm, openId, onOpen)
            }
        }
    }
}

@Composable
private fun SourceSwitcher(state: ListState, onSwitch: (SourceId) -> Unit, onAddGoogle: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = sourceLabel(state.source)
    Box {
        TextButton(onClick = { open = true }) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 2.dp))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.source_switcher))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (s in state.sources) {
                DropdownMenuItem(
                    text = { Text(sourceLabel(s), style = TextStyle(textDirection = TextDirection.Content)) },
                    leadingIcon = { if (s == state.source) Icon(Icons.Filled.Check, null) else Box(Modifier.size(24.dp)) },
                    onClick = { open = false; onSwitch(s) },
                )
            }
            HorizontalDivider(color = Border)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.source_add_google)) },
                onClick = { open = false; onAddGoogle() },
            )
        }
    }
}

@Composable
private fun sourceLabel(s: SourceId): String = when (s) {
    SourceId.Device -> stringResource(R.string.source_device)
    is SourceId.Google -> s.email
}

@Composable
private fun OverflowMenu(actions: AppActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_import)) }, onClick = { open = false; actions.onImport() })
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_export)) }, onClick = { open = false; actions.onExport() })
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_backups)) }, onClick = { open = false; actions.onBackups() })
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_settings)) }, onClick = { open = false; actions.onSettings() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(state: ListState, vm: ContactListViewModel, onEdit: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = vm::clearSelection) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.select_clear))
            }
        },
        title = { Text(stringResource(R.string.selected_count, state.selected.size)) },
        actions = {
            val allShown = state.items.isNotEmpty() && state.items.all { it.id in state.selected }
            IconButton(onClick = { if (allShown) vm.clearSelection() else vm.selectResults() }) {
                Icon(
                    Icons.Filled.SelectAll,
                    contentDescription = stringResource(if (allShown) R.string.select_clear else R.string.select_all_shown),
                    tint = if (allShown) Accent else LocalContentColor.current,
                )
            }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.select_edit_short)) }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.select_results)) }, onClick = { menu = false; vm.selectResults() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.select_all)) }, onClick = { menu = false; vm.selectAll() })
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Sel),
    )
}

@Composable
private fun SearchRow(state: ListState, vm: ContactListViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The field keeps its own text and cursor. Reading the text back from the list state
        // (which updates a moment later) moved the cursor while typing joined scripts like Arabic.
        var text by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) {
            mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(state.query))
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; if (it.text != state.query) vm.setQuery(it.text) },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (text.text.isNotEmpty()) {
                    IconButton(onClick = { text = androidx.compose.ui.text.input.TextFieldValue(""); vm.setQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                    }
                }
            },
            textStyle = TextStyle(textDirection = TextDirection.Content),
            keyboardOptions = KeyboardOptions.Default,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = vm::toggleSort) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort_toggle))
        }
    }
}

@Composable
private fun FilterRow(state: ListState, vm: ContactListViewModel) {
    if (state.accounts.size < 2 && state.groups.isEmpty() && state.accountFilter == null && state.groupFilter == null) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.accounts.size > 1 || state.accountFilter != null) {
            FilterMenu(
                title = stringResource(R.string.filter_account),
                all = stringResource(R.string.filter_all_accounts),
                options = state.accounts,
                current = state.accountFilter,
                onPick = vm::setAccountFilter,
            )
        }
        if (state.groups.isNotEmpty() || state.groupFilter != null) {
            FilterMenu(
                title = stringResource(R.string.filter_group),
                all = stringResource(R.string.filter_all_groups),
                options = state.groups,
                current = state.groupFilter,
                onPick = vm::setGroupFilter,
            )
        }
    }
}

@Composable
private fun FilterMenu(title: String, all: String, options: List<String>, current: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = current != null,
            onClick = { open = true },
            label = { Text(current ?: title, maxLines = 1, style = TextStyle(textDirection = TextDirection.Content)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(all) }, onClick = { open = false; onPick(null) })
            for (o in options) {
                DropdownMenuItem(
                    text = { Text(o, style = TextStyle(textDirection = TextDirection.Content)) },
                    onClick = { open = false; onPick(o) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRows(state: ListState, vm: ContactListViewModel, openId: String?, onOpen: (String) -> Unit) {
    val selecting = state.selected.isNotEmpty()
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.items, key = { it.id }) { c ->
            val selected = c.id in state.selected
            ContactRow(
                contact = c,
                selected = selected,
                highlighted = !selecting && c.id == openId,
                modifier = Modifier.combinedClickable(
                    onClick = { if (selecting) vm.toggleSelect(c.id) else onOpen(c.id) },
                    onLongClick = { vm.toggleSelect(c.id) },
                ),
            )
            HorizontalDivider(color = Border.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, selected: Boolean, highlighted: Boolean, modifier: Modifier = Modifier) {
    val bg = if (selected || highlighted) Sel else Color.Transparent
    Row(
        modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(contact, selected)
        Column(Modifier.weight(1f)) {
            val name = contact.name.display.ifBlank { stringResource(R.string.no_name) }
            Text(
                name,
                style = TextStyle(textDirection = TextDirection.Content, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            val phone = (contact.phones.firstOrNull { it.pref } ?: contact.phones.firstOrNull())?.value
            Text(
                phone ?: stringResource(R.string.no_phone),
                style = TextStyle(textDirection = TextDirection.Content, fontSize = 13.sp, color = Muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        contact.account?.let { AccountBadge(it.label) }
    }
}

@Composable
private fun AccountBadge(label: String) {
    Text(
        label,
        fontSize = 11.sp,
        color = Accent,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Accent.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun Avatar(contact: Contact, selected: Boolean) {
    val size = 44.dp
    if (selected) {
        Box(Modifier.size(size).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = Ink)
        }
        return
    }
    val bmp by produceState<ImageBitmap?>(null, contact.id, contact.thumbnail) {
        value = contact.thumbnail?.let { Thumbnails.get(contact.account?.name.orEmpty() + "/" + contact.id, it) }
    }
    val image = bmp
    if (image != null) {
        Image(image, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        val seed = contact.name.display.ifBlank { contact.id }
        val color = AvatarColors[Math.floorMod(seed.hashCode(), AvatarColors.size)]
        Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(initialOf(seed), color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

internal fun initialOf(name: String): String {
    val t = name.trim()
    if (t.isEmpty()) return "?"
    return String(Character.toChars(t.codePointAt(0))).uppercase()
}

/** Decoded thumbnails, cached; decoding happens off the main thread. */
internal object Thumbnails {
    private val cache = object : LruCache<String, ImageBitmap>(300) {}

    suspend fun get(key: String, bytes: ByteArray): ImageBitmap? {
        val k = key + "#" + bytes.contentHashCode()
        cache.get(k)?.let { return it }
        val decoded = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        } ?: return null
        cache.put(k, decoded)
        return decoded
    }
}


/** Google accounts on the phone whose contacts sync is off: edits there never reach Google Contacts. */
fun contactsSyncOff(emails: List<String>): List<String> = try {
    if (!android.content.ContentResolver.getMasterSyncAutomatically()) emails
    else emails.filter {
        !android.content.ContentResolver.getSyncAutomatically(
            android.accounts.Account(it, "com.google"), android.provider.ContactsContract.AUTHORITY,
        )
    }
} catch (e: SecurityException) {
    emptyList()
}

@Composable
private fun SyncOffBanner(state: ListState, vm: ContactListViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var resumes by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { resumes++ }
    val off = remember(state.loading, state.accounts, resumes) { contactsSyncOff(vm.phoneGoogleAccounts()) }
    if (off.isEmpty()) return
    Surface(color = Sel, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.banner_sync_off, off.joinToString(", ")),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SYNC_SETTINGS)) }
            }) { Text(stringResource(R.string.banner_sync_off_fix)) }
        }
    }
}
