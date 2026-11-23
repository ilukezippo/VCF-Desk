package com.ilukezippo.vcfdesk.core.bulk

import com.ilukezippo.vcfdesk.core.model.*
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class BulkChangeTest {
    private fun c(id: String, company: String = "", photo: ByteArray? = null) =
        Contact(id = id, org = Org(company = company), photo = photo)

    @Test fun previewSkipsUnchanged() {
        val cs = listOf(c("1", "Old", byteArrayOf(1)), c("2", "X", byteArrayOf(2)), c("3"))
        val d = Bulk.preview(cs, listOf(BulkChange.SetField(TextField.COMPANY, "X")))
        assertEquals(2, d.size)
        assertEquals(FieldDiff("Company", "Old", "X"), d[0].changes.single())
        assertEquals(FieldDiff("Company", "", "X"), d[1].changes.single())
        assertTrue(d[0].after.photo.contentEquals(byteArrayOf(1)))
    }
    @Test fun cityCreatesAddressWhenMissing() {
        val r = Bulk.apply(c("1"), listOf(BulkChange.SetField(TextField.CITY, "Name City")))
        assertEquals("Name City", r.addresses.single().city)
        assertEquals("home", r.addresses.single().label)
        assertEquals(0, Bulk.apply(c("1"), listOf(BulkChange.SetField(TextField.CITY, ""))).addresses.size)
    }
    @Test fun cityAppliesToAllAddresses() {
        val x = c("1").copy(addresses = listOf(Address(label = "work", street = "s", poBox = "p"), Address(city = "o")))
        val r = Bulk.apply(x, listOf(BulkChange.SetField(TextField.COUNTRY, "KW")))
        assertEquals(listOf("KW", "KW"), r.addresses.map { it.country })
        assertEquals("p", r.addresses[0].poBox)
    }
    @Test fun findReplaceInTitle() {
        val x = c("1").copy(org = Org(title = "Senior Manager"))
        assertEquals("Junior Manager", Bulk.apply(x, listOf(BulkChange.FindReplace(TextField.TITLE, "senior", "Junior"))).org.title)
        assertEquals("Senior Manager", Bulk.apply(x, listOf(BulkChange.FindReplace(TextField.TITLE, "senior", "Junior", ignoreCase = false))).org.title)
    }
    @Test fun addRemoveGroup() {
        val x = c("1").copy(groups = setOf("A"))
        val r = Bulk.apply(x, listOf(BulkChange.AddGroup("B")))
        assertEquals(setOf("A", "B"), r.groups)
        assertEquals(setOf("B"), Bulk.apply(r, listOf(BulkChange.RemoveGroup("A"))).groups)
        val d = Bulk.preview(listOf(x), listOf(BulkChange.AddGroup("B"))).single()
        assertEquals(FieldDiff("Groups", "A", "A, B"), d.changes.single())
    }
    @Test fun appendNotes() {
        val x = c("1").copy(notes = "one\n")
        assertEquals("one\ntwo", Bulk.apply(x, listOf(BulkChange.AppendNotes("two"))).notes)
        assertEquals("two", Bulk.apply(c("2"), listOf(BulkChange.AppendNotes("two"))).notes)
    }
    @Test fun setNameFieldRefreshesDisplayOnlyIfAuto() {
        val auto = c("1").copy(name = Name(first = "A", last = "B", display = "A B"))
        assertEquals("Z B", Bulk.apply(auto, listOf(BulkChange.SetField(TextField.FIRST, "Z"))).name.display)
        val custom = c("1").copy(name = Name(first = "A", last = "B", display = "Boss"))
        assertEquals("Boss", Bulk.apply(custom, listOf(BulkChange.SetField(TextField.FIRST, "Z"))).name.display)
    }
    @Test fun firstMiddleOnlyChange() {
        val x = c("1").copy(name = Name(first = "A", middle = "عبد الله سالم", last = "L", display = "A عبد الله سالم L"))
        val r = Bulk.apply(x, listOf(BulkChange.FirstMiddleOnly))
        assertEquals("عبد الله", r.name.middle)
        assertEquals("A عبد الله L", r.name.display)
    }
    @Test fun countryCodePerPhoneKeepsMetadata() {
        val x = c("1").copy(phones = listOf(
            Typed("Mobile", "55555555", pref = true, params = mapOf("X" to "y")),
            Typed("Work", "+96599999999"), Typed("Home", "66666666")))
        val d = Bulk.preview(listOf(x), listOf(BulkChange.AddCountryCode())).single()
        assertEquals(listOf(FieldDiff("Phone", "55555555", "+96555555555"), FieldDiff("Phone", "66666666", "+96566666666")), d.changes)
        assertEquals(Typed("Mobile", "+96555555555", pref = true, params = mapOf("X" to "y")), d.after.phones[0])
    }
}
