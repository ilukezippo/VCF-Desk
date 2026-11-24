package com.ilukezippo.vcfdesk.ui.bulk

import com.ilukezippo.vcfdesk.core.bulk.BulkChange
import com.ilukezippo.vcfdesk.core.bulk.TextField
import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Org
import com.ilukezippo.vcfdesk.core.model.Typed
import com.ilukezippo.vcfdesk.data.BulkOp
import com.ilukezippo.vcfdesk.data.BulkRunner
import com.ilukezippo.vcfdesk.data.ContactSource
import com.ilukezippo.vcfdesk.data.FakeSource
import com.ilukezippo.vcfdesk.data.SourceId
import com.ilukezippo.vcfdesk.data.WriteResult
import com.ilukezippo.vcfdesk.data.backup.BackupStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BulkViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val phone = AccountTag("com.example.phone", "Phone", "Phone", readOnly = false)
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun person(n: Int) = Contact(
        account = phone,
        name = Name(first = "Name", last = "$n", display = "Name $n"),
        phones = listOf(Typed("Mobile", "5555555$n")),
        org = Org(company = "Old Co"),
    )

    private fun runner() = BulkRunner(BackupStore(File(tmp.root, "backups")))

    private fun vm(src: ContactSource, ids: List<String>, runner: BulkRunner = runner(), other: ContactSource? = null) =
        BulkViewModel(
            runner = runner,
            sourceId = src.id,
            sourceProvider = { id -> if (id == src.id) src else other ?: error("no source $id") },
            ids = ids,
            countryCode = flowOf("+965"),
            background = dispatcher,
        )

    private val setCompany = BulkOp.Edit(listOf(BulkChange.SetField(TextField.COMPANY, "New Co")))

    @Test
    fun previewThenConfirmRuns() {
        val src = FakeSource()
        val ids = (1..3).map { src.seed(person(it)).id }
        val vm = vm(src, ids)
        assertEquals(Stage.Compose, vm.stage.value)

        vm.preview(setCompany)
        val p = vm.stage.value as Stage.Preview
        assertEquals(3, p.diffs.size)
        assertTrue(src.all().all { it.org.company == "Old Co" }) // preview writes nothing

        vm.confirm()
        val d = vm.stage.value as Stage.Done
        assertEquals(3, d.report.changed)
        assertNotNull(d.report.backup)
        assertTrue(src.all().all { it.org.company == "New Co" })
    }

    @Test
    fun emptyPreviewCannotBeConfirmed() {
        val src = FakeSource()
        val ids = (1..2).map { src.seed(person(it)).id }
        val vm = vm(src, ids)
        vm.preview(BulkOp.Edit(listOf(BulkChange.SetField(TextField.COMPANY, "Old Co"))))
        assertTrue((vm.stage.value as Stage.Preview).diffs.isEmpty())
        vm.confirm()
        assertTrue(vm.stage.value is Stage.Preview)
        assertEquals(0, src.log.count { it.startsWith("update") })
    }

    /** Writes in batches of two; after the first batch it waits on [gate], where a cancel lands. */
    private class BatchedSource(private val inner: FakeSource, val gate: CompletableDeferred<Unit>) : ContactSource by inner {
        override suspend fun update(cs: List<Contact>, onProgress: (Int) -> Unit): WriteResult {
            val written = ArrayList<String>()
            var done = 0
            for (batch in cs.chunked(2)) {
                val r = inner.update(batch)
                written += r.written
                done += batch.size
                onProgress(done)
                gate.await()
            }
            return WriteResult(written, emptyList(), emptyMap())
        }
    }

    @Test
    fun cancelStopsBetweenBatches() {
        val fake = FakeSource()
        val ids = (1..4).map { fake.seed(person(it)).id }
        val src = BatchedSource(fake, CompletableDeferred())
        val vm = vm(src, ids)
        vm.preview(setCompany)
        vm.confirm()
        assertEquals(Stage.Running(2, 4), vm.stage.value)

        vm.cancel()

        val s = vm.stage.value as Stage.Cancelled
        assertEquals(2, s.done); assertEquals(4, s.total)
        assertNotNull(s.backup)
        assertEquals(listOf("New Co", "New Co", "Old Co", "Old Co"), fake.all().map { it.org.company })
        assertEquals(1, File(tmp.root, "backups").listFiles { f -> f.name.endsWith(".json") }!!.size) // backup kept
    }

    @Test
    fun undoAfterCancelRestores() {
        val fake = FakeSource()
        val ids = (1..4).map { fake.seed(person(it)).id }
        val gate = CompletableDeferred<Unit>()
        val vm = vm(BatchedSource(fake, gate), ids)
        vm.preview(setCompany)
        vm.confirm()
        vm.cancel()
        gate.complete(Unit) // the undo's own writes must not wait
        assertEquals(listOf("New Co", "New Co", "Old Co", "Old Co"), fake.all().map { it.org.company })

        vm.undoLast()

        assertEquals(2, (vm.stage.value as Stage.Cancelled).undone!!.changed)
        assertTrue(fake.all().all { it.org.company == "Old Co" })
    }

    @Test
    fun undoLastUsesReportBackup() {
        val src = FakeSource()
        val ids = (1..3).map { src.seed(person(it)).id }
        val vm = vm(src, ids)
        vm.preview(setCompany)
        vm.confirm()
        val d = vm.stage.value as Stage.Done

        vm.undoLast()

        val after = vm.stage.value as Stage.Done
        assertEquals(d.report, after.report)
        assertNotNull(after.undone)
        assertEquals(3, after.undone!!.changed)
        assertTrue(src.all().all { it.org.company == "Old Co" })
        // a second undo does nothing
        val writes = src.log.size
        vm.undoLast()
        assertEquals(writes, src.log.size)
    }

    @Test
    fun undoOfMoveBringsBackFromTarget() {
        val src = FakeSource(SourceId.Device)
        val target = FakeSource(SourceId.Google("name1@example.com"))
        val ids = (1..2).map { src.seed(person(it)).id }
        val vm = vm(src, ids, other = target)
        vm.preview(BulkOp.CopyTo(target, null, move = true))
        vm.confirm()
        assertEquals(0, src.all().size)
        assertEquals(2, target.all().size)

        vm.undoLast()

        assertEquals(2, src.all().size)
        assertEquals(0, target.all().size)
    }

    @Test
    fun deleteNeedsSecondConfirm() {
        val src = FakeSource()
        val ids = (1..3).map { src.seed(person(it)).id }
        val vm = vm(src, ids)
        vm.preview(BulkOp.Delete)
        assertEquals(false, (vm.stage.value as Stage.Preview).finalConfirm)

        vm.confirm()
        val p = vm.stage.value as Stage.Preview
        assertTrue(p.finalConfirm)
        assertEquals(3, src.all().size) // nothing deleted yet

        vm.confirm()
        assertEquals(3, (vm.stage.value as Stage.Done).report.changed)
        assertEquals(0, src.all().size)
    }

    @Test
    fun failureBecomesErrorStage() {
        val src = FakeSource()
        val ids = listOf(src.seed(person(1)).id)
        val vm = vm(src, ids)
        vm.preview(setCompany)
        src.beforeWrite = { throw java.io.IOException("disk gone") }
        vm.confirm()
        assertEquals("disk gone", (vm.stage.value as Stage.Error).message)
        vm.close()
        assertEquals(Stage.Compose, vm.stage.value)
        assertNull((vm.stage.value as? Stage.Done))
    }
}
