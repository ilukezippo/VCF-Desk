package com.ilukezippo.vcfdesk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ilukezippo.vcfdesk.data.google.GoogleAuth
import com.ilukezippo.vcfdesk.ui.AppActions
import com.ilukezippo.vcfdesk.ui.AppNav
import com.ilukezippo.vcfdesk.ui.Screen
import com.ilukezippo.vcfdesk.ui.backups.BackupsScreen
import com.ilukezippo.vcfdesk.ui.backups.BackupsViewModel
import com.ilukezippo.vcfdesk.ui.io.ExportScreen
import com.ilukezippo.vcfdesk.ui.io.ExportViewModel
import com.ilukezippo.vcfdesk.ui.io.ImportScreen
import com.ilukezippo.vcfdesk.ui.io.ImportViewModel
import com.ilukezippo.vcfdesk.ui.io.readCapped
import com.ilukezippo.vcfdesk.ui.io.openOutputForWrite
import com.ilukezippo.vcfdesk.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import java.io.File
import com.ilukezippo.vcfdesk.ui.AppScaffold
import com.ilukezippo.vcfdesk.ui.DefaultDetail
import com.ilukezippo.vcfdesk.ui.DetailContent
import com.ilukezippo.vcfdesk.data.SourceId
import com.ilukezippo.vcfdesk.ui.bulk.BulkEditSheet
import com.ilukezippo.vcfdesk.ui.bulk.BulkHolder
import com.ilukezippo.vcfdesk.ui.bulk.BulkStageDialogs
import com.ilukezippo.vcfdesk.ui.bulk.BulkViewModel
import com.ilukezippo.vcfdesk.ui.editor.AndroidPhotoScaler
import com.ilukezippo.vcfdesk.ui.editor.ContactEditorScreen
import com.ilukezippo.vcfdesk.ui.editor.ContactEditorViewModel
import com.ilukezippo.vcfdesk.ui.editor.EditorHolder
import com.ilukezippo.vcfdesk.ui.editor.EditorHolderViewModel
import com.ilukezippo.vcfdesk.ui.list.ContactListViewModel
import com.ilukezippo.vcfdesk.ui.list.SystemAccountSyncChecker
import com.ilukezippo.vcfdesk.ui.theme.VcfDeskTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as VcfDeskApp).container
    private val nav: AppNav by viewModels()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        nav.handle(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val auth = GoogleAuth(this)
        container.googleAuth = auth
        // "Open with VCF Desk" goes straight to Import; a rotation must not re-open the same file
        if (savedInstanceState == null) nav.handle(intent)
        setContent {
            VcfDeskTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionGate {
                        val vm: ContactListViewModel = viewModel(factory = ListViewModelFactory(container, applicationContext))
                        val screen by nav.screen.collectAsStateWithLifecycle()
                        val actions = remember(nav) {
                            AppActions(
                                onImport = { nav.go(Screen.Import) },
                                onExport = { nav.go(Screen.Export) },
                                onBackups = { nav.go(Screen.Backups) },
                                onSettings = { nav.go(Screen.Settings) },
                            )
                        }
                        val holderVm: EditorHolderViewModel = viewModel(factory = EditorHolderFactory(container))
                        val holder = holderVm.holder
                        val bulkHolderVm: BulkHolderViewModel = viewModel(factory = BulkHolderFactory(container))
                        val bulkHolder = bulkHolderVm.holder
                        val bulkVm by bulkHolder.current.collectAsStateWithLifecycle()
                        val listState by vm.state.collectAsStateWithLifecycle()
                        val pending by holder.pendingSwitch.collectAsStateWithLifecycle()
                        var openRequest by remember { mutableStateOf<String?>(null) }
                        val discard = {
                            val t = holder.confirmDiscard()
                            if (t is EditorHolder.PendingTarget.OpenContact) openRequest = t.id
                        }
                        val keep = {
                            when (val t = holder.keepEditing()) {
                                EditorHolder.PendingTarget.Selection -> vm.clearSelection()
                                is EditorHolder.PendingTarget.SourceSwitch -> vm.switchSource(t.back)
                                else -> {}
                            }
                        }
                        Box(Modifier.fillMaxSize()) {
                        AppScaffold(
                            vm = vm,
                            actions = actions,
                            pickGoogleAccount = { pickAndAuthorise(auth) },
                            beforeOpen = { id ->
                                val cur = holder.current.value
                                (cur != null && cur.id == id && cur.source == vm.state.value.source) ||
                                    holder.requestSwitch(EditorHolder.PendingTarget.OpenContact(id))
                            },
                            keepOpen = { id ->
                                val cur = holder.current.value
                                cur != null && cur.id == id && cur.source == vm.state.value.source
                            },
                            openRequest = openRequest,
                            onOpenRequestHandled = { openRequest = null },
                            detail = { content, onClose ->
                                if (content is DetailContent.Contact) {
                                    val editor = holder.open(vm.state.value.source, content.id)
                                    ContactEditorScreen(editor) { holder.close(); onClose() }
                                } else {
                                    // the contact was left some other way (selection, source change): drop a clean editor, ask about a dirty one
                                    LaunchedEffect(content) {
                                        val open = holder.current.value
                                        if (open != null) {
                                            holder.requestSwitch(
                                                when {
                                                    content is DetailContent.Selection -> EditorHolder.PendingTarget.Selection
                                                    vm.state.value.source != open.source -> EditorHolder.PendingTarget.SourceSwitch(open.source)
                                                    else -> EditorHolder.PendingTarget.Leave
                                                },
                                            )
                                        }
                                    }
                                    if (content is DetailContent.Selection) {
                                        val bulk = bulkHolder.forSelection(listState.source, content.ids)
                                        BulkEditSheet(
                                            vm = bulk,
                                            groups = listState.groups,
                                            googleSources = listState.sources.filterIsInstance<SourceId.Google>(),
                                            selectionAccounts = remember(listState.selected, listState.source, listState.items) { vm.selectionAccounts() },
                                            sourceFor = container::source,
                                        )
                                    } else {
                                        DefaultDetail(content, actions)
                                    }
                                }
                            },
                        )
                        if (screen != Screen.Main) {
                            // above the list, which stays composed so an open editor and the scroll position survive
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                OverlayScreen(screen, vm, listState)
                            }
                        }
                        }
                        // previews, progress and results outlive the selection they were made from
                        bulkVm?.let { b ->
                            BulkStageDialogs(b) { bulkHolder.release(); vm.clearSelection() }
                        }
                        if (pending != null) {
                            AlertDialog(
                                onDismissRequest = keep,
                                title = { Text(stringResource(R.string.editor_discard_title)) },
                                text = { Text(stringResource(R.string.editor_discard_body)) },
                                confirmButton = { TextButton(onClick = discard) { Text(stringResource(R.string.editor_discard)) } },
                                dismissButton = { TextButton(onClick = keep) { Text(stringResource(R.string.editor_keep_editing)) } },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Import, Export, Backups and Settings: full-screen, over the list. */
    @Composable
    private fun OverlayScreen(screen: Screen, vm: ContactListViewModel, listState: com.ilukezippo.vcfdesk.ui.list.ListState) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val importing = stringResource(R.string.import_in_progress)
        val leave: () -> Unit = {
            if (screen == Screen.Import && importVmHolder?.isImporting == true) {
                // never cancel a half-done import (it would be re-run and duplicate contacts)
                android.widget.Toast.makeText(context, importing, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                if (screen == Screen.Import) importVmHolder?.reset()
                nav.back()
            }
        }
        BackHandler(onBack = leave)
        when (screen) {
            Screen.Main -> {}
            Screen.Import -> {
                val ivm: ImportViewModel = viewModel(factory = ImportFactory(container, applicationContext))
                importVmHolder = ivm
                val incoming by nav.importUri.collectAsStateWithLifecycle()
                ImportScreen(
                    vm = ivm,
                    incoming = incoming,
                    onUriTaken = nav::importUriTaken,
                    googleSources = listState.sources.filterIsInstance<SourceId.Google>(),
                    sourceFor = container::source,
                    onBack = leave,
                )
            }
            Screen.Export -> {
                val evm: ExportViewModel = viewModel(factory = ExportFactory(applicationContext))
                ExportScreen(
                    vm = evm,
                    source = container.source(listState.source),
                    results = listState.items,
                    selected = listState.selected,
                    onBack = { evm.reset(); nav.back() },
                )
            }
            Screen.Backups -> {
                val bvm: BackupsViewModel = viewModel(factory = BackupsFactory(container))
                LaunchedEffect(Unit) { bvm.refresh() }
                BackupsScreen(bvm, onBack = leave)
            }
            Screen.Settings -> {
                val cc by container.settings.countryCode.collectAsStateWithLifecycle(initialValue = com.ilukezippo.vcfdesk.data.Settings.DEFAULT_COUNTRY_CODE)
                val accounts by container.settings.googleAccounts.collectAsStateWithLifecycle(initialValue = emptyList())
                val failedText = stringResource(R.string.google_sign_in_failed, "%s")
                val unavailable = stringResource(R.string.google_sign_in_unavailable)
                SettingsScreen(
                    countryCode = cc,
                    googleAccounts = accounts,
                    onSaveCountryCode = { v -> scope.launch { container.settings.setCountryCode(v) } },
                    onAddGoogle = {
                        scope.launch {
                            try {
                                val auth = container.googleAuth
                                val email = if (auth != null) pickAndAuthorise(auth) else null
                                if (email != null) vm.addGoogleAccount(email)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, failedText.replace("%s", e.message ?: unavailable), android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onRemoveGoogle = { email ->
                        if (listState.source == SourceId.Google(email)) vm.switchSource(SourceId.Device)
                        scope.launch { container.settings.removeGoogleAccount(email) }
                    },
                    onBack = leave,
                )
            }
        }
    }

    private var importVmHolder: ImportViewModel? = null

    override fun onDestroy() {
        if (container.googleAuth != null && isFinishing) container.googleAuth = null
        super.onDestroy()
    }

    /** Account chooser, then one token request so the consent screen shows now; null if the user backed out. */
    private suspend fun pickAndAuthorise(auth: GoogleAuth): String? {
        val email = auth.pickAccount() ?: return null
        auth.token(email)
        return email
    }
}

private class ListViewModelFactory(
    private val container: AppContainer,
    private val context: android.content.Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ContactListViewModel(
        sourceFor = container::source,
        googleAccounts = container.settings.googleAccounts,
        saveGoogleAccount = container.settings::addGoogleAccount,
        syncChecker = SystemAccountSyncChecker(context),
    ) as T
}

private class EditorHolderFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorHolderViewModel(
        EditorHolder { source, id -> ContactEditorViewModel({ container.source(source) }, id, AndroidPhotoScaler()) },
    ) as T
}

private class BulkHolderViewModel(val holder: BulkHolder) : ViewModel() {
    override fun onCleared() = holder.release()
}

private class BulkHolderFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = BulkHolderViewModel(
        BulkHolder { source, ids ->
            BulkViewModel(container.bulkRunner, source, container::source, ids, container.settings.countryCode)
        },
    ) as T
}

private class ImportFactory(private val container: AppContainer, private val context: android.content.Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ImportViewModel({ uri -> context.contentResolver.readCapped(uri) }, container.settings.countryCode) as T
}

private class ExportFactory(private val context: android.content.Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ExportViewModel(
        openOutput = { uri -> context.contentResolver.openOutputForWrite(uri) },
        shareFile = { name -> File(File(context.cacheDir, "share"), name) },
    ) as T
}

private class BackupsFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        BackupsViewModel(container.backupStore, container.bulkRunner, container::source, container.settings.googleAccounts) as T
}

private val PERMISSIONS = arrayOf(
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.WRITE_CONTACTS,
    Manifest.permission.GET_ACCOUNTS,
)

/** Shows [content] once contacts access is granted; otherwise asks, then explains with a retry button. */
@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun granted() = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var ok by remember { mutableStateOf(granted()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ok = granted()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ok = granted() }
    if (ok) {
        content()
        return
    }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            launcher.launch(PERMISSIONS)
        }
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.perm_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { launcher.launch(PERMISSIONS) }) { Text(stringResource(R.string.perm_retry)) }
        TextButton(onClick = {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }) { Text(stringResource(R.string.perm_open_settings)) }
    }
}
