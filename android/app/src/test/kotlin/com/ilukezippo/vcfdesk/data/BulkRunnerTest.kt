package com.ilukezippo.vcfdesk.data

import com.ilukezippo.vcfdesk.core.bulk.BulkChange
import com.ilukezippo.vcfdesk.core.bulk.TextField
import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Org
import com.ilukezippo.vcfdesk.core.model.Typed
import com.ilukezippo.vcfdesk.data.backup.BackupStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BulkRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val phone = AccountTag("com.example.phone", "Phone", "Phone", readOnly = false)
    private val sim = AccountTag("com.example.sim", "SIM", "SIM", readOnly = true)
    private val photo = ByteArray(5000) { (it % 251).toByte() }

    private fun person(n: Int, company: String = "Old Co", account: AccountTag? = phone, withPhoto: Boolean = false) = Contact(
        account = account,
        name = Name(first = "Name", last = "$n", display = "Name $n"),
        phones = listOf(Typed("Mobile", "5555555$n")),
        org = Org(company = company),
        photo = if (withPhoto) photo else null,
    )

    private fun dir(): File = File(tmp.root, "backups")
    private fun backupFiles(): List<String> = dir().listFiles()?.map { it.name }?.sorted().orEmpty()

    @Test
    fun backupWrittenBeforeAnyWrite() = runTest {
        val src = FakeSource()
        val ids = (1..3).map { src.seed(person(it, withPhoto = it == 2)).id }
        val runner = BulkRunner(BackupStore(dir()))
        var filesAtWrite: List<String>? = null
        src.beforeWrite = { if (filesAtWrite == null) filesAtWrite = backupFiles() }
        val progress = ArrayList<Pair<Int, Int>>()

        val r = runner.run(src, ids, BulkOp.Edit(listOf(BulkChange.SetField(TextField.COMPANY, "New Co")))) { d, t -> progress += d to t }

        // every id loaded before the first write, and the backup (vcf + json) on disk at that moment
        val firstWrite = src.log.indexOfFirst { it.startsWith("update") }
        assertTrue(src.log.subList(0, firstWrite).containsAll(ids.map { "load:$it" }))
        assertTrue(filesAtWrite!!.any { it.endsWith("-edit.vcf") })
        assertTrue(filesAtWrite!!.any { it.endsWith("-edit.json") })
        assertFalse(filesAtWrite!!.any { it.endsWith(".tmp") })
        assertEquals(3, r.changed)
        assertNotNull(r.backup)
        assertEquals("edit", r.backup!!.action)
        assertEquals(3, r.backup!!.count)
        assertEquals(SourceId.Device, r.backup!!.source)
        assertEquals(3 to 3, progress.last())
        assertTrue(src.all().all { it.org.company == "New Co" })

        // the backup holds the full photo, original ids and accounts
        val backed = BackupStore(dir()).load(r.backup!!)
        assertEquals(ids, backed.map { it.id })
        assertEquals(listOf(phone, phone, phone), backed.map { it.account })
        assertArrayEquals(photo, backed[1].photo)
        assertTrue(backed.all { it.org.company == "Old Co" })
        assertEquals(listOf(r.backup!!.file), BackupStore(dir()).list().map { it.file })
    }

    @Test
    fun unchangedContactsAreNotWrittenOrBackedUp() = runTest {
        val src = FakeSource()
        val a = src.seed(person(1, company = "New Co"))
        val b = src.seed(person(2))
        val r = BulkRunner(BackupStore(dir())).run(src, listOf(a.id, b.id), BulkOp.Edit(listOf(BulkChange.SetField(TextField.COMPANY, "New Co")))) { _, _ -> }
        assertEquals(listOf("update:${b.id}"), src.log.filter { it.startsWith("update") })
        assertEquals(1, r.backup!!.count)
        assertEquals(a.etag, src.get(a.id)!!.etag)
    }

    @Test
    fun skippedAreReported() = runTest {
        val src = FakeSource()
        val a = src.seed(person(1))
        val b = src.seed(person(2, account = sim))
        val r = BulkRunner(BackupStore(dir())).run(src, listOf(a.id, b.id), BulkOp.Edit(listOf(BulkChange.SetField(TextField.COMPANY, "New Co")))) { _, _ -> }
        assertEquals(1, r.changed)
        assertEquals(1, r.skippedReadOnly)
        assertEquals(emptyMap<String, String>(), r.failed)
        assertEquals("Old Co", src.get(b.id)!!.org.company)
    }

    @Test
    fun partialFailureReportsAndUndoRestores() = runTest {
        val src = FakeSource()
        val ids = (1..3).map { src.seed(person(it)).id }
        src.failUpdate[ids[1]] = "boom"
        val runner = BulkRunner(BackupStore(dir()))
        val r = runner.run(src, ids, BulkOp.Edit(listOf(BulkChange.SetField(TextField.COMPANY, "New Co")))) { _, _ -> }
        assertEquals(2, r.changed)
        assertEquals(mapOf(ids[1] to "boom"), r.failed)
        assertEquals(listOf("New Co", "Old Co", "New Co"), ids.map { src.get(it)!!.org.company })

        src.failUpdate.clear()
        val u = runner.undo(src, r.backup!!)
        // every contact back to the old company; ids unchanged (the etag was refreshed, not stale)
        assertEquals(listOf("Old Co", "Old Co", "Old Co"), ids.map { src.get(it)!!.org.company })
        assertEquals(3, src.all().size)
        assertEquals(emptyMap<String, String>(), u.failed)
        assertEquals(2, u.changed) // the one that never changed needs no write
        assertTrue(src.log.none { it.startsWith("create") })
    }

    @Test
    fun undoKeepsCurrentExtrasAndEtag() = runTest {
        val src = FakeSource(SourceId.Google("me@example.com"))
        val c = src.seed(person(1).copy(extras = listOf(com.ilukezippo.vcfdesk.core.model.Extra("google", "{\"x\":1}"))))
        val runner = BulkRunner(BackupStore(dir()))
        val r = runner.run(src, listOf(c.id), BulkOp.Edit(listOf(BulkChange.SetField(TextField.COMPANY, "New Co")))) { _, _ -> }
        assertEquals(SourceId.Google("me@example.com"), BackupStore(dir()).list().single().source)
        val u = runner.undo(src, r.backup!!)
        assertEquals(1, u.changed)
        assertEquals("Old Co", src.get(c.id)!!.org.company)
        assertEquals(c.extras, src.get(c.id)!!.extras)
    }

    @Test
    fun undoRefusesAnotherSourcesBackup() = runTest {
        val src = FakeSource()
        val c = src.seed(person(1))
        val runner = BulkRunner(BackupStore(dir()))
        val r = runner.run(src, listOf(c.id), BulkOp.Edit(listOf(BulkChange.SetField(TextField.COMPANY, "New Co")))) { _, _ -> }
        val other = FakeSource(SourceId.Google("me@example.com"))
        val threw = try { runner.undo(other, r.backup!!); false } catch (e: IllegalArgumentException) { true }
        assertTrue(threw)
        assertTrue(other.log.isEmpty())
    }

    @Test
    fun deleteUndoRecreates() = runTest {
        val src = FakeSource()
        val a = src.seed(person(1, withPhoto = true))
        val b = src.seed(person(2, account = null))
        val keep = src.seed(person(3))
        val runner = BulkRunner(BackupStore(dir()))

        val pv = runner.preview(src, listOf(a.id, b.id), BulkOp.Delete)
        assertEquals(listOf("deleted", "deleted"), pv.map { it.changes.single().after })
        assertEquals(3, src.all().size)

        val r = runner.run(src, listOf(a.id, b.id), BulkOp.Delete) { _, _ -> }
        assertEquals(2, r.changed)
        assertEquals("delete", r.backup!!.action)
        assertEquals(listOf(keep.id), src.all().map { it.id })

        val u = runner.undo(src, r.backup!!)
        assertEquals(2, u.changed)
        assertEquals(3, src.all().size)
        val back = src.all().filter { it.id != keep.id }
        val ra = back.single { it.name.display == "Name 1" }
        val rb = back.single { it.name.display == "Name 2" }
        assertEquals(phone, ra.account)
        assertNull(rb.account)
        assertArrayEquals(photo, ra.photo)
        assertEquals(listOf("55555551"), ra.phones.map { it.value })
    }

    @Test
    fun deleteNeverTouchesContactsThatCouldNotBeBackedUp() = runTest {
        val src = FakeSource()
        val a = src.seed(person(1))
        val b = src.seed(person(2))
        src.failLoad[b.id] = "offline"
        val r = BulkRunner(BackupStore(dir())).run(src, listOf(a.id, b.id), BulkOp.Delete) { _, _ -> }
        assertEquals(listOf("delete:${a.id}"), src.log.filter { it.startsWith("delete") })
        assertTrue(r.failed.getValue(b.id).contains("offline"))
        assertNotNull(src.get(b.id))
    }

    @Test
    fun backupFailureAbortsWithNothingWritten() = runTest {
        val src = FakeSource()
        val a = src.seed(person(1))
        val blocker = File(tmp.root, "notadir").apply { writeText("x") } // a file where the dir should be
        val r = BulkRunner(BackupStore(blocker)).run(src, listOf(a.id), BulkOp.Delete) { _, _ -> }
        assertTrue(src.log.none { it.startsWith("delete") || it.startsWith("update") || it.startsWith("create") })
        assertEquals(0, r.changed)
        assertNull(r.backup)
        assertTrue(r.failed.containsKey(a.id))
        assertNotNull(src.get(a.id))
    }

    @Test
    fun moveCopiesThenDeletesOnlyCopied() = runTest {
        val src = FakeSource()
        val target = FakeSource(SourceId.Google("me@example.com"))
        val ids = (1..3).map { src.seed(person(it)).id }
        target.failCreate[ids[1]] = "quota"
        val gAcct = AccountTag("com.google", "me@example.com", "me@example.com", readOnly = false)
        val runner = BulkRunner(BackupStore(dir()))

        val r = runner.run(src, ids, BulkOp.CopyTo(target, gAcct, move = true)) { _, _ -> }

        // copy first, delete afterwards and only what was copied
        val createAt = target.log.indexOfFirst { it.startsWith("create") }
        assertTrue(createAt >= 0)
        assertEquals(listOf("delete:${ids[0]},${ids[2]}"), src.log.filter { it.startsWith("delete") })
        assertEquals(listOf(ids[1]), src.all().map { it.id })
        assertEquals(2, target.all().size)
        assertTrue(target.all().all { it.account == gAcct })
        assertEquals(2, r.changed)
        assertEquals(mapOf(ids[1] to "quota"), r.failed)
        assertEquals("move", r.backup!!.action)

        // undo puts the moved ones back in the source and removes the copies
        runner.undo(src, r.backup!!, target)
        assertEquals(3, src.all().size)
        assertEquals(0, target.all().size)
    }

    @Test
    fun moveDeletesNothingWhenCreateResultsCannotBeMatched() = runTest {
        val src = FakeSource()
        val ids = (1..2).map { src.seed(person(it)).id }
        // a target that claims success but reports fewer new ids than inputs
        val target = object : ContactSource by FakeSource(SourceId.Google("me@example.com")) {
            override suspend fun create(cs: List<Contact>, target: AccountTag?, onProgress: (Int) -> Unit) =
                WriteResult(listOf("x"), emptyList(), emptyMap())
        }
        val r = BulkRunner(BackupStore(dir())).run(src, ids, BulkOp.CopyTo(target, null, move = true)) { _, _ -> }
        assertTrue(src.log.none { it.startsWith("delete") })
        assertEquals(2, src.all().size)
        assertEquals(setOf(ids[0], ids[1]), r.failed.keys)
    }

    @Test
    fun copyWithoutMoveLeavesSourceAlone() = runTest {
        val src = FakeSource()
        val target = FakeSource()
        val ids = (1..2).map { src.seed(person(it)).id }
        val r = BulkRunner(BackupStore(dir())).run(src, ids, BulkOp.CopyTo(target, null, move = false)) { _, _ -> }
        assertEquals(2, r.changed)
        assertNull(r.backup)
        assertEquals(2, src.all().size)
        assertEquals(2, target.all().size)
        assertTrue(src.log.none { it.startsWith("delete") || it.startsWith("update") })
    }

    @Test
    fun removeExactDuplicatesKeepsKeeper() = runTest {
        val src = FakeSource()
        val plain = src.seed(person(1))
        val rich = src.seed(person(1).copy(notes = "more info"))
        val plain2 = src.seed(person(1))
        val other = src.seed(person(2))
        val runner = BulkRunner(BackupStore(dir()))
        val ids = listOf(plain.id, rich.id, plain2.id, other.id)

        val pv = runner.preview(src, ids, BulkOp.RemoveExactDuplicates)
        assertEquals(setOf(plain.id, plain2.id), pv.map { it.contact.id }.toSet())
        assertTrue(pv.all { it.changes.single().after.contains("Name 1") })

        val r = runner.run(src, ids, BulkOp.RemoveExactDuplicates) { _, _ -> }
        assertEquals(setOf(rich.id, other.id), src.all().map { it.id }.toSet())
        assertEquals(2, r.changed)
        assertEquals(2, r.backup!!.count)
        assertEquals("delete", r.backup!!.action)
    }

    @Test
    fun editPreviewShowsChanges() = runTest {
        val src = FakeSource()
        val a = src.seed(person(1))
        val pv = BulkRunner(BackupStore(dir())).preview(src, listOf(a.id), BulkOp.Edit(listOf(BulkChange.SetField(TextField.COMPANY, "New Co"))))
        assertEquals("New Co", pv.single().after.org.company)
        assertTrue(src.log.none { !it.startsWith("load") })
    }

    // ---- fix round 1 ----

    private val edit = BulkOp.Edit(listOf(BulkChange.SetField(TextField.COMPANY, "New Co")))

    @Test
    fun undoRestoresExactlyTheOriginal() = runTest {
        val src = FakeSource()
        val original = src.seed(
            Contact(
                account = phone,
                name = Name(first = "Name", last = "1"), // empty display name
                phones = listOf(Typed("Main", "55555551"), Typed("", "99999999", pref = true)),
                emails = listOf(Typed("", "name1@example.com")),
                events = listOf(Typed("Birthday", "1990-02-01")),
                org = Org(company = "Old Co", units = listOf("Unit A")),
                groups = setOf("Family", "Work"),
                photo = photo,
                extras = listOf(com.ilukezippo.vcfdesk.core.model.Extra("device", "vnd.example/item")),
            ),
        )
        val runner = BulkRunner(BackupStore(dir()))
        val r = runner.run(src, listOf(original.id), edit) { _, _ -> }
        assertEquals("New Co", src.get(original.id)!!.org.company)
        val u = runner.undo(src, r.backup!!)
        assertEquals(1, u.changed)
        assertEquals(emptyMap<String, String>(), u.failed)
        val now = src.get(original.id)!!
        assertEquals(original.copy(etag = now.etag), now)
    }

    @Test
    fun undoDoesNotWriteContactsWhoseUpdateFailed() = runTest {
        val src = FakeSource()
        val ids = (1..3).map { src.seed(person(it)).id }
        src.failUpdate[ids[1]] = "boom"
        val runner = BulkRunner(BackupStore(dir()))
        val r = runner.run(src, ids, edit) { _, _ -> }
        src.failUpdate.clear()
        val etag1 = src.get(ids[1])!!.etag
        src.log.clear()
        runner.undo(src, r.backup!!)
        assertEquals(listOf("update:${ids[0]},${ids[2]}"), src.log.filter { it.startsWith("update") })
        assertEquals(etag1, src.get(ids[1])!!.etag)
    }

    @Test
    fun fieldChangedSinceEditIsAConflictAndKept() = runTest {
        val src = FakeSource()
        val c = src.seed(person(1).copy(notes = "old notes"))
        val runner = BulkRunner(BackupStore(dir()))
        val r = runner.run(src, listOf(c.id), BulkOp.Edit(listOf(
            BulkChange.SetField(TextField.COMPANY, "New Co"), BulkChange.SetField(TextField.NOTES, "new notes"),
        ))) { _, _ -> }
        // someone changes the company again after the bulk edit
        val fresh = src.load(c.id)
        src.update(listOf(fresh.copy(org = fresh.org.copy(company = "Other Co"))))
        val u = runner.undo(src, r.backup!!)
        val now = src.get(c.id)!!
        assertEquals("Other Co", now.org.company)
        assertEquals("old notes", now.notes)
        assertTrue(u.failed.getValue(c.id).contains("company"))
    }

    @Test
    fun contactsWhosePhotoCantBeReadAreNeverTouched() = runTest {
        val src = FakeSource()
        val target = FakeSource()
        val a = src.seed(person(1, withPhoto = true))
        val b = src.seed(person(2, withPhoto = true))
        src.photoFails += b.id
        val runner = BulkRunner(BackupStore(dir()))

        val moved = runner.run(src, listOf(a.id, b.id), BulkOp.CopyTo(target, null, move = true)) { _, _ -> }
        assertTrue(moved.failed.getValue(b.id).contains("photo"))
        assertEquals(listOf(b.id), src.all().map { it.id })
        assertEquals(1, target.all().size)
        assertArrayEquals(photo, target.all().single().photo)
        assertEquals(1, BackupStore(dir()).load(moved.backup!!).size)

        val del = runner.run(src, listOf(b.id), BulkOp.Delete) { _, _ -> }
        assertTrue(del.failed.containsKey(b.id))
        assertNotNull(src.get(b.id))

        val ed = runner.run(src, listOf(b.id), edit) { _, _ -> }
        assertTrue(ed.failed.containsKey(b.id))
        assertEquals("Old Co", src.get(b.id)!!.org.company)
    }

    @Test
    fun duplicateWithDataTheKeeperLacksIsKept() = runTest {
        val src = FakeSource()
        // richest of the group (a photo counts 3, so it needs more fields than withPhoto)
        val keeper = src.seed(person(1).let {
            it.copy(
                notes = "note", org = Org(company = "Old Co", title = "Boss", department = "Ops"),
                name = it.name.copy(nickname = "N1"), websites = listOf(Typed("", "example.com")),
            )
        })
        val withAddress = src.seed(person(1).copy(addresses = listOf(com.ilukezippo.vcfdesk.core.model.Address(city = "Kuwait"))))
        val contained = src.seed(person(1))
        val withPhoto = src.seed(person(1, withPhoto = true))
        val runner = BulkRunner(BackupStore(dir()))
        val ids = listOf(keeper.id, withAddress.id, contained.id, withPhoto.id)

        val pv = runner.preview(src, ids, BulkOp.RemoveExactDuplicates).associateBy { it.contact.id }
        assertTrue(pv.getValue(contained.id).changes.single().after.startsWith("removed"))
        val kept = pv.getValue(withAddress.id).changes.single().after
        assertTrue(kept, kept.startsWith("kept") && kept.contains("address"))
        assertTrue(pv.getValue(withPhoto.id).changes.single().after.contains("photo"))

        val r = runner.run(src, ids, BulkOp.RemoveExactDuplicates) { _, _ -> }
        assertEquals(1, r.changed)
        assertEquals(setOf(keeper.id, withAddress.id, withPhoto.id), src.all().map { it.id }.toSet())
    }

    @Test
    fun moveUndoRemovesTheCopiesAndRestoresTheOriginals() = runTest {
        val src = FakeSource()
        val target = FakeSource(SourceId.Google("me@example.com"))
        val ids = (1..3).map { src.seed(person(it)).id }
        target.failCreate[ids[2]] = "quota"
        val runner = BulkRunner(BackupStore(dir()))
        val r = runner.run(src, ids, BulkOp.CopyTo(target, null, move = true)) { _, _ -> }
        assertEquals(2, target.all().size)
        assertEquals(listOf(ids[2]), src.all().map { it.id })

        // without the target, a move can't be undone properly: refuse rather than leave duplicates
        val refused = try { runner.undo(src, r.backup!!); false } catch (e: IllegalArgumentException) { true }
        assertTrue(refused)

        val u = runner.undo(src, r.backup!!, target)
        assertEquals(emptyMap<String, String>(), u.failed)
        assertEquals(0, target.all().size)
        assertEquals(setOf("Name 1", "Name 2", "Name 3"), src.all().map { it.name.display }.toSet())
        // re-created before the copies were removed
        val created = src.log.indexOfFirst { it.startsWith("create") }
        val removed = target.log.indexOfFirst { it.startsWith("delete") }
        assertTrue(created >= 0 && removed >= 0)
    }
}
