package com.ilukezippo.vcfdesk.core.table

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsxTest {
    private fun entry(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == name) return z.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("no $name")
    }

    @Test fun roundTrip() {
        val t = Table(listOf("الاسم", "ملاحظات", "x"), listOf(
            listOf("اسم1", "سطر1\nسطر2", "+96599999999"),
            listOf("Name <&> 2", "", "00123"),
        ))
        val s = Xlsx.sheets(Xlsx.write(t, "Contacts"))
        assertEquals(listOf("Contacts"), s.keys.toList())
        assertEquals(t, s["Contacts"])
    }

    @Test fun rtlFlag() {
        val t = Table(listOf("a"), listOf(listOf("b")))
        assertTrue(entry(Xlsx.write(t, rtl = true), "xl/worksheets/sheet1.xml").contains("rightToLeft=\"1\""))
        assertTrue(!entry(Xlsx.write(t), "xl/worksheets/sheet1.xml").contains("rightToLeft"))
    }

    @Test fun controlCharsAreStripped() {
        val s = Xlsx.sheets(Xlsx.write(Table(listOf("a"), listOf(listOf("x\u0001y")))))
        assertEquals("xy", s.getValue("Contacts").rows[0][0])
    }

    @Test fun readsSharedStringsNumbersAndGaps() {
        // hand-built workbook: shared strings, numeric, boolean, a skipped column, rich text with phonetic run
        val sheet = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>
            <row r="2"><c r="A2"><v>99999999</v></c><c r="B2" t="b"><v>1</v></c><c r="C2"><v>1.5</v></c></row>
            <row r="3"/></sheetData></worksheet>"""
        val ss = """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Name</t></si>
            <si><r><t>Pho</t></r><r><t>ne</t></r><rPh><t>skip</t></rPh></si></sst>"""
        val wb = """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S1" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>"""
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            for ((n, d) in listOf("xl/workbook.xml" to wb, "xl/_rels/workbook.xml.rels" to rels, "xl/sharedStrings.xml" to ss, "xl/worksheets/sheet1.xml" to sheet)) {
                z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(d.toByteArray()); z.closeEntry()
            }
        }
        val t = Xlsx.sheets(bos.toByteArray()).getValue("S1")
        assertEquals(listOf("Name", "", "Phone"), t.headers)
        assertEquals(listOf("99999999", "TRUE", "1.5"), t.rows[0])
    }
}
