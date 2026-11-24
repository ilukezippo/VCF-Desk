package com.ilukezippo.vcfdesk.ui.editor

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilukezippo.vcfdesk.R
import com.ilukezippo.vcfdesk.core.model.Address
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Typed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PHONE_LABELS = listOf("Mobile", "Home", "Work", "Other", "Main", "Work mobile", "Work fax", "Home fax", "Pager")
private val EMAIL_LABELS = listOf("Home", "Work", "Other", "Mobile")
private val ADDRESS_LABELS = listOf("Home", "Work", "Other")
private val WEBSITE_LABELS = listOf("Homepage", "Blog", "Profile", "Home", "Work", "Other")
// lower-case, as the sources and the vCard reader name events
private val EVENT_LABELS = listOf("birthday", "anniversary", "other")
private val RELATION_LABELS = listOf("Spouse", "Partner", "Child", "Parent", "Mother", "Father", "Brother", "Sister", "Friend", "Manager", "Assistant", "Relative")
private val IM_LABELS = listOf("Skype", "Google Talk", "Yahoo", "AIM", "ICQ", "Jabber", "QQ", "Windows Live")

/** The editor for one contact: the whole detail pane (or the whole screen when folded). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditorScreen(vm: ContactEditorViewModel, onClose: () -> Unit) {
    val status by vm.status.collectAsStateWithLifecycle()
    val contact by vm.contact.collectAsStateWithLifecycle()
    val dirty by vm.isDirty.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val readOnly by vm.readOnly.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val saveError by vm.saveError.collectAsStateWithLifecycle()
    val photoError by vm.photoError.collectAsStateWithLifecycle()
    val groupChoices by vm.groupChoices.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestClose = { if (dirty) confirmDiscard = true else onClose() }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    val savedText = stringResource(R.string.editor_saved)
    val failedText = stringResource(R.string.editor_save_failed, "%s")
    val photoBad = stringResource(R.string.editor_photo_bad)
    LaunchedEffect(photoError) {
        if (photoError) { snackbar.showSnackbar(photoBad); vm.clearPhotoError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val shown = contact?.name?.display?.takeIf { it.isNotBlank() } ?: stringResource(R.string.no_name)
                    Text(shown, maxLines = 1, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
                },
                navigationIcon = {
                    IconButton(onClick = requestClose) { Icon(Icons.Default.Close, stringResource(R.string.editor_close)) }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.editor_undo))
                    }
                    IconButton(onClick = vm::redo, enabled = canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo))
                    }
                    TextButton(
                        enabled = dirty && !readOnly && !saving,
                        onClick = {
                            scope.launch {
                                val c = vm.contact.value
                                val r = vm.save()
                                val id = c?.id
                                when {
                                    id != null && id in r.written -> snackbar.showSnackbar(savedText)
                                    id != null && r.failed[id] != null -> snackbar.showSnackbar(failedText.replace("%s", r.failed.getValue(id)))
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.editor_save)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val c = contact
            when {
                status == EditorStatus.Loading || (c == null && status == EditorStatus.Ready) ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                status == EditorStatus.PhotoUnavailable -> Problem(stringResource(R.string.editor_photo_unavailable), stringResource(R.string.editor_retry), vm::reload)
                status == EditorStatus.Gone -> Problem(stringResource(R.string.editor_gone), null, {})
                status is EditorStatus.Error -> Problem(
                    stringResource(R.string.editor_load_error, (status as EditorStatus.Error).message),
                    stringResource(R.string.editor_retry), vm::reload,
                )
                c != null -> EditorBody(c, vm, readOnly, saveError, groupChoices)
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_body)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text(stringResource(R.string.editor_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.editor_keep_editing)) } },
        )
    }
}

@Composable
private fun Problem(message: String, action: String?, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun EditorBody(c: Contact, vm: ContactEditorViewModel, readOnly: Boolean, saveError: String?, groupChoices: List<String>) {
    val enabled = !readOnly
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (readOnly) Banner(stringResource(R.string.editor_read_only))
        if (saveError != null) Banner(stringResource(R.string.editor_stale, saveError), stringResource(R.string.editor_reload), vm::reload)

        PhotoSection(c, enabled, onPicked = { b -> if (b == null) vm.reportPhotoError() else vm.setPhoto(b) })

        Section(stringResource(R.string.editor_section_name)) {
            fun name(key: String, f: (Name) -> Name) = vm.edit(key) { it.copy(name = f(it.name)) }
            Field(c.name.display, stringResource(R.string.field_display), enabled) { v -> name("display") { it.copy(display = v) } }
            Field(c.name.prefix, stringResource(R.string.field_prefix), enabled) { v -> name("prefix") { it.copy(prefix = v) } }
            Field(c.name.first, stringResource(R.string.field_first), enabled) { v -> name("first") { it.copy(first = v) } }
            Field(c.name.middle, stringResource(R.string.field_middle), enabled) { v -> name("middle") { it.copy(middle = v) } }
            Field(c.name.last, stringResource(R.string.field_last), enabled) { v -> name("last") { it.copy(last = v) } }
            Field(c.name.suffix, stringResource(R.string.field_suffix), enabled) { v -> name("suffix") { it.copy(suffix = v) } }
            Field(c.name.phoneticFirst, stringResource(R.string.field_phonetic_first), enabled) { v -> name("pfirst") { it.copy(phoneticFirst = v) } }
            Field(c.name.phoneticLast, stringResource(R.string.field_phonetic_last), enabled) { v -> name("plast") { it.copy(phoneticLast = v) } }
            Field(c.name.nickname, stringResource(R.string.field_nickname), enabled) { v -> name("nick") { it.copy(nickname = v) } }
        }

        TypedSection("phones", stringResource(R.string.editor_section_phones), stringResource(R.string.field_phone), stringResource(R.string.editor_add_phone),
            c.phones, PHONE_LABELS, "Mobile", KeyboardType.Phone, enabled, vm) { c, l -> c.copy(phones = l) }
        TypedSection("emails", stringResource(R.string.editor_section_emails), stringResource(R.string.field_email), stringResource(R.string.editor_add_email),
            c.emails, EMAIL_LABELS, "Home", KeyboardType.Email, enabled, vm) { c, l -> c.copy(emails = l) }
        AddressSection(c.addresses, enabled, vm)

        Section(stringResource(R.string.editor_section_org)) {
            fun org(key: String, f: (com.ilukezippo.vcfdesk.core.model.Org) -> com.ilukezippo.vcfdesk.core.model.Org) = vm.edit(key) { it.copy(org = f(it.org)) }
            Field(c.org.company, stringResource(R.string.field_company), enabled) { v -> org("company") { it.copy(company = v) } }
            Field(c.org.department, stringResource(R.string.field_department), enabled) { v -> org("dept") { it.copy(department = v) } }
            Field(c.org.title, stringResource(R.string.field_title), enabled) { v -> org("title") { it.copy(title = v) } }
        }

        TypedSection("websites", stringResource(R.string.editor_section_websites), stringResource(R.string.field_website), stringResource(R.string.editor_add_website),
            c.websites, WEBSITE_LABELS, "Homepage", KeyboardType.Uri, enabled, vm) { c, l -> c.copy(websites = l) }
        TypedSection("events", stringResource(R.string.editor_section_events), stringResource(R.string.field_event), stringResource(R.string.editor_add_event),
            c.events, EVENT_LABELS, "birthday", KeyboardType.Text, enabled, vm) { c, l -> c.copy(events = l) }
        TypedSection("relations", stringResource(R.string.editor_section_relations), stringResource(R.string.field_relation), stringResource(R.string.editor_add_relation),
            c.relations, RELATION_LABELS, "Spouse", KeyboardType.Text, enabled, vm) { c, l -> c.copy(relations = l) }
        TypedSection("ims", stringResource(R.string.editor_section_ims), stringResource(R.string.field_im), stringResource(R.string.editor_add_im),
            c.ims, IM_LABELS, "Skype", KeyboardType.Text, enabled, vm) { c, l -> c.copy(ims = l) }

        Section(stringResource(R.string.editor_section_notes)) {
            Field(c.notes, stringResource(R.string.field_notes), enabled, singleLine = false) { v -> vm.edit("notes") { it.copy(notes = v) } }
        }

        GroupSection(c.groups, groupChoices, enabled, vm)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A text field that follows the direction of what is typed (Arabic stays right to left). */
