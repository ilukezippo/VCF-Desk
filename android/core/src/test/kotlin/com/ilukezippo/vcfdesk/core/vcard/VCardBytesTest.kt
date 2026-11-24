package com.ilukezippo.vcfdesk.core.vcard

import org.junit.Assert.assertEquals
import org.junit.Test

class VCardBytesTest {
    private val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:اسم ١\r\nN:١;اسم;;;\r\nTEL:55555555\r\nEND:VCARD\r\n"

    @Test fun utf16LeBomArabic() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + card.toByteArray(Charsets.UTF_16LE)
        assertEquals("اسم ١", VCardReader.read(bytes).single().name.display)
    }

    @Test fun utf16BeBomArabic() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + card.toByteArray(Charsets.UTF_16BE)
        assertEquals("اسم ١", VCardReader.read(bytes).single().name.display)
    }

    @Test fun utf8WithAndWithoutBom() {
        val plain = card.toByteArray(Charsets.UTF_8)
        assertEquals("اسم ١", VCardReader.read(plain).single().name.display)
        assertEquals("اسم ١", VCardReader.read(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + plain).single().name.display)
    }

    @Test fun windows1256Arabic() {
        val arabic = "BEGIN:VCARD\r\nVERSION:2.1\r\nFN:محمد\r\nN:محمد;;;;\r\nTEL:55555555\r\nEND:VCARD\r\n"
        val bytes = arabic.toByteArray(charset("windows-1256"))
        assertEquals("محمد", VCardReader.read(bytes).single().name.display)
    }
}
