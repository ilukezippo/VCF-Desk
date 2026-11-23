package com.ilukezippo.vcfdesk.core.vcard

import com.ilukezippo.vcfdesk.core.model.Address
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Extra
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Org
import com.ilukezippo.vcfdesk.core.model.Typed
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardTest {
    private val android21 =
        "BEGIN:VCARD\r\nVERSION:2.1\r\n" +
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=D8=A7=D8=B3=D9=852;=D8=A7=D8=B3=D9=851;;;\r\n" +
            "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=D8=A7=D8=B3=D9=851 =D8=A7=D8=B3=D9=852\r\n" +
            "TEL;CELL;PREF:+96599999999\r\n" +
            "NOTE;ENCODING=QUOTED-PRINTABLE:line one=0D=0Aline two with a soft =\r\nbreak\r\n" +
            "X-ANDROID-CUSTOM:vnd.android.cursor.item/nickname;Mo;1;;;;;;;;;;;;;\r\n" +
            "END:VCARD\r\n"

    private val iphone30 =
        "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Name;Name;;;\r\nFN:Name Name\r\n" +
            "ORG:Company;Department;\r\nitem1.TEL;type=pref:55555555\r\n" +
            "item1.X-ABLabel:_\$!<Mobile>!\$_\r\nEMAIL;type=INTERNET;type=HOME:name@example.com\r\n" +
            "ADR;type=HOME:;;Street\\, 2;City;Region;00000;Country\r\n" +
            "NOTE:semicolon\\; comma\\, and\\nnewline\r\nEND:VCARD\r\n"

    private fun demo(): String =
        javaClass.getResourceAsStream("/demo.vcf")!!.readBytes().toString(Charsets.UTF_8)

    @Test
    fun androidQuotedPrintableArabic() {
        val c = VCardReader.read(android21).single()
        assertEquals("اسم1 اسم2", c.name.display)
        assertEquals("اسم2", c.name.last)
        assertEquals("اسم1", c.name.first)
        assertEquals("line one\nline two with a soft break", c.notes)
        assertEquals(listOf(Typed("Mobile", "+96599999999", true)), c.phones)
        assertEquals(
            listOf(Extra("vcard", "X-ANDROID-CUSTOM:vnd.android.cursor.item/nickname;Mo;1;;;;;;;;;;;;;")),
            c.extras,
        )
    }

    @Test
    fun iphoneItemLabels() {
        val c = VCardReader.read(iphone30).single()
        assertEquals(listOf(Typed("Mobile", "55555555", true)), c.phones)
        assertEquals(listOf(Typed("Home", "name@example.com")), c.emails)
        assertEquals("Street, 2", c.addresses.single().street)
        assertEquals("Home", c.addresses.single().label)
        assertEquals("semicolon; comma, and\nnewline", c.notes)
        assertEquals(Org("Company", "Department"), c.org)
        assertTrue(c.extras.isEmpty())

        val custom = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:X\r\nitem1.TEL:55555555\r\nitem1.X-ABLabel:Work Mobile\r\nEND:VCARD\r\n"
        val p = VCardReader.read(custom).single()
        assertEquals(listOf(Typed("Work Mobile", "55555555")), p.phones)
        assertTrue(p.extras.isEmpty())
        // the custom label survives a write and re-read in every version
        for (v in VCardVersion.values()) {
            val back = VCardReader.read(VCardWriter.write(listOf(p), v)).single()
            assertEquals(listOf(Typed("Work Mobile", "55555555")), back.phones)
        }
    }

    @Test
    fun unknownPropertiesSurvive() {
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Name\r\nX-CUSTOM:abc\r\nitem3.X-FOO:bar\r\nitem3.X-ABLabel:keep\r\nEND:VCARD\r\n"
        val c = VCardReader.read(text).single()
        for (v in VCardVersion.values()) {
            val out = VCardWriter.write(listOf(c), v)
            assertTrue(v.name, out.contains("\r\nX-CUSTOM:abc\r\n"))
            assertTrue(v.name, out.contains("\r\nitem3.X-FOO:bar\r\n"))
            assertTrue(v.name, out.contains("\r\nitem3.X-ABLabel:keep\r\n"))
            assertEquals(c.extras, VCardReader.read(out).single().extras)
        }
    }

    @Test
    fun roundTripEveryVersion() {
        val cs = VCardReader.read(demo()) + VCardReader.read(android21 + iphone30)
        assertEquals(12, cs.size)
        assertTrue(cs.count { it.photo != null } >= 5)
        for (v in VCardVersion.values()) {
            val back = VCardReader.read(VCardWriter.write(cs, v))
            assertEquals(v.name, cs.size, back.size)
            for ((a, b) in cs.zip(back)) {
                assertEquals(v.name, a.name.display, b.name.display)
                assertEquals(v.name, a.name, b.name)
                assertEquals(v.name, a.phones, b.phones)
                assertEquals(v.name, a.org, b.org)
                assertEquals(v.name, a.emails, b.emails)
                assertEquals(v.name, a.addresses, b.addresses)
                assertEquals(v.name, a.notes, b.notes)
                assertEquals(v.name, a.photo == null, b.photo == null)
                if (a.photo != null) assertArrayEquals(v.name, a.photo, b.photo)
            }
        }
    }

    @Test
    fun foldedLinesAndEscapes() {
        val note = "Hello\\, world\\; this is a long note that needs folding at column seventy-five \\nsecond line"
        val line = "NOTE:$note"
        val folded = line.chunked(74).joinToString("\r\n ")
        assertTrue(folded.contains("\r\n "))
        val c = VCardReader.read("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:A\r\n$folded\r\nEND:VCARD\r\n").single()
        assertEquals("Hello, world; this is a long note that needs folding at column seventy-five \nsecond line", c.notes)
        // written output is folded at 75 bytes and reads back identically
        val long = c.copy(notes = "x, y; z\n" + "é".repeat(100))
        val out = VCardWriter.write(listOf(long), VCardVersion.V30)
        assertTrue(out.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertEquals(long.notes, VCardReader.read(out).single().notes)
    }

    @Test
    fun allModelledFieldsRoundTrip() {
        val c = Contact(
            name = Name("Dr", "اسم1", "Name2", "اسم3", "Jr", "Fon", "Lon", "Nick", "Display Name"),
            phones = listOf(
                Typed("Mobile", "55555555"), Typed("Home", "66666666"), Typed("Work", "99999999"),
                Typed("Home fax", "1"), Typed("Work fax", "2"), Typed("Pager", "3"),
                Typed("Other", "4"), Typed("My label", "5"),
            ),
            emails = listOf(Typed("Home", "a@example.com"), Typed("Work", "b@example.com"), Typed("Other", "c@example.com"), Typed("Custom", "d@example.com")),
            addresses = listOf(Address("Work", "Street; 1", "City", "Region", "00000", "Country"), Address("Other", "S2")),
            org = Org("Company", "Department", "Title"),
            websites = listOf(Typed("", "https://example.com"), Typed("Blog", "https://b.example.com")),
            events = listOf(Typed("birthday", "1990-02-01"), Typed("anniversary", "2010-05-06"), Typed("Graduation", "--03-04")),
            relations = listOf(Typed("Mother", "Name 7")),
            ims = listOf(Typed("Skype", "name.skype"), Typed("", "xmpp:foo@example.com"), Typed("Signal", "sig")),
            notes = "line1\nline2, with; punctuation \\ backslash",
            groups = setOf("Family", "Work, Team"),
            photo = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3),
            extras = listOf(Extra("vcard", "X-CUSTOM:abc")),
        )
        for (v in VCardVersion.values()) {
            val back = VCardReader.read(VCardWriter.write(listOf(c), v)).single()
            assertEquals(v.name, c, back)
        }
        assertNotNull(c.photo)
    }

    @Test
    fun displayNameIsComposedWhenEmpty() {
        val c = Contact(name = Name(first = "A", last = "B"))
        val back = VCardReader.read(VCardWriter.write(listOf(c), VCardVersion.V30)).single()
        assertEquals("A B", back.name.display)
    }

    @Test
    fun linkedPhotoStaysExtra() {
        val c = VCardReader.read("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:A\r\nPHOTO;VALUE=URI:http://example.com/a.jpg\r\nEND:VCARD\r\n").single()
        assertEquals(null, c.photo)
        assertEquals(listOf(Extra("vcard", "PHOTO;VALUE=URI:http://example.com/a.jpg")), c.extras)
    }

    private val arabicLong = "xx" + "ا".repeat(12) + " tail " +
        (1..30).joinToString(" ") { "كلمة$it" } + " " + "ب".repeat(40) + " "

    @Test
    fun v21QuotedPrintableKeepsSpacesAtSoftBreaks() {
        for (text in listOf("xx" + "ا".repeat(12) + " tail", arabicLong, "a ".repeat(60), " lead" + " ".repeat(80) + "x")) {
            assertEquals(text, QuotedPrintable.decode(VCardSyntax.logicalLines(
                "NOTE;ENCODING=QUOTED-PRINTABLE:" + QuotedPrintable.encode(text)).single().substringAfter(':')))
            val c = Contact(name = Name(display = "A"), notes = text)
            val out = VCardWriter.write(listOf(c), VCardVersion.V21)
            assertEquals(text, VCardReader.read(out).single().notes)
        }
    }

    @Test
    fun quotedPrintableExtraSurvivesNewerVersions() {
        val raw = "X-ANDROID-CUSTOM;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:" + QuotedPrintable.encode(arabicLong)
        val src = VCardReader.read("BEGIN:VCARD\r\nVERSION:2.1\r\nFN:A\r\n$raw\r\nEND:VCARD\r\n").single()
        assertEquals(1, src.extras.size)
        for (v in VCardVersion.values()) {
            val back = VCardReader.read(VCardWriter.write(listOf(src), v)).single()
            assertEquals(v.name, src.extras, back.extras)
            // the stored line still decodes to the original text
            val line = back.extras.single().raw
            assertEquals(arabicLong, QuotedPrintable.decode(line.substringAfter(':')))
        }
    }

    @Test
    fun uneditedRoundTripKeepsPrefParamsAndStructure() {
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Name\r\nN:;Name;;;\r\n" +
            "ORG:Company;Department;Unit1;Unit2\r\n" +
            "TEL;TYPE=CELL,PREF;LANGUAGE=ar:55555555\r\n" +
            "EMAIL;TYPE=INTERNET,WORK:name@example.com\r\n" +
            "ADR;TYPE=HOME,PREF;LABEL=\"Home, sweet\":Box 1;Apt 2;Street;City;Region;00000;Country\r\n" +
            "URL;TYPE=WORK:https://example.com\r\n" +
            "IMPP;X-SERVICE-TYPE=Signal:signal:name\r\n" +
            "BDAY;X-APPLE-OMIT-YEAR=1604:1604-02-01\r\n" +
            "END:VCARD\r\n"
        val c = VCardReader.read(text).single()
        assertEquals(Org("Company", "Department", "", listOf("Unit1", "Unit2")), c.org)
        assertEquals(Typed("Mobile", "55555555", true, mapOf("LANGUAGE" to "ar")), c.phones.single())
        val a = c.addresses.single()
        assertEquals("Box 1", a.poBox)
        assertEquals("Apt 2", a.extended)
        assertEquals(true, a.pref)
        assertEquals(mapOf("LABEL" to "\"Home, sweet\""), a.params)
        assertEquals(Typed("Work", "https://example.com"), c.websites.single())
        assertEquals(Typed("Signal", "signal:name", false, mapOf("X-SERVICE-TYPE" to "Signal")), c.ims.single())
        assertEquals(Typed("birthday", "1604-02-01", false, mapOf("X-APPLE-OMIT-YEAR" to "1604")), c.events.single())
        for (v in VCardVersion.values()) {
            assertEquals(v.name, c, VCardReader.read(VCardWriter.write(listOf(c), v)).single())
        }
        val v40 = VCardWriter.write(listOf(c), VCardVersion.V40)
        assertTrue(v40, v40.contains("PREF=1"))
        assertTrue(v40, !v40.contains("TYPE=pref") && !v40.lowercase().contains(",pref"))
        assertTrue(VCardWriter.write(listOf(c), VCardVersion.V30).contains("PREF"))
    }
}
