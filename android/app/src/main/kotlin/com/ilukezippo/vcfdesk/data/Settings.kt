package com.ilukezippo.vcfdesk.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// One DataStore per process; a second instance for the same file would throw.
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

class Settings(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    /** Prefix applied to 8-digit local numbers. */
    val countryCode: Flow<String> = store.data.map { it[COUNTRY_CODE] ?: DEFAULT_COUNTRY_CODE }

    /** Linked Google accounts, trimmed, lowercased, sorted. */
    val googleAccounts: Flow<List<String>> = store.data.map { (it[GOOGLE_ACCOUNTS] ?: emptySet()).sorted() }

    suspend fun setCountryCode(v: String) {
        store.edit { it[COUNTRY_CODE] = v.trim() }
    }

    suspend fun addGoogleAccount(email: String) {
        val e = normalize(email)
        if (e.isEmpty()) return
        store.edit { it[GOOGLE_ACCOUNTS] = (it[GOOGLE_ACCOUNTS] ?: emptySet()) + e }
    }

    suspend fun removeGoogleAccount(email: String) {
        val e = normalize(email)
        store.edit { it[GOOGLE_ACCOUNTS] = (it[GOOGLE_ACCOUNTS] ?: emptySet()) - e }
    }

    private fun normalize(email: String) = email.trim().lowercase()

    companion object {
        const val DEFAULT_COUNTRY_CODE = "+965"
        private val COUNTRY_CODE = stringPreferencesKey("country_code")
        private val GOOGLE_ACCOUNTS = stringSetPreferencesKey("google_accounts")
    }
}
