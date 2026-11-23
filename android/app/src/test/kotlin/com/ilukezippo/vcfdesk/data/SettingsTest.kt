package com.ilukezippo.vcfdesk.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = Settings(context)

    @After
    fun clear() = runTest { context.settingsDataStore.edit { it.clear() } }

    @Test
    fun defaultsAndPersistence() = runTest {
        assertEquals("+965", settings.countryCode.first())
        assertEquals(emptyList<String>(), settings.googleAccounts.first())

        settings.setCountryCode("+966")
        settings.addGoogleAccount("  Me@Example.com ")
        assertEquals("+966", settings.countryCode.first())
        assertEquals(listOf("me@example.com"), settings.googleAccounts.first())
    }

    @Test
    fun accountsAreIdempotentSortedAndRemovable() = runTest {
        settings.addGoogleAccount("b@x.com")
        settings.addGoogleAccount("a@x.com")
        settings.addGoogleAccount("A@x.com")
        assertEquals(listOf("a@x.com", "b@x.com"), settings.googleAccounts.first())

        settings.removeGoogleAccount(" B@X.com")
        settings.removeGoogleAccount("missing@x.com")
        assertEquals(listOf("a@x.com"), settings.googleAccounts.first())
    }
}