@Composable
private fun Field(
    value: String,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
        modifier = modifier,
    )
}

@Composable
private fun PhotoSection(c: Contact, enabled: Boolean, onPicked: (ByteArray?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var viewing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }
            }
            onPicked(bytes)
        }
    }
    val bitmap by produceState<ImageBitmap?>(null, c.photo, c.thumbnail) {
        val b = c.photo ?: c.thumbnail
        value = if (b == null) null else withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val view = stringResource(R.string.editor_photo_view)
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = bitmap != null, onClickLabel = view) { viewing = true },
            contentAlignment = Alignment.Center,
        ) {
            val b = bitmap
            if (b != null) Image(b, stringResource(R.string.editor_photo), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.Person, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            enabled = enabled,
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        ) { Text(stringResource(R.string.editor_photo_change)) }
    }
    val full = bitmap
    if (viewing && full != null) {
        Dialog(onDismissRequest = { viewing = false }) {
            Image(full, stringResource(R.string.editor_photo), Modifier.fillMaxWidth().clickable { viewing = false }, contentScale = ContentScale.Fit)
        }
    }
}

private fun shown(label: String) = label.replaceFirstChar { it.uppercase() }

/** Label menu: the standard labels, then "Custom…" which turns the label into a text field. */
@Composable
private fun LabelPicker(label: String, options: List<String>, enabled: Boolean, onChange: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(label.isNotBlank() && label !in options) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (custom) {
            Field(label, stringResource(R.string.editor_custom_label), enabled, Modifier.weight(1f)) { onChange(it) }
        }
        Box {
            TextButton(onClick = { menu = true }, enabled = enabled) {
                if (!custom) Text(if (label.isBlank()) stringResource(R.string.editor_label) else shown(label))
                Icon(Icons.Default.ArrowDropDown, stringResource(R.string.editor_label))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (o in options) DropdownMenuItem(text = { Text(shown(o)) }, onClick = { custom = false; menu = false; onChange(o) })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_custom)) },
                    onClick = { custom = true; menu = false; if (label in options) onChange("") },
                )
            }
        }
    }
}

