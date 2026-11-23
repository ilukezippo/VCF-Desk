package com.ilukezippo.vcfdesk.data.device

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Address
import com.ilukezippo.vcfdesk.core.model.Extra
import com.ilukezippo.vcfdesk.core.model.Typed
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMapperTest {
    private val phoneAcct = AccountTag("", "", "Phone", false)
    private val whatsapp = "vnd.android.cursor.item/vnd.com.whatsapp.profile"

    private fun row(id: Long, mime: String, vararg v: Pair<String, String?>, blob: ByteArray? = null) =
        DataRow(id, mime, mapOf(*v), blob)

    private val thumb = byteArrayOf(1, 2, 3)

    private val allRows = listOf(
        row(
            1, StructuredName.CONTENT_ITEM_TYPE,
            "data1" to "Dr Name 1 Jr", "data2" to "Name", "data3" to "One", "data4" to "Dr",
            "data5" to "Mid", "data6" to "Jr", "data7" to "Neim", "data9" to "Wan",
        ),
        row(2, Phone.CONTENT_ITEM_TYPE, "data1" to "55555555", "data2" to "2", "is_primary" to "1"),
        row(3, Phone.CONTENT_ITEM_TYPE, "data1" to "99999999", "data2" to "0", "data3" to "Diwaniya"),
        row(4, Phone.CONTENT_ITEM_TYPE, "data1" to "66666666", "data2" to "4"),
        row(5, Email.CONTENT_ITEM_TYPE, "data1" to "name1@example.com", "data2" to "2"),
        row(
            6, StructuredPostal.CONTENT_ITEM_TYPE, "data2" to "1", "data4" to "Street 1", "data5" to "PO 9",
            "data6" to "Block 2", "data7" to "Kuwait City", "data8" to "Capital", "data9" to "13000",
            "data10" to "Kuwait",
        ),
        row(7, Organization.CONTENT_ITEM_TYPE, "data1" to "Acme", "data4" to "Boss", "data5" to "R&D"),
        row(8, Website.CONTENT_ITEM_TYPE, "data1" to "https://example.com", "data2" to "1"),
        row(9, Event.CONTENT_ITEM_TYPE, "data1" to "1990-02-01", "data2" to "3"),
        row(10, Relation.CONTENT_ITEM_TYPE, "data1" to "Name 2", "data2" to "8"),
        row(11, Im.CONTENT_ITEM_TYPE, "data1" to "name1", "data5" to "3"),
        row(12, Note.CONTENT_ITEM_TYPE, "data1" to "ملاحظة"),
        row(13, Nickname.CONTENT_ITEM_TYPE, "data1" to "Nemo"),
        row(14, GroupMembership.CONTENT_ITEM_TYPE, "data1" to "77"),
        row(15, Photo.CONTENT_ITEM_TYPE, blob = thumb),
    )

    private fun base() = DeviceMapper.toContact(42, phoneAcct, allRows, mapOf(77L to "Family"))

    @Test
    fun mapsAllModelledMimetypes() {
        val c = base()
        assertEquals("42", c.id)
        assertEquals(phoneAcct, c.account)
        with(c.name) {
            assertEquals("Dr Name 1 Jr", display); assertEquals("Name", first); assertEquals("One", last)
            assertEquals("Dr", prefix); assertEquals("Mid", middle); assertEquals("Jr", suffix)
            assertEquals("Neim", phoneticFirst); assertEquals("Wan", phoneticLast); assertEquals("Nemo", nickname)
        }
        assertEquals(
            listOf(Typed("Mobile", "55555555", pref = true), Typed("Diwaniya", "99999999"), Typed("Work fax", "66666666")),
            c.phones,
        )
        assertEquals(listOf(Typed("Work", "name1@example.com")), c.emails)
        assertEquals(
            listOf(Address("Home", "Street 1", "Kuwait City", "Capital", "13000", "Kuwait", "PO 9", "Block 2")),
            c.addresses,
        )
        assertEquals("Acme", c.org.company); assertEquals("Boss", c.org.title); assertEquals("R&D", c.org.department)
        assertEquals(listOf(Typed("Homepage", "https://example.com")), c.websites)
        assertEquals(listOf(Typed("birthday", "1990-02-01")), c.events)
        assertEquals(listOf(Typed("Mother", "Name 2")), c.relations)
        assertEquals(listOf(Typed("Skype", "name1")), c.ims)
        assertEquals("ملاحظة", c.notes)
        assertEquals(setOf("Family"), c.groups)
        assertArrayEquals(thumb, c.thumbnail)
        assertNull(c.photo)
        assertEquals(emptyList<Extra>(), c.extras)
        // an unchanged contact diffs to nothing
        assertEquals(emptyList<RowOp>(), DeviceMapper.diff(allRows, c, mapOf("Family" to 77L)))
    }

    @Test
    fun keepsUnknownMimetypeRows() {
        val rows = allRows + row(99, whatsapp, "data1" to "x", "data3" to "Message +965")
        val c = DeviceMapper.toContact(42, phoneAcct, rows, mapOf(77L to "Family"))
        assertTrue(Extra("device", whatsapp) in c.extras)
        val edited = c.copy(
            phones = emptyList(), emails = emptyList(), notes = "", groups = emptySet(),
            name = c.name.copy(first = "Changed"), extras = emptyList(),
        )
        val ops = DeviceMapper.diff(rows, edited, mapOf("Family" to 77L))
        assertTrue(ops.isNotEmpty())
        for (op in ops) {
            val id = when (op) { is RowOp.Update -> op.id; is RowOp.Delete -> op.id; is RowOp.Insert -> op.row.id }
            assertTrue("op touched unknown row: $op", id != 99L)
            if (op is RowOp.Insert) assertTrue(op.row.mimetype != whatsapp)
        }
    }

    @Test
    fun diffUpdatesOnlyChangedRows() {
        val c = base()
        val ops = DeviceMapper.diff(allRows, c.copy(org = c.org.copy(company = "Acme 2")), mapOf("Family" to 77L))
        assertEquals(listOf<RowOp>(RowOp.Update(7, mapOf("data1" to "Acme 2"))), ops)
    }

    @Test
    fun groupMembershipDiff() {
        val c = base()
        val ops = DeviceMapper.diff(allRows, c.copy(groups = setOf("Family", "Work")), mapOf("Family" to 77L, "Work" to 78L))
        assertEquals(1, ops.size)
        val ins = ops[0] as RowOp.Insert
        assertEquals(GroupMembership.CONTENT_ITEM_TYPE, ins.row.mimetype)
        assertEquals("78", ins.row.values["data1"])

        val removed = DeviceMapper.diff(allRows, c.copy(groups = emptySet()), mapOf("Family" to 77L))
        assertEquals(listOf<RowOp>(RowOp.Delete(14)), removed)
    }

    @Test
    fun unknownGroupRowIsNeverTouched() {
        val rows = allRows + row(16, GroupMembership.CONTENT_ITEM_TYPE, "data1" to "999")
        val c = DeviceMapper.toContact(42, phoneAcct, rows, mapOf(77L to "Family"))
        assertEquals(setOf("Family"), c.groups)
        assertEquals(emptyList<RowOp>(), DeviceMapper.diff(rows, c.copy(groups = emptySet()), mapOf("Family" to 77L)).filter { it == RowOp.Delete(16) })
    }

    @Test
    fun secondOrganizationRowIsKeptAndUntouched() {
        val rows = allRows + row(17, Organization.CONTENT_ITEM_TYPE, "data1" to "Second Co")
        val c = DeviceMapper.toContact(42, phoneAcct, rows, mapOf(77L to "Family"))
        assertEquals("Acme", c.org.company)
        assertTrue(Extra("device", Organization.CONTENT_ITEM_TYPE) in c.extras)
        val ops = DeviceMapper.diff(rows, c.copy(org = c.org.copy(company = "")), mapOf("Family" to 77L))
        assertEquals(listOf<RowOp>(RowOp.Update(7, mapOf("data1" to null))), ops)
    }

    @Test
    fun reorderingProducesNoChurn() {
        val c = base()
        val ops = DeviceMapper.diff(allRows, c.copy(phones = c.phones.reversed()), mapOf("Family" to 77L))
        assertEquals(emptyList<RowOp>(), ops)
    }

    @Test
    fun changedValueIsAnUpdateNotDeleteInsert() {
        val c = base()
        val phones = c.phones.toMutableList().also { it[1] = it[1].copy(value = "+965 99999999") }
        val ops = DeviceMapper.diff(allRows, c.copy(phones = phones), mapOf("Family" to 77L))
        assertEquals(listOf<RowOp>(RowOp.Update(3, mapOf("data1" to "+965 99999999"))), ops)
    }

    @Test
    fun labelsAndPrefMapToTypeColumns() {
        val c = base()
        val phones = listOf(
            c.phones[0].copy(pref = false),
            c.phones[1].copy(label = "Work"),
            c.phones[2].copy(label = "Satellite"),
        )
        val ops = DeviceMapper.diff(allRows, c.copy(phones = phones), mapOf("Family" to 77L)).toSet()
        assertEquals(
            setOf<RowOp>(
                RowOp.Update(2, mapOf("is_primary" to "0")),
                RowOp.Update(3, mapOf("data2" to "3", "data3" to null)),
                RowOp.Update(4, mapOf("data2" to "0", "data3" to "Satellite")),
            ),
            ops,
        )
    }

    @Test
    fun removedAndAddedValues() {
        val c = base()
        val ops = DeviceMapper.diff(
            allRows,
            c.copy(emails = emptyList(), websites = c.websites + Typed("Work", "https://work.example"), phones = c.phones + Typed("Mobile", "  ")),
            mapOf("Family" to 77L),
        )
        assertEquals(2, ops.size)
        assertTrue(RowOp.Delete(5) in ops)
        val ins = ops.filterIsInstance<RowOp.Insert>().single().row
        assertEquals(Website.CONTENT_ITEM_TYPE, ins.mimetype)
        assertEquals(mapOf("data1" to "https://work.example", "data2" to "5"), ins.values)
    }

    @Test
    fun nullPhotoNeverDeletesAndChangedPhotoUpdatesRow() {
        val c = base()
        assertEquals(emptyList<RowOp>(), DeviceMapper.diff(allRows, c.copy(photo = null, thumbnail = null), mapOf("Family" to 77L)))
        // the loaded photo equal to the current one: nothing
        assertEquals(emptyList<RowOp>(), DeviceMapper.diff(allRows, c.copy(photo = byteArrayOf(9)), mapOf("Family" to 77L), byteArrayOf(9)))
        val ops = DeviceMapper.diff(allRows, c.copy(photo = byteArrayOf(7, 7)), mapOf("Family" to 77L), byteArrayOf(9))
        assertEquals(1, ops.size)
        val up = ops[0] as RowOp.Update
        assertEquals(15L, up.id)
        assertEquals(emptyMap<String, String?>(), up.values)
        assertArrayEquals(byteArrayOf(7, 7), up.blob)
        // no photo row yet: insert one
        val noPhoto = allRows.filter { it.mimetype != Photo.CONTENT_ITEM_TYPE }
        val ins = DeviceMapper.diff(noPhoto, c.copy(photo = byteArrayOf(5)), mapOf("Family" to 77L)).single() as RowOp.Insert
        assertEquals(Photo.CONTENT_ITEM_TYPE, ins.row.mimetype)
        assertArrayEquals(byteArrayOf(5), ins.row.blob)
    }

    @Test
    fun formattedOnlyAddressIsShownAndKeptOnEdit() {
        val rows = listOf(row(20, StructuredPostal.CONTENT_ITEM_TYPE, "data1" to "Block 2, Street 1\nKuwait", "data2" to "2"))
        val c = DeviceMapper.toContact(1, phoneAcct, rows, emptyMap())
        assertEquals("Block 2, Street 1\nKuwait", c.addresses.single().street)
        assertEquals(emptyList<RowOp>(), DeviceMapper.diff(rows, c, emptyMap()))
        val ops = DeviceMapper.diff(rows, c.copy(addresses = listOf(c.addresses[0].copy(city = "Hawalli"))), emptyMap())
        // structured fields are written together so the provider rebuilds the formatted address from all of them
        assertEquals(listOf<RowOp>(RowOp.Update(20, mapOf("data4" to "Block 2, Street 1\nKuwait", "data7" to "Hawalli"))), ops)
    }

    @Test
    fun nameEditsLetProviderRebuildDisplay() {
        val c = base()
        val ops = DeviceMapper.diff(allRows, c.copy(name = c.name.copy(first = "أحمد")), mapOf("Family" to 77L))
        assertEquals(listOf<RowOp>(RowOp.Update(1, mapOf("data2" to "أحمد"))), ops)
        // display-only change keeps the structured parts alongside so the provider doesn't re-split them
        val d = DeviceMapper.diff(allRows, c.copy(name = c.name.copy(display = "Boss")), mapOf("Family" to 77L)).single() as RowOp.Update
        assertEquals("Boss", d.values["data1"]); assertEquals("Name", d.values["data2"]); assertEquals("One", d.values["data3"])
    }

    @Test
    fun createDiffInsertsEverything() {
        val c = base()
        val ops = DeviceMapper.diff(emptyList(), c.copy(photo = c.thumbnail), mapOf("Family" to 77L))
        assertTrue(ops.all { it is RowOp.Insert })
        val mimes = ops.map { (it as RowOp.Insert).row.mimetype }.toSet()
        assertEquals(13, mimes.size)
        // and the inserted rows read back as the same contact
        val back = DeviceMapper.toContact(
            42, phoneAcct,
            ops.mapIndexed { i, op -> (op as RowOp.Insert).row.copy(id = 100L + i) }, mapOf(77L to "Family"),
        )
        assertEquals(c.copy(photo = null), back.copy(photo = null))
    }
}
