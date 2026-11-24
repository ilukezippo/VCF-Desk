package com.ilukezippo.vcfdesk.ui.backups

import com.ilukezippo.vcfdesk.core.bulk.BulkChange
import com.ilukezippo.vcfdesk.core.bulk.TextField
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Org
import com.ilukezippo.vcfdesk.data.BulkOp
import com.ilukezippo.vcfdesk.data.BulkRunner
import com.ilukezippo.vcfdesk.data.FakeSource
import com.ilukezippo.vcfdesk.data.SourceId
import com.ilukezippo.vcfdesk.data.backup.BackupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BackupsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val store by lazy { BackupStore(File(tmp.root, "b")) }
    private fun person(n: Int) = Contact(name = Name(first = "Name", last = "$n", display = "Name $n"), org = Org(company = "Old Co"))

    @Test fun listsAndRestoresAnEdit() = runTest(dispatcher) {
        val src = FakeSource()
        val ids = (1..2).map { src.seed(person(it)).id }
        val runner = BulkRunner(store)
        runner.run(src, ids, BulkOp.Edit(listOf(BulkChange.SetField(TextField.COMPANY, "New Co")))) { _, _ -> }
        val vm = BackupsViewModel(store, runner, { src }, flowOf(emptyList()), dispatcher)
        val b = vm.items.value.single()
        assertEquals("edit", b.action)
        assertEquals(2, b.count)
        vm.restore(b)
        val done = vm.restore.value as RestoreState.Done
        assertEquals(2, done.report.changed)
        assertTrue(src.all().all { it.org.company == "Old Co" })
        // the undo kept its own safety backup
        assertEquals(2, vm.items.value.size)
    }

    @Test fun googleBackupNeedsItsAccount() = runTest(dispatcher) {
        val g = FakeSource(SourceId.Google("me@example.com"))
        val ids = listOf(g.seed(person(1)).id)
        val runner = BulkRunner(store)
        runner.run(g, ids, BulkOp.Edit(listOf(BulkChange.SetField(TextField.COMPANY, "New Co")))) { _, _ -> }
        val vm = BackupsViewModel(store, runner, { g }, flowOf(emptyList()), dispatcher)
        vm.restore(vm.items.value.single())
        assertEquals(RestoreError.AccountMissing("me@example.com"), (vm.restore.value as RestoreState.Failed).error)
        assertEquals("New Co", g.all().single().org.company)
    }

    @Test fun moveBackupNeedsItsTargetAccount() = runTest(dispatcher) {
        val src = FakeSource()
        val target = FakeSource(SourceId.Google("me@example.com"))
        val ids = listOf(src.seed(person(1)).id)
        val runner = BulkRunner(store)
        runner.run(src, ids, BulkOp.CopyTo(target, null, move = true)) { _, _ -> }
        val vm = BackupsViewModel(store, runner, { if (it == SourceId.Device) src else target }, flowOf(emptyList()), dispatcher)
        vm.restore(vm.items.value.single { it.action == "move" })
        assertEquals(RestoreError.AccountMissing("me@example.com"), (vm.restore.value as RestoreState.Failed).error)
        assertEquals(0, src.all().size)

        val vm2 = BackupsViewModel(store, runner, { if (it == SourceId.Device) src else target }, flowOf(listOf("me@example.com")), dispatcher)
        vm2.restore(vm2.items.value.single { it.action == "move" })
        assertTrue(vm2.restore.value is RestoreState.Done)
        assertEquals(1, src.all().size)
        assertEquals(0, target.all().size)
    }
}
