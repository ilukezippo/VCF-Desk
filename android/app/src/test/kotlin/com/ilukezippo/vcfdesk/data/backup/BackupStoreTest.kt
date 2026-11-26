package com.ilukezippo.vcfdesk.data.backup

import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.data.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BackupStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = Clock.fixed(Instant.parse("2026-11-23T10:15:30Z"), ZoneOffset.UTC)
    private val acct = AccountTag("com.google", "me@example.com", "me@example.com", false)

    @Test
    fun saveNamesFilesAndRoundTrips() {
        val store = BackupStore(tmp.root, clock)
        val cs = listOf(
            Contact(id = "people/c1", account = acct, etag = "e1", name = Name(first = "اسم", display = "اسم")),
            Contact(id = "7", account = null, name = Name(first = "Name", last = "2", display = "Name 2")),
        )
        val b = store.save(cs, "edit", SourceId.Google("me@example.com"))
        assertEquals("20261123-101530-edit.vcf", b.file.name)
        assertTrue(java.io.File(tmp.root, "20261123-101530-edit.json").isFile)
        assertEquals(Instant.parse("2026-11-23T10:15:30Z"), b.time)
        assertEquals(2, b.count)

        // a second backup in the same second gets its own file
        val b2 = store.save(cs.take(1), "edit", SourceId.Device)
        assertTrue(b2.file != b.file)

        val listed = store.list()
        assertEquals(2, listed.size)
        val again = listed.single { it.file == b.file }
        assertEquals(SourceId.Google("me@example.com"), again.source)
        val loaded = store.load(again)
        assertEquals(listOf("people/c1", "7"), loaded.map { it.id })
        assertEquals(listOf(acct, null), loaded.map { it.account })
        assertEquals("اسم", loaded[0].name.display)
        assertEquals(null, loaded[0].etag)
    }

    @Test
    fun listIgnoresIncompleteBackups() {
        val store = BackupStore(tmp.root, clock)
        java.io.File(tmp.root, "20260101-000000-edit.vcf").writeText("BEGIN:VCARD\r\nEND:VCARD\r\n")
        java.io.File(tmp.root, "x.json.tmp").writeText("{}")
        assertEquals(emptyList<Backup>(), store.list())
    }

    @Test
    fun snapshotIsLosslessAndPhotoComesFromTheVcf() {
        val store = BackupStore(tmp.root, clock)
        val photo = ByteArray(3000) { (it % 200).toByte() }
        val c = Contact(
            id = "9", account = acct, etag = "e9",
            name = Name(first = "Name", last = "9"), // empty display
            phones = listOf(com.ilukezippo.vcfdesk.core.model.Typed("Main", "55555559"), com.ilukezippo.vcfdesk.core.model.Typed("", "66666666", true, mapOf("X-A" to "b"))),
            emails = listOf(com.ilukezippo.vcfdesk.core.model.Typed("", "n9@example.com")),
            events = listOf(com.ilukezippo.vcfdesk.core.model.Typed("Birthday", "1990-02-01")),
            groups = setOf("B", "A"),
            photo = photo, thumbnail = byteArrayOf(1),
            extras = listOf(com.ilukezippo.vcfdesk.core.model.Extra("google", "{\"k\":1}")),
        )
        val after = c.copy(notes = "edited")
        val b = store.save(listOf(c), "edit", SourceId.Device, listOf(after))
        val e = store.entries(b).single()
        assertEquals(c.copy(thumbnail = null), e.before)
        assertEquals(after.copy(photo = null, thumbnail = null), e.after)
        assertEquals(e.beforePhoto, e.afterPhoto)

        val reloaded = after.copy(name = after.name.copy(display = "Name 9"), etag = "e10")
        store.setAfter(b, mapOf("9" to reloaded))
        assertEquals("Name 9", store.entries(b).single().after!!.name.display)

        store.recordMove(b, SourceId.Google("me@example.com"), mapOf("9" to "people/c5"))
        val m = store.move(b)!!
        assertEquals(SourceId.Google("me@example.com"), m.target)
        assertEquals(mapOf("9" to "people/c5"), m.copies)
        assertEquals(1, store.list().size)
    }

    @Test
    fun streamedBackupIsTheSameAsTheWholeFileAndReadsBackCardByCard() {
        val store = BackupStore(tmp.root, clock)
        val cs = (1..25).map { i ->
            Contact(
                id = "$i", name = Name(first = "Name", last = "$i", display = "Name $i"),
                phones = listOf(com.ilukezippo.vcfdesk.core.model.Typed("Mobile", "5555555$i")),
                notes = "line one\nEND:VCARD\nline three", // never ends a card early
                photo = if (i % 3 == 0) ByteArray(4000 + i) { (it * i % 251).toByte() } else null,
            )
        }
        val b = store.save(cs, "delete", SourceId.Device)
        // byte-for-byte what writing everything at once gives
        assertEquals(
            com.ilukezippo.vcfdesk.core.vcard.VCardWriter.write(cs, com.ilukezippo.vcfdesk.core.vcard.VCardVersion.V30),
            b.file.readText(Charsets.UTF_8),
        )
        // the card-by-card reader sees exactly what the whole-file reader sees
        val whole = com.ilukezippo.vcfdesk.core.vcard.VCardReader.read(b.file.readText(Charsets.UTF_8))
        val streamed = ArrayList<Contact>()
        assertEquals(25, store.forEachCard(b.file) { i, c -> assertEquals(streamed.size, i); streamed += c })
        assertEquals(whole, streamed)
        assertEquals(cs.map { it.photo?.toList() }, store.entries(b).map { it.before.photo?.toList() })
        assertEquals(cs.map { it.notes }, store.load(b).map { it.notes })
    }

    @Test
    fun damagedPhotoInTheVcfIsReported() {
        val store = BackupStore(tmp.root, clock)
        val c = Contact(id = "1", name = Name(display = "Name 1"), photo = ByteArray(500) { it.toByte() })
        val b = store.save(listOf(c, c.copy(id = "2", photo = null)), "delete", SourceId.Device)
        // drop the second card: count no longer matches
        val text = b.file.readText(Charsets.UTF_8)
        b.file.writeText(text.substring(0, text.indexOf("END:VCARD") + "END:VCARD\r\n".length))
        try {
            store.entries(b)
            org.junit.Assert.fail("expected damage to be reported")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("damaged"))
        }
    }
}
