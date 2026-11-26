package com.ilukezippo.vcfdesk.ui.bulk

import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.ilukezippo.vcfdesk.data.device.FakeContactsProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WritableAccountsTest {
    @Test fun accountsHiddenFromAccountManagerAreFoundInTheContactsStore() {
        val fake = Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        fake.addRaw("com.google", "name@gmail.com")
        fake.addRaw("com.google", "name@gmail.com")
        fake.addRaw("vnd.sec.contact.phone", "vnd.sec.contact.phone")
        fake.addRaw("com.google", "gone@gmail.com", deleted = true)
        fake.addRaw("com.whatsapp", "WhatsApp")
        val got = writableDeviceAccounts(ApplicationProvider.getApplicationContext())
        assertEquals(listOf("vnd.sec.contact.phone" to "vnd.sec.contact.phone", "com.google" to "name@gmail.com"), got.map { it.type to it.name })
    }
}
