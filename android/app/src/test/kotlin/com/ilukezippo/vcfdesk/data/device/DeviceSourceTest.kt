package com.ilukezippo.vcfdesk.data.device

import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Typed
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fake: FakeContactsProvider
    private val whatsappType = "com.whatsapp"
    private val whatsappMime = "vnd.android.cursor.item/vnd.com.whatsapp.profile"

    private val accounts = object : DeviceAccounts {
        override fun tag(type: String?, name: String?) = AccountTag(
            type.orEmpty(), name.orEmpty(),
            if (type == null) "Phone" else type, readOnly = type == whatsappType,
        )
    }

    @Before
    fun setUp() {
        fake = Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
    }

    private fun source(scope: kotlinx.coroutines.test.TestScope) =
        DeviceSource(context, accounts, UnconfinedTestDispatcher(scope.testScheduler))

    private fun person(n: Int, type: String? = null, deleted: Boolean = false): Long {
        val id = fake.addRaw(type, if (type == null) null else "me", deleted)
        fake.addRow(id, StructuredName.CONTENT_ITEM_TYPE, "data1" to "Name $n", "data2" to "Name $n")
        fake.addRow(id, Phone.CONTENT_ITEM_TYPE, "data1" to "5555555$n", "data2" to "2")
        return id
    }

    @Test
    fun readOnlyAccountsAreSkipped() = runTest {
        val id = person(1, whatsappType)
        val src = source(this)
        val c = src.load(id.toString())
        assertTrue(c.account!!.readOnly)
        fake.calls.clear()
        val r = src.update(listOf(c.copy(notes = "changed")))
        assertEquals(listOf(id.toString()), r.skippedReadOnly)
        assertEquals(emptyList<String>(), r.written)
        assertEquals(emptyList<String>(), fake.calls)
    }

    @Test
    fun readOnlyAccountIsRecheckedAgainstTheProvider() = runTest {
        val id = person(1, whatsappType)
        val src = source(this)
        val c = src.load(id.toString()).let { it.copy(account = it.account!!.copy(readOnly = false)) }
        fake.calls.clear()
        val r = src.update(listOf(c.copy(notes = "changed")))
        assertEquals(listOf(id.toString()), r.skippedReadOnly)
        assertEquals(emptyList<String>(), fake.writeCalls())
    }

    @Test
    fun batchesAt400Ops() = runTest {
        val ids = (1..250).map { person(it) }
        val src = source(this)
        val loaded = ids.map { src.load(it.toString()) }
        fake.calls.clear()
        val edited = loaded.map { c ->
            c.copy(phones = listOf(c.phones[0].copy(value = c.phones[0].value + "0")), emails = listOf(Typed("Work", "x@example.com")))
        }
        var last = 0
        val r = src.update(edited) { last = it }
        assertEquals(250, r.written.size)
        assertEquals(emptyMap<String, String>(), r.failed)
        assertEquals(listOf("applyBatch 400", "applyBatch 100"), fake.calls.filter { it.startsWith("applyBatch") })
        assertEquals(250, last)
        // first op of each contact allows yielding, the second does not
        val b = fake.batches[0]
        assertTrue(b[0].isYieldAllowed); assertFalse(b[1].isYieldAllowed); assertTrue(b[2].isYieldAllowed)
        val first = src.load(ids[0].toString())
        assertEquals(listOf("555555510"), first.phones.map { it.value })
        assertEquals(listOf("x@example.com"), first.emails.map { it.value })
    }

    @Test
    fun loadsThumbnailsNotDisplayPhotos() = runTest {
        val id = person(1)
        fake.addRow(id, Photo.CONTENT_ITEM_TYPE, "data15" to byteArrayOf(1, 2))
        fake.displayPhotos[id] = byteArrayOf(9, 9, 9)
        val src = source(this)
        src.contacts().test {
            val list = awaitItem()
            assertArrayEquals(byteArrayOf(1, 2), list.single().thumbnail)
            assertNull(list.single().photo)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(fake.calls.none { it.startsWith("openAssetFile") })
        // load() does read the display photo
        assertArrayEquals(byteArrayOf(9, 9, 9), src.load(id.toString()).photo)
        assertTrue(fake.calls.any { it.startsWith("openAssetFile") && it.endsWith("/raw_contacts/$id/display_photo") })
    }

    @Test
    fun unreadableDisplayPhotoMakesLoadThrow() = runTest {
        val id = person(1)
        // PHOTO_FILE_ID set, but the display photo can't be opened
        fake.addRow(id, Photo.CONTENT_ITEM_TYPE, "data15" to byteArrayOf(1, 2), "data14" to "77")
        val src = source(this)
        val thrown = try { src.load(id.toString()); null } catch (e: com.ilukezippo.vcfdesk.data.PhotoUnavailableException) { e }
        assertTrue(thrown != null)
    }

    @Test
    fun thumbnailOnlyPhotoLoadsWithoutDisplayPhoto() = runTest {
        val id = person(1)
        fake.addRow(id, Photo.CONTENT_ITEM_TYPE, "data15" to byteArrayOf(1, 2))
        val src = source(this)
        assertArrayEquals(byteArrayOf(1, 2), src.load(id.toString()).photo)
    }

    @Test
    fun contactsExcludesDeletedAndRequeriesOnChange() = runTest {
        person(1)
        person(2, deleted = true)
        val src = source(this)
        src.contacts().test {
            assertEquals(listOf("Name 1"), awaitItem().map { it.name.display })
            person(3)
            fake.context!!.contentResolver.notifyChange(ContactsContract.RawContacts.CONTENT_URI, null)
            assertEquals(listOf("Name 1", "Name 3"), expectMostRecentItem().map { it.name.display })
            src.refresh()
            assertEquals(2, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun updateKeepsUnknownRowsAndPhoto() = runTest {
        val id = person(1)
        val wa = fake.addRow(id, whatsappMime, "data1" to "x", "data3" to "Message")
        val photoRow = fake.addRow(id, Photo.CONTENT_ITEM_TYPE, "data15" to byteArrayOf(1), "data14" to "55")
        val src = source(this)
        val listed = src.contacts().let { f ->
            var v: List<Contact> = emptyList(); f.test { v = awaitItem(); cancelAndIgnoreRemainingEvents() }; v
        }.single()
        val r = src.update(listOf(listed.copy(org = listed.org.copy(company = "Acme"), photo = null, thumbnail = null)))
        assertEquals(listOf(id.toString()), r.written)
        assertEquals(mapOf<String, Any?>("data1" to "x", "data3" to "Message"), fake.data[wa]!!.values)
        assertArrayEquals(byteArrayOf(1), fake.data[photoRow]!!.values["data15"] as ByteArray)
        assertEquals("55", fake.data[photoRow]!!.values["data14"])
        assertEquals("Acme", fake.rowsOf(id).single { it.mimetype == Organization.CONTENT_ITEM_TYPE }.values["data1"])
    }

    @Test
    fun unchangedLoadedContactWritesNothing() = runTest {
        val id = person(1)
        fake.addRow(id, Photo.CONTENT_ITEM_TYPE, "data15" to byteArrayOf(1))
        fake.displayPhotos[id] = byteArrayOf(9, 9)
        val src = source(this)
        val c = src.load(id.toString())
        fake.calls.clear()
        val r = src.update(listOf(c))
        assertEquals(listOf(id.toString()), r.written)
        assertEquals(emptyList<String>(), fake.writeCalls())
    }

    @Test
    fun failedBatchFallsBackToPerContact() = runTest {
        val a = person(1); val b = person(2)
        val src = source(this)
        val ca = src.load(a.toString()); val cb = src.load(b.toString())
        // fail any batch that touches contact b's rows
        val bRows = fake.rowsOf(b).map { it.id.toString() }.toSet()
        fake.failBatch = { ops -> ops.any { it.uri.lastPathSegment in bRows } }
        val r = src.update(listOf(ca.copy(notes = "a"), cb.copy(phones = listOf(Typed("Mobile", "1")))))
        assertEquals(listOf(a.toString()), r.written)
        assertEquals(setOf(b.toString()), r.failed.keys)
        assertEquals("a", src.load(a.toString()).notes)
    }

    private fun membershipsOf(id: Long) = fake.rowsOf(id)
        .filter { it.mimetype == ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE }
        .map { fake.groups.getValue(it.values["data1"].toString().toLong()) }

    @Test
    fun addToNewGroupCreatesOneGroupPerAccount() = runTest {
        val local = (1..3).map { person(it) }
        val google = (4..5).map { person(it, "com.google") }
        val src = source(this)
        val all = (local + google).map { src.load(it.toString()) }
        val r = src.update(all.map { it.copy(groups = setOf("Friends")) })
        assertEquals((local + google).map { it.toString() }, r.written)
        assertEquals(emptyMap<String, String>(), r.failed)
        val friends = fake.groups.values.filter { it.title == "Friends" }
        assertEquals(2, friends.size)
        assertTrue(friends.all { it.visible })
        assertEquals(setOf(null, "com.google"), friends.map { it.type }.toSet())
        for (id in local) assertEquals(listOf<String?>(null), membershipsOf(id).map { it.type })
        for (id in google) assertEquals(listOf<String?>("com.google"), membershipsOf(id).map { it.type })
        // group and memberships land in the same batch
        assertEquals(1, fake.batches.size)
        assertEquals("Friends", src.load(local[0].toString()).groups.single())
    }

    @Test
    fun newGroupIsCreatedOnceAcrossBatches() = runTest {
        val ids = (1..250).map { person(it) }
        val src = source(this)
        val edited = ids.map { src.load(it.toString()) }.map { c ->
            c.copy(groups = setOf("Friends"), phones = listOf(c.phones[0].copy(value = c.phones[0].value + "0")))
        }
        val r = src.update(edited)
        assertEquals(250, r.written.size)
        assertEquals(2, fake.batches.size)
        assertEquals(1, fake.groups.values.count { it.title == "Friends" })
        for (id in ids) assertEquals(listOf("Friends"), membershipsOf(id).map { it.title })
    }

    @Test
    fun groupThatCannotBeCreatedFailsTheContact() = runTest {
        val a = person(1); val b = person(2)
        val src = source(this)
        val ca = src.load(a.toString()); val cb = src.load(b.toString())
        fake.failGroupInsert = true
        val r = src.update(listOf(ca.copy(groups = setOf("Friends")), cb.copy(notes = "b")))
        assertEquals(listOf(b.toString()), r.written)
        assertEquals("group Friends could not be created in Phone", r.failed[a.toString()])
        assertTrue(membershipsOf(a).isEmpty())
        assertEquals("b", src.load(b.toString()).notes)

        val created = src.create(listOf(Contact(id = "x", name = Name(display = "Name 3"), groups = setOf("Friends"))), null)
        assertEquals(emptyList<String>(), created.written)
        assertEquals("group Friends could not be created in Phone", created.failed["x"])
    }

    @Test
    fun createAddsToNewGroupInTheTargetAccount() = runTest {
        val src = source(this)
        val g = AccountTag("com.google", "me@example.com", "Google", false)
        val r = src.create((1..3).map { Contact(name = Name(display = "Name $it"), groups = setOf("Imported")) }, g)
        assertEquals(3, r.written.size)
        val imported = fake.groups.values.single { it.title == "Imported" }
        assertEquals("com.google", imported.type); assertEquals("me@example.com", imported.name)
        for (id in r.written) assertEquals(listOf(imported.id), membershipsOf(id.toLong()).map { it.id })
    }

    @Test
    fun editLandsOnLowestIdRowWhateverOrderTheProviderReturns() = runTest {
        val id = fake.addRaw()
        val first = fake.addRow(id, Organization.CONTENT_ITEM_TYPE, "data1" to "First")
        val second = fake.addRow(id, Organization.CONTENT_ITEM_TYPE, "data1" to "Second")
        fake.reverseWhenUnsorted = true
        val src = source(this)
        val c = src.load(id.toString())
        assertEquals("First", c.org.company)
        src.update(listOf(c.copy(org = c.org.copy(company = "Edited"))))
        assertEquals("Edited", fake.data.getValue(first).values["data1"])
        assertEquals("Second", fake.data.getValue(second).values["data1"])
        val dataQueries = fake.calls.filter { it.startsWith("query data") }
        assertTrue(dataQueries.isNotEmpty())
        assertTrue(dataQueries.toString(), dataQueries.all { it.endsWith("order=raw_contact_id ASC, _id ASC") })
    }

    @Test
    fun contactChangedOnPhoneSinceReadIsNotOverwritten() = runTest {
        val id = person(1)
        val src = source(this)
        val c = src.load(id.toString())
        assertEquals("1", c.etag)
        // a sync adds a number after we read the contact
        fake.addRow(id, Phone.CONTENT_ITEM_TYPE, "data1" to "99999999", "data2" to "1")
        fake.raws.getValue(id).version++
        fake.calls.clear()
        val r = src.update(listOf(c.copy(notes = "x")))
        assertEquals(setOf(id.toString()), r.failed.keys)
        assertEquals(emptyList<String>(), fake.writeCalls())
        assertEquals(2, fake.rowsOf(id).count { it.mimetype == Phone.CONTENT_ITEM_TYPE })
    }

    @Test
    fun duplicateIdsInOneUpdateAreRejected() = runTest {
        val id = person(1)
        val src = source(this)
        val c = src.load(id.toString())
        val r = src.update(listOf(c.copy(emails = listOf(Typed("Work", "a@example.com"))), c.copy(notes = "b")))
        assertEquals(listOf(id.toString()), r.written)
        assertEquals(setOf(id.toString()), r.failed.keys)
        assertEquals(1, fake.batches.size)
    }

    @Test
    fun createInsertsRawContactWithTargetAccount() = runTest {
        val src = source(this)
        val c = Contact(name = Name(first = "Name", last = "1", display = "Name 1"), phones = listOf(Typed("Mobile", "55555555")))
        val r = src.create(listOf(c, c.copy(name = Name(display = "Name 2"))), null)
        assertEquals(2, r.written.size)
        val raw = fake.raws.getValue(r.written[0].toLong())
        assertNull(raw.type); assertNull(raw.name)
        assertEquals(listOf("applyBatch 6"), fake.calls.filter { it.startsWith("applyBatch") })
        assertTrue(fake.batches[0][0].isYieldAllowed && fake.batches[0][3].isYieldAllowed)
        assertEquals("Name 1", src.load(r.written[0]).name.display)

        val g = AccountTag("com.google", "me@example.com", "Google", false)
        val r2 = src.create(listOf(c), g)
        val raw2 = fake.raws.getValue(r2.written[0].toLong())
        assertEquals("com.google", raw2.type); assertEquals("me@example.com", raw2.name)
    }

    @Test
    fun deleteSyncsAndSkipsReadOnly() = runTest {
        val a = person(1); val w = person(2, whatsappType)
        val src = source(this)
        val r = src.delete(listOf(a.toString(), w.toString(), "999"))
        assertEquals(listOf(a.toString()), r.written)
        assertEquals(listOf(w.toString()), r.skippedReadOnly)
        assertEquals(setOf("999"), r.failed.keys)
        assertTrue(fake.raws.getValue(a).deleted)
        assertFalse(fake.raws.getValue(w).deleted)
        assertTrue(fake.calls.filter { it.startsWith("delete") }.none { it.contains(ContactsContract.CALLER_IS_SYNCADAPTER) })
    }

    @Test
    fun groupsAndGroupMembershipUseTheContactsAccount() = runTest {
        val id = person(1)
        fake.addGroup("Family")
        val otherAccountFamily = fake.addGroup("Family", "com.google", "me")
        val src = source(this)
        assertEquals(listOf("Family"), src.groups())
        val c = src.load(id.toString())
        src.update(listOf(c.copy(groups = setOf("Family"))))
        val gm = fake.rowsOf(id).single { it.mimetype == ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE }
        assertTrue(gm.values["data1"] != otherAccountFamily.toString())
        assertEquals(setOf("Family"), src.load(id.toString()).groups)
    }
}
