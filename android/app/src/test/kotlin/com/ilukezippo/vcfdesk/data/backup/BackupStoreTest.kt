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
}
