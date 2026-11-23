package com.ilukezippo.vcfdesk.core.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RowImportTest {
    @Test fun middleNameOptions() {
        val t = Table(listOf("الاسم", "الهاتف"), listOf(listOf("Name1 Name2 Name3 Name4 Name5", "99999999")))
        val m = listOf(Field.FULL_NAME, Field.MOBILE)
        val c = RowImport.toContacts(t, m, "+965").single()
        assertEquals("Name2 Name3 Name4", c.name.middle)
        assertEquals("+96599999999", c.phones.single().value)
        val one = RowImport.toContacts(t, m, "+965", oneMiddle = true).single()
        assertEquals("Name2", one.name.middle)
        assertEquals("Name1 Name2 Name5", com.ilukezippo.vcfdesk.core.text.Names.compose(one.name))
    }

    @Test fun mixedFields() {
        val t = Table(
            listOf("n", "p", "b", "g", "ph", "x"),
            listOf(listOf("Name 1", "٩٩٩٩٩٩٩٩ / 66666666", "15/03/1980", "a, b،c", "not an image", "ignored")),
        )
        val c = RowImport.toContacts(t, listOf(Field.FULL_NAME, Field.MOBILE, Field.BIRTHDAY, Field.GROUPS, Field.PHOTO, Field.SKIP), "+965").single()
        assertEquals(listOf("+96599999999", "+96566666666"), c.phones.map { it.value })
        assertEquals("1980-03-15", c.events.single().value)
        assertEquals(setOf("a", "b", "c"), c.groups)
        assertNull(c.photo)
    }

    @Test fun emptyRowsSkipped() {
        val t = Table(listOf("n"), listOf(listOf(""), listOf("Name 1")))
        assertEquals(1, RowImport.toContacts(t, listOf(Field.FULL_NAME)).size)
    }
}
