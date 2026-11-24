package com.ilukezippo.vcfdesk.ui.io

import android.content.Intent
import android.net.Uri
import com.ilukezippo.vcfdesk.core.export.ExportFormat
import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Typed
import com.ilukezippo.vcfdesk.core.table.ColumnGuess
import com.ilukezippo.vcfdesk.core.table.Field
import com.ilukezippo.vcfdesk.core.table.Table
import com.ilukezippo.vcfdesk.core.table.Xlsx
import com.ilukezippo.vcfdesk.core.vcard.VCardReader
import com.ilukezippo.vcfdesk.core.vcard.VCardVersion
import com.ilukezippo.vcfdesk.core.vcard.VCardWriter
import com.ilukezippo.vcfdesk.data.FakeSource
import com.ilukezippo.vcfdesk.ui.AppNav
import com.ilukezippo.vcfdesk.ui.Screen
import com.ilukezippo.vcfdesk.ui.importUriFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class IoViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()
    private val uri = Uri.parse("content://test/file")

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun importVm(bytes: ByteArray, cc: String = "+965") =
        ImportViewModel({ bytes }, flowOf(cc), dispatcher)

    private fun people(n: Int) = (1..n).map {
        Contact(name = Name(first = "Name", last = "$it", display = "Name $it"), phones = listOf(Typed("Mobile", "5555555$it")))
    }

    private fun vcf(n: Int) = VCardWriter.write(people(n), VCardVersion.V30).toByteArray()

    private fun ready(vm: ImportViewModel) = vm.state.value as ImportState.Ready

    // ---- import

    @Test fun vcfImportCount() {
        val vm = importVm(vcf(3))
        vm.open(uri)
        val s = ready(vm)
        assertEquals(ImportKind.VCF, s.kind)
        assertEquals(3, s.count)
        assertTrue(s.canImport)
    }

    @Test fun xlsxGuessesMapping() {
        val t = Table(listOf("Name", "Mobile", "E-mail", "Notes"), listOf(listOf("Name 1", "55555555", "name1@example.com", "x"), listOf("Name 2", "99999999", "", "")))
        val vm = importVm(Xlsx.write(t, sheet = "People"))
        vm.open(uri)
        val s = ready(vm)
        assertEquals(ImportKind.XLSX, s.kind)
        assertEquals(listOf("People"), s.sheets)
        assertEquals(t.headers.map { ColumnGuess.guess(it) }, vm.mapping.value)
        assertTrue(Field.MOBILE in vm.mapping.value)
        assertEquals(2, s.count)
        assertEquals(listOf("Name 1", "55555555", "name1@example.com", "x"), s.samples)
    }

    @Test fun editingTheMappingChangesTheCount() {
        val t = Table(listOf("Name", "Mobile"), listOf(listOf("Name 1", "55555555"), listOf("", "66666666")))
        val vm = importVm(Csv(t))
        vm.open(uri)
        assertEquals(ImportKind.CSV, ready(vm).kind)
        assertEquals(2, ready(vm).count)
        vm.setMapping(0, Field.SKIP)
        vm.setMapping(1, Field.SKIP)
        assertEquals(0, ready(vm).count)
        assertFalse(ready(vm).canImport)
        vm.setMapping(1, Field.MOBILE)
        assertEquals(2, ready(vm).count)
    }

    @Test fun countryCodeIsPrefilledAndValidated() {
        val t = Table(listOf("Name", "Mobile"), listOf(listOf("Name 1", "55555555")))
        val vm = importVm(Csv(t), cc = "+966")
        vm.open(uri)
        assertEquals("+966", ready(vm).countryCode)
        vm.setCountryCode("abc")
        assertFalse(ready(vm).countryCodeValid)
        assertFalse(ready(vm).canImport)
        vm.setCountryCode("")
        assertTrue(ready(vm).canImport)
        assertTrue(CountryCode.valid("+965"))
        assertFalse(CountryCode.valid("965"))
        assertFalse(CountryCode.valid("+12345"))
        assertFalse(CountryCode.valid(""))
    }

    @Test fun unreadableFilesAreErrorsNeverZeroContacts() {
        fun err(bytes: ByteArray): ImportError = importVm(bytes).also { it.open(uri) }.state.value.let { (it as ImportState.Failed).error }
        assertTrue(err(ByteArray(0)) is ImportError.NoContacts)
        assertTrue(err(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 1, 2, 3)) is ImportError.Unreadable)
        assertTrue(err(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0)) is ImportError.OldExcel)
        assertTrue(err("BEGIN:VCARD\r\nFN:Name 1\r\n".toByteArray()) is ImportError.NoContacts)
        val wb = """<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheets><sheet name="S1" sheetId="1"/></sheets></workbook>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("xl/workbook.xml")); z.write(wb.toByteArray()); z.closeEntry() }
        assertTrue(err(bos.toByteArray()) is ImportError.Unreadable)
    }

    @Test fun tooLargeFileIsReported() {
        val vm = ImportViewModel({ throw FileTooLargeException(MAX_IMPORT_BYTES) }, flowOf("+965"), dispatcher)
        vm.open(uri)
        assertEquals(ImportError.TooLarge(50), (vm.state.value as ImportState.Failed).error)
    }

    @Test fun importCreatesContactsAndReports() = runTest(dispatcher) {
        val vm = importVm(vcf(3))
        vm.open(uri)
        val target = FakeSource()
        target.failCreate["#1"] = "no space"
        val acct = AccountTag("com.example", "Phone", "Phone", false)
        vm.importInto(target, acct)
        val d = vm.state.value as ImportState.Done
        assertEquals(3, d.report.total)
        assertEquals(2, d.report.written)
        assertEquals(listOf("Name 2" to "no space"), d.report.failed)
        assertEquals(2, target.all().size)
        assertTrue(target.all().all { it.account == acct })
        // a VCF's own ids/etags never leak into the new contacts
        assertTrue(target.log.single().startsWith("create:"))
    }

    @Test fun leavingOrOpeningDuringAnImportNeverCancelsIt() = runTest(dispatcher) {
        val files = mapOf(uri to vcf(3), Uri.parse("content://test/second") to vcf(2))
        val vm = ImportViewModel({ files.getValue(it) }, flowOf("+965"), dispatcher)
        vm.open(uri)
        val inner = FakeSource()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : com.ilukezippo.vcfdesk.data.ContactSource by inner {
            override suspend fun create(cs: List<Contact>, target: AccountTag?, onProgress: (Int) -> Unit): com.ilukezippo.vcfdesk.data.WriteResult {
                gate.await()
                return inner.create(cs, target, onProgress)
            }
        }
        vm.importInto(slow, null)
        assertTrue(vm.isImporting)
        vm.reset()
        vm.open(Uri.parse("content://test/second"))
        assertTrue(vm.isImporting)
        gate.complete(Unit)
        val done = vm.state.value as ImportState.Done
        assertEquals(3, done.report.written)
        assertEquals(3, inner.all().size)
        // the report stays until it is dismissed; then the queued file opens
        vm.open(Uri.parse("content://test/second"))
        assertTrue(vm.state.value is ImportState.Done)
        vm.reset()
        assertEquals(2, (vm.state.value as ImportState.Ready).count)
    }

    // ---- export

    private fun exportVm(sink: MutableList<Pair<Uri, ByteArray>> = ArrayList()) =
        ExportViewModel(
            { u -> object : ByteArrayOutputStream() { override fun close() { super.close(); sink += u to toByteArray() } } },
            { File(tmp.root, "share/$it") }, dispatcher,
        )

    private fun seeded(n: Int): Pair<FakeSource, List<Contact>> {
        val src = FakeSource()
        val cs = people(n).map { src.seed(it) }
        return src to cs
    }

    @Test fun exportScopeSelectionOnly() {
        val (src, cs) = seeded(4)
        val sink = ArrayList<Pair<Uri, ByteArray>>()
        val vm = exportVm(sink)
        vm.configure(src, results = cs.take(3), selected = setOf(cs[2].id))
        vm.export(ExportScope.SELECTION, ExportFormat.ANDROID_VCF, uri)
        val out = VCardReader.read(sink.single().second)
        assertEquals(listOf("Name 3"), out.map { it.name.display })
        assertEquals(1, (vm.state.value as ExportState.Done).report.exported)

        sink.clear()
        vm.export(ExportScope.RESULTS, ExportFormat.ANDROID_VCF, uri)
        assertEquals(3, VCardReader.read(sink.single().second).size)
        sink.clear()
        vm.export(ExportScope.ALL, ExportFormat.ANDROID_VCF, uri)
        assertEquals(4, VCardReader.read(sink.single().second).size)
    }

    @Test fun selectionMayIncludeContactsTheSearchHides() {
        val (src, cs) = seeded(3)
        val sink = ArrayList<Pair<Uri, ByteArray>>()
        val vm = exportVm(sink)
        vm.configure(src, results = cs.take(1), selected = setOf(cs[0].id, cs[2].id))
        vm.export(ExportScope.SELECTION, ExportFormat.ANDROID_VCF, uri)
        assertEquals(setOf("Name 1", "Name 3"), VCardReader.read(sink.single().second).map { it.name.display }.toSet())
    }

    @Test fun contactsWithUnreadablePhotosAreSkippedAndReported() {
        val src = FakeSource()
        val a = src.seed(people(1)[0].copy(photo = ByteArray(10) { 1 }))
        val b = src.seed(people(2)[1])
        src.photoFails += a.id
        val sink = ArrayList<Pair<Uri, ByteArray>>()
        val vm = exportVm(sink)
        vm.configure(src, listOf(a, b), emptySet())
        vm.export(ExportScope.RESULTS, ExportFormat.CSV, uri)
        val r = (vm.state.value as ExportState.Done).report
        assertEquals(1, r.exported)
        assertEquals(1, r.skipped.size)
        assertEquals("Name 1", r.skipped[0].first)
        assertEquals(1, sink.size)
    }

    @Test fun excelReportsPhotosLeftOut() {
        val src = FakeSource()
        val big = src.seed(people(1)[0].copy(photo = ByteArray(30000) { 0x41 }))
        val vm = exportVm()
        vm.configure(src, listOf(big), emptySet())
        vm.export(ExportScope.RESULTS, ExportFormat.EXCEL, uri)
        assertEquals(1, (vm.state.value as ExportState.Done).report.excelPhotosOmitted)
        vm.export(ExportScope.RESULTS, ExportFormat.ANDROID_VCF, uri)
        assertEquals(0, (vm.state.value as ExportState.Done).report.excelPhotosOmitted)
    }

    @Test fun shareWritesACacheFileAndPostsIt() {
        val (src, cs) = seeded(2)
        val vm = exportVm()
        vm.configure(src, cs, emptySet())
        vm.share(ExportScope.RESULTS, ExportFormat.EXCEL)
        val s = vm.pendingShare.value!!
        assertEquals("contacts.xlsx", s.file.name)
        assertEquals(2, Xlsx.sheets(s.file.readBytes()).getValue("Contacts").rows.size)
        vm.shareHandled()
        assertNull(vm.pendingShare.value)
    }

    @Test fun vcfExportStreamsInChunks() {
        val (src, cs) = seeded(120)
        val sink = ArrayList<Pair<Uri, ByteArray>>()
        val vm = exportVm(sink)
        vm.configure(src, cs, emptySet())
        vm.export(ExportScope.RESULTS, ExportFormat.ANDROID_VCF, uri)
        assertEquals(120, VCardReader.read(sink.single().second).size)
        assertEquals(120, (vm.state.value as ExportState.Done).report.exported)
        sink.clear()
        vm.export(ExportScope.RESULTS, ExportFormat.SPLIT_VCF_ZIP, uri)
        assertTrue(sink.single().second.isNotEmpty())
    }

    @Test fun nothingExportedWritesNoFile() {
        val src = FakeSource()
        val sink = ArrayList<Pair<Uri, ByteArray>>()
        val vm = exportVm(sink)
        vm.configure(src, emptyList(), emptySet())
        vm.export(ExportScope.RESULTS, ExportFormat.ANDROID_VCF, uri)
        assertTrue(sink.isEmpty())
        assertEquals(0, (vm.state.value as ExportState.Done).report.exported)
    }

    @Test fun formatsHaveMimeAndName() {
        assertEquals("contacts.vcf", ExportFormat.ANDROID_VCF.fileName())
        assertEquals("contacts.zip", ExportFormat.SPLIT_VCF_ZIP.fileName())
        assertEquals("text/csv", ExportFormat.OUTLOOK_CSV.mime())
        assertEquals("contacts.xlsx", ExportFormat.EXCEL.fileName())
    }

    // ---- open with

    @Test fun openWithIntentRoutesToImport() {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/x-vcard")
        assertEquals(uri, importUriFrom(view))
        val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(uri, importUriFrom(send))
        assertNull(importUriFrom(Intent(Intent.ACTION_MAIN)))
        assertNull(importUriFrom(null))

        val nav = AppNav()
        assertFalse(nav.handle(Intent(Intent.ACTION_MAIN)))
        assertEquals(Screen.Main, nav.screen.value)
        assertTrue(nav.handle(view))
        assertEquals(Screen.Import, nav.screen.value)
        assertEquals(uri, nav.importUri.value)
        nav.importUriTaken()
        assertNull(nav.importUri.value)
        nav.back()
        assertEquals(Screen.Main, nav.screen.value)
    }

    private fun Csv(t: Table): ByteArray = com.ilukezippo.vcfdesk.core.table.Csv.write(t)
}
