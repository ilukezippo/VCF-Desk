package com.ilukezippo.vcfdesk.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ilukezippo.vcfdesk.BuildConfig
import com.ilukezippo.vcfdesk.R
import com.ilukezippo.vcfdesk.ui.io.CountryCode

const val GITHUB_URL = "https://github.com/ilukezippo/VCF-Desk"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    countryCode: String,
    googleAccounts: List<String>,
    onSaveCountryCode: (String) -> Unit,
    onAddGoogle: () -> Unit,
    onRemoveGoogle: (String) -> Unit,
    onBack: () -> Unit,
) {
    var text by remember(countryCode) { mutableStateOf(countryCode) }
    var saved by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    val valid = CountryCode.valid(text)
    val uri = LocalUriHandler.current
    LaunchedEffect(text) { saved = false }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_country_code), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = !valid,
                    supportingText = { Text(stringResource(if (valid) R.string.settings_country_code_hint else R.string.settings_country_code_bad)) },
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                Button(
                    enabled = valid && text.trim() != countryCode,
                    onClick = { onSaveCountryCode(text.trim()); saved = true },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(stringResource(R.string.settings_save)) }
            }
            if (saved) Text(stringResource(R.string.settings_saved), color = MaterialTheme.colorScheme.primary)

            HorizontalDivider()
            Text(stringResource(R.string.settings_google), style = MaterialTheme.typography.titleMedium)
            if (googleAccounts.isEmpty()) {
                Text(stringResource(R.string.settings_google_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            googleAccounts.forEach { email ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(email, Modifier.weight(1f))
                    TextButton(onClick = { removing = email }) { Text(stringResource(R.string.settings_google_remove)) }
                }
            }
            OutlinedButton(onClick = onAddGoogle) { Text(stringResource(R.string.settings_google_add)) }

            HorizontalDivider()
            Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider()
            Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
            TextButton(onClick = { uri.openUri(GITHUB_URL) }) { Text(stringResource(R.string.settings_github)) }
        }
    }

    removing?.let { email ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.settings_google_remove_title, email)) },
            text = { Text(stringResource(R.string.settings_google_remove_body)) },
            confirmButton = { TextButton(onClick = { removing = null; onRemoveGoogle(email) }) { Text(stringResource(R.string.settings_google_remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
