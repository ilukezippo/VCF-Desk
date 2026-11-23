package com.ilukezippo.vcfdesk.core.table

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    @Test fun writeHasUtf8BomAndCrlf() {
        val b = Csv.write(Table(listOf("الاسم", "x"), listOf(listOf("اسم1", "a,b"))))
        assertArrayEquals(bom, b.copyOf(3))
        assertEquals("الاسم,x\r\nاسم1,\"a,b\"\r\n", b.copyOfRange(3, b.size).toString(Charsets.UTF_8))
    }

    @Test fun cellsWithLineBreaksAndQuotes() {
        val t = Table(listOf("الاسم", "ملاحظات", "الهاتف"), listOf(
            listOf("Name", "سطر1\nسطر2", "99999999"),
            listOf("Name", "say \"hi\"", "66666666"),
        ))
        val back = Csv.read(Csv.write(t))
        assertEquals(t, back)
        assertEquals("سطر1\nسطر2", back.rows[0][1])
    }

    @Test fun semicolonAndTabDelimiters() {
        assertEquals(listOf("a", "b"), Csv.read("a;b\n1;2\n".toByteArray()).headers)
        assertEquals(listOf("1", "2"), Csv.read("a\tb\n1\t2\n".toByteArray()).rows[0])
    }

    @Test fun trimsCellsAndTrailingEmptyRows() {
        val t = Csv.read("a,b\n 1 , 2\n,\n\n".toByteArray())
        assertEquals(listOf(listOf("1", "2")), t.rows)
    }

    @Test fun utf16BomIsDecoded() {
        val b = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "اسم,x\r\n1,2\r\n".toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf("اسم", "x"), Csv.read(b).headers)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "اسم,x\r\n1,2\r\n".toByteArray(Charsets.UTF_16BE)
        assertEquals(listOf("اسم", "x"), Csv.read(be).headers)
    }

    @Test fun fallsBackToWindows1256() {
        val b = "الاسم,الهاتف\r\nاسم,99999999\r\n".toByteArray(charset("windows-1256"))
        val t = Csv.read(b)
        assertEquals(listOf("الاسم", "الهاتف"), t.headers)
        assertEquals("اسم", t.rows[0][0])
    }

    @Test fun emptyInput() {
        assertEquals(Table(emptyList(), emptyList()), Csv.read(ByteArray(0)))
    }
}
