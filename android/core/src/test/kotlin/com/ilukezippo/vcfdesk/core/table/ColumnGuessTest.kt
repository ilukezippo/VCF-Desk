package com.ilukezippo.vcfdesk.core.table

import org.junit.Assert.assertEquals
import org.junit.Test

class ColumnGuessTest {
    private fun g(h: String) = ColumnGuess.guess(h)

    @Test fun columnGuessing() {
        assertEquals(Field.MOBILE, g("Mobile"))
        assertEquals(Field.MOBILE, g("الجوال"))
        assertEquals(Field.MOBILE, g("رقم الهاتف"))
        assertEquals(Field.FULL_NAME, g("الاسم"))
        assertEquals(Field.DEPARTMENT, g("Department"))
        assertEquals(Field.DEPARTMENT, g("القسم"))
        assertEquals(Field.TITLE, g("الرتبة"))
        assertEquals(Field.SKIP, g("#"))
        assertEquals(Field.SKIP, g(""))
        assertEquals(Field.SKIP, g("الرقم العسكري"))
        assertEquals(Field.SKIP, g("Phone 1 - Label"))
        assertEquals(Field.TITLE, g("Organization Title"))
        assertEquals(Field.CITY, g("Address 1 - City"))
        assertEquals(Field.PHONE_WORK, g("Business Phone"))
        assertEquals(Field.NICKNAME, g("Nickname"))
        assertEquals(Field.GROUPS, g("Labels"))
        assertEquals(Field.COUNTRY, g("Business Country/Region"))
    }
}
