package com.ilukezippo.vcfdesk.core.export

import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Typed
import com.ilukezippo.vcfdesk.core.table.ColumnGuess
import com.ilukezippo.vcfdesk.core.table.Csv
import com.ilukezippo.vcfdesk.core.table.Xlsx
import com.ilukezippo.vcfdesk.core.table.RowImport
import com.ilukezippo.vcfdesk.core.table.Table
import com.ilukezippo.vcfdesk.core.text.Phones
import com.ilukezippo.vcfdesk.core.vcard.VCardReader
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportersTest {
    private fun demo() = VCardReader.read(javaClass.getResourceAsStream("/demo.vcf")!!.readBytes().toString(Charsets.UTF_8))
    private fun display(c: Contact) = c.name.display.ifBlank { listOf(c.name.prefix, c.name.first, c.name.middle, c.name.last, c.name.suffix).filter { it.isNotBlank() }.joinToString(" ") }

    private fun reimport(t: Table): List<Contact> =
        RowImport.toContacts(t, t.headers.map { ColumnGuess.guess(it) }, "+965")

    @Test fun googleCsvHasBom() {
        val c = Contact(name = Name(first = "اسم1", last = "اسم2"), phones = listOf(Typed("Mobile", "+96599999999")))
        val b = Exporters.export(listOf(c), ExportFormat.GOOGLE_CSV)
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), b.copyOf(3))
        assertEquals("اسم1", Csv.read(b).rows[0][0])
    }

    @Test fun googleAndOutlookCsvReimport() {
        val cs = demo()
        for (f in listOf(ExportFormat.GOOGLE_CSV, ExportFormat.OUTLOOK_CSV)) {
            val t = Csv.read(Exporters.export(cs, f))
            val back = reimport(t)
            assertEquals(f.name, cs.size, back.size)
            for ((a, b) in cs.zip(back)) {
                assertEquals(f.name, display(a), display(b))
                assertEquals(f.name, a.phones.map { Phones.normalise(it.value, "+965") }.toSet(), b.phones.map { it.value }.toSet())
                // label columns must not become phone numbers
                assertTrue(b.phones.none { it.value in setOf("Mobile", "Home", "Work", "Other") })
            }
        }
    }

    @Test fun tableCsvRoundTrip() {
        val cs = demo()
        val back = reimport(Csv.read(Exporters.export(cs, ExportFormat.CSV)))
        assertEquals(cs.map { display(it) }, back.map { display(it) })
        assertEquals(cs.map { it.phones.map { p -> Phones.normalise(p.value, "+965") }.toSet() }, back.map { it.phones.map { p -> p.value }.toSet() })
    }

    @Test fun excelRoundTrip() {
        val cs = demo()
        assertTrue(cs.any { it.photo != null })
        val sheets = Xlsx.sheets(Exporters.export(cs, ExportFormat.EXCEL))
        val back = reimport(sheets.getValue("Contacts"))
        assertEquals(cs.map { display(it) }, back.map { display(it) })
        assertEquals(cs.count { it.photo != null }, back.count { it.photo != null })
        for ((a, b) in cs.zip(back)) assertArrayEquals(a.photo, b.photo)
    }

    private fun sheetXml(b: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == "xl/worksheets/sheet1.xml") return z.readBytes().toString(Charsets.UTF_8) }
        }
        error("none")
    }

    @Test fun excelRtlWhenMostlyArabic() {
        val ar = (1..3).map { Contact(name = Name(first = "اسم$it", display = "اسم$it")) }
        assertTrue(sheetXml(Exporters.export(ar, ExportFormat.EXCEL)).contains("rightToLeft=\"1\""))
        val en = (1..3).map { Contact(name = Name(first = "Name$it", display = "Name$it")) }
        assertTrue(!sheetXml(Exporters.export(en, ExportFormat.EXCEL)).contains("rightToLeft"))
    }

    private fun zipNames(b: ByteArray): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            while (true) { val e = z.nextEntry ?: break; m[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return m
    }

    @Test fun splitZipHasOneFilePerContact() {
        val cs = listOf("Name 1", "Name 1", "name 1", "a/b:c", "").map { Contact(name = Name(first = it, display = it)) }
        val m = zipNames(Exporters.export(cs, ExportFormat.SPLIT_VCF_ZIP))
        assertEquals(listOf("Name 1.vcf", "Name 1 (2).vcf", "name 1 (3).vcf", "a b c.vcf", "contact.vcf"), m.keys.toList())
        assertTrue(m.values.all { it.contains("VERSION:3.0") && it.contains("BEGIN:VCARD") })
        assertEquals(1, Regex("BEGIN:VCARD").findAll(m.getValue("Name 1.vcf")).count())
    }

    @Test fun vcfFormatsUseExpectedVersions() {
        val cs = listOf(Contact(name = Name(first = "Name", display = "Name"), phones = listOf(Typed("Mobile", "55555555"))))
        for ((f, v) in listOf(ExportFormat.ANDROID_VCF to "3.0", ExportFormat.IPHONE_VCF to "3.0", ExportFormat.VCARD4 to "4.0")) {
            assertTrue(f.name, Exporters.export(cs, f).toString(Charsets.UTF_8).contains("VERSION:$v"))
        }
    }

    @Test fun oversizedPhotoIsLeftOutOfExcelOnly() {
        val big = ByteArray(30000) { 0x41 }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        val c = Contact(name = Name(first = "Name", display = "Name"), photo = big)
        val x = Xlsx.sheets(Exporters.export(listOf(c), ExportFormat.EXCEL)).getValue("Contacts")
        assertTrue(x.rows[0].all { it.length <= 32767 })
        val csv = Csv.read(Exporters.export(listOf(c), ExportFormat.CSV))
        assertEquals(40000, csv.rows[0].last().length)
    }

    @Test fun excelPhotosOmittedCountsOnlyOversized() {
        val big = Contact(name = Name(display = "Name 1"), photo = ByteArray(30000) { 0x41 })
        val small = Contact(name = Name(display = "Name 2"), photo = ByteArray(1000) { 0x41 })
        val none = Contact(name = Name(display = "Name 3"))
        assertEquals(1, Exporters.excelPhotosOmitted(listOf(big, small, none)))
        assertEquals(0, Exporters.excelPhotosOmitted(listOf(small, none)))
    }

    @Test fun streamingEqualsInMemory() {
        val cs = demo()
        for (f in ExportFormat.entries.filter { Exporters.isStreamable(it) }) {
            val bos = java.io.ByteArrayOutputStream()
            Exporters.StreamWriter(f, bos).use { w -> cs.chunked(2).forEach { w.add(it) } }
            if (f == ExportFormat.SPLIT_VCF_ZIP) assertEquals(zipNames(Exporters.export(cs, f)), zipNames(bos.toByteArray()))
            else assertArrayEquals(f.name, Exporters.export(cs, f), bos.toByteArray())
        }
    }

    @Test fun closeKeepsTheFirstFailureAndClosesTheStream() {
        var closed = false
        val bad = object : java.io.OutputStream() {
            override fun write(b: Int) {}
            override fun flush() { throw java.io.IOException("flush") }
            override fun close() { closed = true; throw java.io.IOException("close") }
        }
        val w = Exporters.StreamWriter(ExportFormat.ANDROID_VCF, bad)
        val e = runCatching { w.close() }.exceptionOrNull()!!
        assertEquals("flush", e.message)
        assertEquals("close", e.suppressed.single().message)
        assertTrue(closed)
    }
}