@Composable
private fun TypedSection(
    key: String,
    title: String,
    valueLabel: String,
    addText: String,
    items: List<Typed>,
    labels: List<String>,
    defaultLabel: String,
    keyboard: KeyboardType,
    enabled: Boolean,
    vm: ContactEditorViewModel,
    set: (Contact, List<Typed>) -> Contact,
) {
    Section(title) {
        items.forEachIndexed { i, t ->
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    LabelPicker(t.label, labels, enabled) { l -> vm.edit { c -> set(c, items.mapIndexed { j, x -> if (j == i) x.copy(label = l) else x }) } }
                    Field(t.value, valueLabel, enabled, keyboardType = keyboard) { v ->
                        vm.edit("$key$i") { c -> set(c, items.mapIndexed { j, x -> if (j == i) x.copy(value = v) else x }) }
                    }
                }
                IconButton(onClick = { vm.edit { c -> set(c, items.filterIndexed { j, _ -> j != i }) } }, enabled = enabled) {
                    Icon(Icons.Default.Delete, stringResource(R.string.editor_remove))
                }
            }
        }
        TextButton(onClick = { vm.edit { c -> set(c, items + Typed(defaultLabel, "")) } }, enabled = enabled) {
            Icon(Icons.Default.Add, null)
            Text(addText)
        }
    }
}

@Composable
private fun AddressSection(items: List<Address>, enabled: Boolean, vm: ContactEditorViewModel) {
    Section(stringResource(R.string.editor_section_addresses)) {
        items.forEachIndexed { i, a ->
            fun upd(key: String?, f: (Address) -> Address) =
                vm.edit(key) { c -> c.copy(addresses = c.addresses.mapIndexed { j, x -> if (j == i) f(x) else x }) }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelPicker(a.label, ADDRESS_LABELS, enabled) { l -> upd(null) { it.copy(label = l) } }
                    Field(a.street, stringResource(R.string.field_street), enabled) { v -> upd("street$i") { it.copy(street = v) } }
                    Field(a.poBox, stringResource(R.string.field_pobox), enabled) { v -> upd("pobox$i") { it.copy(poBox = v) } }
                    Field(a.extended, stringResource(R.string.field_extended), enabled) { v -> upd("ext$i") { it.copy(extended = v) } }
                    Field(a.city, stringResource(R.string.field_city), enabled) { v -> upd("city$i") { it.copy(city = v) } }
                    Field(a.region, stringResource(R.string.field_region), enabled) { v -> upd("region$i") { it.copy(region = v) } }
                    Field(a.postcode, stringResource(R.string.field_postcode), enabled) { v -> upd("post$i") { it.copy(postcode = v) } }
                    Field(a.country, stringResource(R.string.field_country), enabled) { v -> upd("country$i") { it.copy(country = v) } }
                }
                IconButton(onClick = { vm.edit { c -> c.copy(addresses = c.addresses.filterIndexed { j, _ -> j != i }) } }, enabled = enabled) {
                    Icon(Icons.Default.Delete, stringResource(R.string.editor_remove))
                }
            }
        }
        TextButton(onClick = { vm.edit { c -> c.copy(addresses = c.addresses + Address(label = "Home")) } }, enabled = enabled) {
            Icon(Icons.Default.Add, null)
            Text(stringResource(R.string.editor_add_address))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSection(groups: Set<String>, choices: List<String>, enabled: Boolean, vm: ContactEditorViewModel) {
    var menu by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf("") }
    Section(stringResource(R.string.editor_section_groups)) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (g in groups) {
                FilterChip(
                    selected = true, enabled = enabled,
                    onClick = { vm.edit { it.copy(groups = it.groups - g) } },
                    label = { Text(g, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content)) },
                    trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.editor_remove_group, g), Modifier.size(16.dp)) },
                )
            }
        }
        val remaining = choices.filter { it !in groups }
        if (remaining.isNotEmpty()) Box {
            TextButton(onClick = { menu = true }, enabled = enabled) {
                Icon(Icons.Default.Add, null)
                Text(stringResource(R.string.editor_add_group))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (g in remaining) DropdownMenuItem(text = { Text(g) }, onClick = { menu = false; vm.edit { it.copy(groups = it.groups + g) } })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(typing, stringResource(R.string.field_group), enabled, Modifier.weight(1f)) { typing = it }
            TextButton(
                enabled = enabled && typing.isNotBlank(),
                onClick = { val g = typing.trim(); typing = ""; vm.edit { it.copy(groups = it.groups + g) } },
            ) { Text(stringResource(R.string.editor_add)) }
        }
    }
}
