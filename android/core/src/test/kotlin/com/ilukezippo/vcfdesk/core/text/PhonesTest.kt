package com.ilukezippo.vcfdesk.core.text

import org.junit.Test
import org.junit.Assert.assertEquals

class PhonesTest {
    @Test fun addCountryCode() {
        assertEquals("+96555555555", Phones.addCountryCode("55555555"))
        assertEquals("+96599999999", Phones.addCountryCode("+96599999999"))
        assertEquals("1234", Phones.addCountryCode("1234"))
    }
    @Test fun normaliseRules() {
        assertEquals("+96566666666", Phones.normalise("66666666.0", "+965"))
        assertEquals("+96555555555", Phones.normalise("5555-5555", "+965"))
        assertEquals("+96555555555", Phones.normalise("0096555555555", "+965"))
        assertEquals("+96555555555", Phones.normalise("96555555555", "+965"))
        assertEquals("55555555", Phones.normalise("55555555", ""))
    }
    @Test fun normaliseDates() {
        assertEquals("1990-02-01", Dates.normalise("1/2/1990"))
        assertEquals("1990-02-01", Dates.normalise("1990-02-01"))
        assertEquals("1990-02-01", Dates.normalise("1990/2/1"))
        assertEquals("2000-01-01", Dates.normalise("36526"))
        assertEquals("--02-01", Dates.normalise("--02-01"))
        assertEquals("junk", Dates.normalise("junk"))
        assertEquals("31/02/1990", Dates.normalise("31/02/1990"))
    }
}
