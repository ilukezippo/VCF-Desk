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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ilukezippo.vcfdesk.data.google.GoogleAuth
import com.ilukezippo.vcfdesk.ui.AppActions
import com.ilukezippo.vcfdesk.ui.AppScaffold
import com.ilukezippo.vcfdesk.ui.list.ContactListViewModel
import com.ilukezippo.vcfdesk.ui.list.SystemAccountSyncChecker
import com.ilukezippo.vcfdesk.ui.theme.VcfDeskTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as VcfDeskApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val auth = GoogleAuth(this)
        container.googleAuth = auth
        setContent {
            VcfDeskTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionGate {
                        val vm: ContactListViewModel = viewModel(factory = ListViewModelFactory(container, applicationContext))
                        AppScaffold(
                            vm = vm,
                            actions = AppActions(),
                            pickGoogleAccount = { pickAndAuthorise(auth) },
                        )
                    }
                }
            }
        }
    }

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
