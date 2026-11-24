package com.ilukezippo.vcfdesk.ui.editor

import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Typed
import com.ilukezippo.vcfdesk.data.FakeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactEditorViewModelTest {
    private val source = FakeSource()
    private val noScale = object : PhotoScaler { override fun scale(bytes: ByteArray): ByteArray = bytes }

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun seeded() = source.seed(
        Contact(
            name = Name(first = "Name", last = "1", display = "Name 1"),
            phones = listOf(Typed("Mobile", "55555555")),
        ),
    )

    private fun vm(id: String, scaler: PhotoScaler = noScale) = ContactEditorViewModel({ source }, id, scaler, Dispatchers.Unconfined)

    @Test fun undoRedoFieldEdits() = runTest {
        val c = seeded()
        val vm = vm(c.id)
        assertEquals(c, vm.contact.value)
        vm.edit { it.copy(notes = "a") }
        vm.edit { it.copy(phones = it.phones + Typed("Home", "99999999")) }
        assertTrue(vm.isDirty.value)
        vm.undo()
        assertEquals("a", vm.contact.value!!.notes)
        assertEquals(1, vm.contact.value!!.phones.size)
        vm.undo()
        assertEquals(c, vm.contact.value)
        assertFalse(vm.isDirty.value)
        assertFalse(vm.canUndo.value)
        vm.redo()
        vm.redo()
        assertEquals(2, vm.contact.value!!.phones.size)
        assertFalse(vm.canRedo.value)
        // a new edit drops the redo stack
        vm.undo()
        vm.edit { it.copy(notes = "b") }
        assertFalse(vm.canRedo.value)
    }

    @Test fun editsWithTheSameKeyShareOneUndoStep() = runTest {
        val vm = vm(seeded().id)
        vm.edit("notes") { it.copy(notes = "a") }
        vm.edit("notes") { it.copy(notes = "ab") }
        vm.edit("notes") { it.copy(notes = "abc") }
        vm.undo()
        assertEquals("", vm.contact.value!!.notes)
        assertFalse(vm.canUndo.value)
    }

    @Test fun nameDisplayFollowsPartsOnlyWhileItWasComposed() = runTest {
        val vm = vm(seeded().id)
        vm.edit { it.copy(name = it.name.copy(first = "Name 9")) }
        assertEquals("Name 9 1", vm.contact.value!!.name.display)
        vm.edit { it.copy(name = it.name.copy(display = "Custom")) }
        vm.edit { it.copy(name = it.name.copy(last = "2")) }
        assertEquals("Custom", vm.contact.value!!.name.display)
    }

    @Test fun saveOnlyWhenChanged() = runTest {
        val c = seeded()
        val vm = vm(c.id)
        assertFalse(vm.isDirty.value)
        val r = vm.save()
        assertTrue(r.written.isEmpty())
        assertFalse(source.log.any { it.startsWith("update") })

        vm.edit { it.copy(notes = "hello") }
        assertTrue(vm.isDirty.value)
        val r2 = vm.save()
        assertEquals(listOf(c.id), r2.written)
        assertEquals("hello", source.get(c.id)!!.notes)
        // reloaded with the new etag, clean, undo history gone
        assertEquals(source.get(c.id)!!.etag, vm.contact.value!!.etag)
        assertFalse(vm.isDirty.value)
        assertFalse(vm.canUndo.value)
        // an unchanged second save writes nothing
        vm.save()
        assertEquals(1, source.log.count { it.startsWith("update") })
        // editing again after a save works with the fresh etag
        vm.edit { it.copy(notes = "again") }
        assertEquals(listOf(c.id), vm.save().written)
    }

    @Test fun staleSaveKeepsEditsAndOffersReload() = runTest {
        val c = seeded()
        val vm = vm(c.id)
        vm.edit { it.copy(notes = "mine") }
        source.update(listOf(source.load(c.id).copy(notes = "theirs")))
        val r = vm.save()
        assertTrue(r.failed.containsKey(c.id))
        assertEquals("stale", vm.saveError.value)
        assertEquals("mine", vm.contact.value!!.notes)
        assertTrue(vm.isDirty.value)
        vm.reload()
        assertEquals("theirs", vm.contact.value!!.notes)
        assertNull(vm.saveError.value)
        assertFalse(vm.isDirty.value)
    }

    @Test fun photoThatCannotBeLoadedBlocksEditing() = runTest {
        val c = source.seed(Contact(name = Name(first = "Name"), photo = byteArrayOf(1, 2, 3)))
        source.photoFails += c.id
        val vm = vm(c.id)
        assertNull(vm.contact.value)
        assertEquals(EditorStatus.PhotoUnavailable, vm.status.value)
        assertTrue(vm.save().written.isEmpty())
        assertFalse(source.log.any { it.startsWith("update") })
        source.photoFails.clear()
        vm.reload()
        assertEquals(EditorStatus.Ready, vm.status.value)
        assertArrayEquals(byteArrayOf(1, 2, 3), vm.contact.value!!.photo)
    }

    @Test fun readOnlyAccountCannotBeEdited() = runTest {
        val c = source.seed(Contact(name = Name(first = "Name"), account = AccountTag("sim", "", "SIM", readOnly = true)))
        val vm = vm(c.id)
        assertTrue(vm.readOnly.value)
        vm.edit { it.copy(notes = "x") }
        assertFalse(vm.isDirty.value)
        assertTrue(vm.save().written.isEmpty())
    }

    @Test fun emptyRowsAreNotWritten() = runTest {
        val c = seeded()
        val vm = vm(c.id)
        vm.edit { it.copy(phones = it.phones + Typed("Home", "  "), emails = listOf(Typed("Work", ""))) }
        vm.save()
        val saved = source.get(c.id)!!
        assertEquals(listOf(Typed("Mobile", "55555555")), saved.phones)
        assertTrue(saved.emails.isEmpty())
    }

    @Test fun missingContactIsReported() = runTest {
        val vm = vm("nope")
        assertEquals(EditorStatus.Gone, vm.status.value)
    }

    @Test fun setPhotoUsesTheScalerAndIsUndoable() = runTest {
        val c = seeded()
        val small = byteArrayOf(9, 9)
        val vm = vm(c.id, object : PhotoScaler { override fun scale(bytes: ByteArray) = small })
        vm.setPhoto(byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(small, vm.contact.value!!.photo)
        assertTrue(vm.isDirty.value)
        vm.undo()
        assertNull(vm.contact.value!!.photo)
        val bad = vm(c.id, object : PhotoScaler { override fun scale(bytes: ByteArray): ByteArray? = null })
        bad.setPhoto(byteArrayOf(1))
        assertNull(bad.contact.value!!.photo)
        assertTrue(bad.photoError.value)
    }

    @Test fun photoScaledTo720() {
        assertEquals(720 to 540, PhotoScaling.targetSize(4000, 3000))
        assertEquals(405 to 720, PhotoScaling.targetSize(1080, 1920))
        assertEquals(720 to 720, PhotoScaling.targetSize(3000, 3000))
        assertEquals(720 to 1, PhotoScaling.targetSize(5000, 1))
        assertEquals(640 to 480, PhotoScaling.targetSize(640, 480))
        assertEquals(720 to 720, PhotoScaling.targetSize(720, 720))

        val resized = ArrayList<Pair<Int, Int>>()
        val codec = object : ImageCodec {
            override fun size(bytes: ByteArray) = if (bytes.size == 1) 4000 to 3000 else if (bytes.size == 2) 600 to 400 else null
            override fun resize(bytes: ByteArray, width: Int, height: Int): ByteArray { resized += width to height; return byteArrayOf(7) }
        }
        val scaler = CodecPhotoScaler(codec)
        val big = byteArrayOf(1)
        assertArrayEquals(byteArrayOf(7), scaler.scale(big))
        assertEquals(listOf(720 to 540), resized)
        val small = byteArrayOf(1, 2)
        assertSame(small, scaler.scale(small))
        assertEquals(1, resized.size)
        assertNull(scaler.scale(byteArrayOf(1, 2, 3)))
    }

    @Test fun sampleSizeKeepsTheLongestSideAtOrAbove720() {
        assertEquals(8, PhotoScaling.sampleSize(8000, 6000))
        assertEquals(1, PhotoScaling.sampleSize(1000, 1000))
        assertEquals(1, PhotoScaling.sampleSize(1439, 100))
        assertEquals(2, PhotoScaling.sampleSize(1440, 100))
        assertEquals(1, PhotoScaling.sampleSize(500, 400))
    }

    @Test fun rotatedPhotosAreRedoneEvenWhenSmall() {
        val resized = ArrayList<Pair<Int, Int>>()
        val codec = object : ImageCodec {
            override fun size(bytes: ByteArray) = 400 to 600
            override fun rotation(bytes: ByteArray) = 90
            override fun resize(bytes: ByteArray, width: Int, height: Int): ByteArray { resized += width to height; return byteArrayOf(5) }
        }
        assertArrayEquals(byteArrayOf(5), CodecPhotoScaler(codec).scale(byteArrayOf(1)))
        assertEquals(listOf(400 to 600), resized)
    }

    @Test fun anImageThatRunsOutOfMemoryBecomesAPhotoErrorNotACrash() = runTest {
        val vm = vm(seeded().id, object : PhotoScaler { override fun scale(bytes: ByteArray): ByteArray? = throw OutOfMemoryError() })
        vm.setPhoto(byteArrayOf(1))
        assertTrue(vm.photoError.value)
        assertNull(vm.contact.value!!.photo)
    }

    private fun holder() = EditorHolder { _, id -> ContactEditorViewModel({ source }, id, noScale, Dispatchers.Unconfined) }

    @Test fun discardThenReopenShowsTheSourcesData() = runTest {
        val c = seeded()
        val h = holder()
        val e1 = h.open(c.id.let { com.ilukezippo.vcfdesk.data.SourceId.Device }, c.id)
        e1.edit { it.copy(notes = "unsaved") }
        assertTrue(h.isDirty)
        var left = false
        h.requestSwitch { left = true }
        assertFalse(left)
        assertTrue(h.pendingSwitch.value != null)
        h.confirmDiscard()
        assertTrue(left)
        assertNull(h.current.value)
        val e2 = h.open(com.ilukezippo.vcfdesk.data.SourceId.Device, c.id)
        assertEquals("", e2.contact.value!!.notes)
        assertFalse(e2.isDirty.value)
    }

    @Test fun keepEditingStaysAndCleanEditorsSwitchAtOnce() = runTest {
        val a = seeded(); val b = seeded()
        val dev = com.ilukezippo.vcfdesk.data.SourceId.Device
        val h = holder()
        val e = h.open(dev, a.id)
        var kept = false; var moved = false
        e.edit { it.copy(notes = "x") }
        h.requestSwitch(onKeep = { kept = true }) { moved = true }
        h.keepEditing()
        assertTrue(kept); assertFalse(moved)
        assertNull(h.pendingSwitch.value)
        assertSame(e, h.open(dev, a.id))
        assertEquals("x", e.contact.value!!.notes)
        e.undo()
        h.requestSwitch { moved = true }
        assertTrue(moved)
        assertNull(h.current.value)
        assertEquals(b.id, h.open(dev, b.id).contact.value!!.id)
    }
}
