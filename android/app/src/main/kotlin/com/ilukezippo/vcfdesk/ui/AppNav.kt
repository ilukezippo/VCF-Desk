package com.ilukezippo.vcfdesk.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The full-screen destinations above the contact list. */
enum class Screen { Main, Import, Export, Backups, Settings }

/** The file another app handed us ("Open with VCF Desk" / Share), or null for any other intent. */
fun importUriFrom(intent: Intent?): Uri? {
    intent ?: return null
    return when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: intent.data
        else -> null
    }
}

/** Which screen is showing; survives rotation. */
class AppNav : ViewModel() {
    private val _screen = MutableStateFlow(Screen.Main)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _importUri = MutableStateFlow<Uri?>(null)
    /** A file waiting to be opened by the Import screen. */
    val importUri: StateFlow<Uri?> = _importUri.asStateFlow()

    fun go(s: Screen) { _screen.value = s }

    fun back() { _screen.value = Screen.Main }

    /** Routes a launch / new intent: a file to import goes straight to Import. Returns whether it did. */
    fun handle(intent: Intent?): Boolean {
        val uri = importUriFrom(intent) ?: return false
        _importUri.value = uri
        _screen.value = Screen.Import
        return true
    }

    fun importUriTaken() { _importUri.value = null }
}
