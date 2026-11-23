package com.ilukezippo.vcfdesk.core.text

import com.ilukezippo.vcfdesk.core.model.Name
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class NamesTest {
    @Test fun splitKeepsAbdAllah() {
        val n = Names.split("محمد عبد الله أحمد")
        assertEquals("محمد", n.first); assertEquals("عبد الله", n.middle); assertEquals("أحمد", n.last)
    }
    @Test fun oneMiddleKeepsFirstMiddleOnly() {
        val n = Names.split("A B C D", oneMiddle = true)
        assertEquals("B", n.middle); assertEquals("D", n.last); assertEquals("A", n.first)
    }
    @Test fun splitShortNames() {
        assertEquals(Name(first = "A"), Names.split("A"))
        assertEquals(Name(first = "A", last = "B"), Names.split("  A   B "))
        assertEquals(Name(), Names.split("  "))
    }
    @Test fun firstMiddleOnly() {
        assertEquals("عبد الله", Names.firstMiddleOnly("عبد الله سالم"))
        assertEquals("", Names.firstMiddleOnly(""))
    }
    @Test fun composeSkipsBlanks() {
        assertEquals("Dr A C", Names.compose(Name(prefix = "Dr", first = "A ", middle = " ", last = "C")))
    }
    @Test fun mostlyArabic() {
        assertTrue(Names.isMostlyArabic(listOf("محمد", "علي", "Name")))
        assertFalse(Names.isMostlyArabic(listOf("Name 1", "Name 2", "محمد")))
        assertFalse(Names.isMostlyArabic(emptyList()))
    }
}
