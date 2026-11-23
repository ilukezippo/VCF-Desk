package com.ilukezippo.vcfdesk.data.google

import com.ilukezippo.vcfdesk.core.model.Address
import com.ilukezippo.vcfdesk.core.model.Typed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleMapperTest {
    private val groups = mapOf("contactGroups/abc" to "Family")
    private val groupIds = mapOf("Family" to "contactGroups/abc", "Work" to "contactGroups/w1")

    private val person: JsonObject = Json.parseToJsonElement(
        """
        {
          "resourceName": "people/c1",
          "etag": "%EgUBAgMuNz0aBAECBQc=",
          "metadata": {"sources": [{"type": "CONTACT", "id": "1", "etag": "#abc"}], "objectType": "PERSON"},
          "names": [{"metadata": {"primary": true, "source": {"type": "CONTACT", "id": "1"}},
                     "displayName": "Name 1 Test", "givenName": "Name 1", "familyName": "Test",
                     "honorificPrefix": "Dr", "phoneticGivenName": "nayim"}],
          "nicknames": [{"value": "N1"}],
          "phoneNumbers": [
            {"metadata": {"primary": true}, "value": "+965 5555 5555", "canonicalForm": "+96555555555", "type": "mobile", "formattedType": "Mobile"},
            {"value": "99999999", "type": "Shop line", "formattedType": "Shop line", "futureKey": 7}
          ],
          "emailAddresses": [{"value": "name1@example.com", "type": "work", "displayName": "N One"}],
          "addresses": [{"type": "home", "streetAddress": "Street 1", "city": "Kuwait City", "postalCode": "12345",
                         "country": "Kuwait", "countryCode": "KW", "poBox": "PO 9", "extendedAddress": "Block 2",
                         "region": "Capital", "formattedValue": "Street 1\nKuwait City"}],
          "organizations": [{"name": "Acme", "department": "IT", "title": "Engineer", "jobDescription": "builds", "symbol": "ACM"}],
          "urls": [{"value": "https://example.com", "type": "homePage"}],
          "birthdays": [{"date": {"year": 1990, "month": 2, "day": 1}}],
          "events": [{"date": {"month": 6, "day": 15}, "type": "anniversary"}],
          "relations": [{"person": "Name 2", "type": "spouse"}],
          "imClients": [{"username": "name1", "protocol": "skype", "type": "work"}],
          "biographies": [{"value": "Note line\nمرحبا", "contentType": "TEXT_PLAIN"}],
          "memberships": [
            {"contactGroupMembership": {"contactGroupId": "myContacts", "contactGroupResourceName": "contactGroups/myContacts"}},
            {"contactGroupMembership": {"contactGroupId": "abc", "contactGroupResourceName": "contactGroups/abc"}}
          ],
          "photos": [{"url": "https://lh3.example/p.jpg"}],
          "userDefined": [{"key": "k", "value": "v"}],
          "externalIds": [{"value": "E1", "type": "account"}],
          "clientData": [{"key": "app", "value": "1"}]
        }
        """.trimIndent(),
    ).jsonObject

    private fun JsonObject.arr(k: String): JsonArray = getValue(k).jsonArray
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    @Test
    fun mapsPersonFields() {
        val c = PeopleMapper.toContact(person, groups)
        assertEquals("people/c1", c.id)
        assertEquals("%EgUBAgMuNz0aBAECBQc=", c.etag)
        assertEquals("Name 1", c.name.first)
        assertEquals("Test", c.name.last)
        assertEquals("Dr", c.name.prefix)
        assertEquals("nayim", c.name.phoneticFirst)
        assertEquals("Name 1 Test", c.name.display)
        assertEquals("N1", c.name.nickname)
        assertEquals(listOf(Typed("Mobile", "+965 5555 5555", pref = true), Typed("Shop line", "99999999")), c.phones)
        assertEquals(listOf(Typed("Work", "name1@example.com")), c.emails)
        assertEquals(
            listOf(Address("Home", "Street 1", "Kuwait City", "Capital", "12345", "Kuwait", "PO 9", "Block 2")),
            c.addresses,
        )
        assertEquals("Acme", c.org.company)
        assertEquals("IT", c.org.department)
        assertEquals("Engineer", c.org.title)
        assertEquals(listOf(Typed("Homepage", "https://example.com")), c.websites)
        assertEquals(listOf(Typed("birthday", "1990-02-01"), Typed("anniversary", "--06-15")), c.events)
        assertEquals(listOf(Typed("Spouse", "Name 2")), c.relations)
        assertEquals(listOf(Typed("Skype", "name1")), c.ims)
        assertEquals("Note line\nمرحبا", c.notes)
        assertEquals(setOf("Family"), c.groups)
    }

    @Test
    fun updateMaskOnlyNamesEditedFields() {
        val before = PeopleMapper.toContact(person, groups)
        val after = before.copy(org = before.org.copy(title = "Manager"))
        assertEquals("organizations", PeopleMapper.updateMask(before, after))
        assertEquals("", PeopleMapper.updateMask(before, before.copy()))
        val more = after.copy(groups = setOf("Family", "Work"), name = after.name.copy(nickname = "x"))
        assertEquals("nicknames,organizations,memberships", PeopleMapper.updateMask(before, more))
        val bday = before.copy(events = listOf(Typed("birthday", "1991-02-01"), before.events[1]))
        assertEquals("birthdays", PeopleMapper.updateMask(before, bday))
    }

    @Test
    fun keepsUnknownPersonKeys() {
        val c = PeopleMapper.toContact(person, groups)
        val edited = c.copy(org = c.org.copy(title = "Manager"), phones = c.phones + Typed("Work", "66666666"))
        val out = PeopleMapper.toPerson(edited, groupIds)
        for (k in listOf("metadata", "userDefined", "externalIds", "clientData", "photos", "resourceName")) {
            assertEquals(k, person[k], out[k])
        }
        assertEquals(person.str("etag"), out.str("etag"))
        // the edited organization keeps its unmodelled keys
        val org = out.arr("organizations")[0].jsonObject
        assertEquals("Manager", org.str("title"))
        assertEquals("builds", org.str("jobDescription"))
        assertEquals("ACM", org.str("symbol"))
        // untouched phone entries are kept verbatim (metadata, unknown keys), the new one is appended
        val phones = out.arr("phoneNumbers")
        assertEquals(person.arr("phoneNumbers")[0], phones[0])
        assertEquals(person.arr("phoneNumbers")[1], phones[1])
        assertEquals("66666666", phones[2].jsonObject.str("value"))
        assertEquals("work", phones[2].jsonObject.str("type"))
    }

    @Test
    fun unchangedContactRoundTripsVerbatim() {
        val c = PeopleMapper.toContact(person, groups)
        assertEquals(person, PeopleMapper.toPerson(c, groupIds))
    }

    @Test
    fun editedEntryKeepsItsUnknownKeysAndCustomLabel() {
        val c = PeopleMapper.toContact(person, groups)
        val edited = c.copy(phones = listOf(c.phones[0], c.phones[1].copy(value = "66666666")))
        val p = PeopleMapper.toPerson(edited, groupIds).arr("phoneNumbers")[1].jsonObject
        assertEquals("66666666", p.str("value"))
        assertEquals("Shop line", p.str("type"))
        assertEquals("7", p.str("futureKey"))
    }

    @Test
    fun editedAddressDropsStaleFormattedValueButKeepsCountryCode() {
        val c = PeopleMapper.toContact(person, groups)
        val edited = c.copy(addresses = listOf(c.addresses[0].copy(city = "Hawalli")))
        val a = PeopleMapper.toPerson(edited, groupIds).arr("addresses")[0].jsonObject
        assertEquals("Hawalli", a.str("city"))
        assertEquals("KW", a.str("countryCode"))
        assertFalse(a.containsKey("formattedValue"))
    }

    @Test
    fun membershipsKeepSystemGroupsAndMapNames() {
        val c = PeopleMapper.toContact(person, groups)
        val out = PeopleMapper.toPerson(c.copy(groups = setOf("Work")), groupIds).arr("memberships")
        val names = out.map { it.jsonObject.getValue("contactGroupMembership").jsonObject.str("contactGroupResourceName") }
        assertEquals(listOf("contactGroups/myContacts", "contactGroups/w1"), names)
    }

    @Test
    fun newContactJoinsMyContactsAndUsesApiTypes() {
        val c = PeopleMapper.toContact(person, groups).copy(id = "", etag = null, extras = emptyList())
        val out = PeopleMapper.toPerson(c, groupIds)
        assertFalse(out.containsKey("resourceName"))
        assertFalse(out.containsKey("etag"))
        assertEquals("mobile", out.arr("phoneNumbers")[0].jsonObject.str("type"))
        assertEquals("Shop line", out.arr("phoneNumbers")[1].jsonObject.str("type"))
        assertEquals("homePage", out.arr("urls")[0].jsonObject.str("type"))
        assertEquals("skype", out.arr("imClients")[0].jsonObject.str("protocol"))
        val bd = out.arr("birthdays")[0].jsonObject.getValue("date").jsonObject
        assertEquals("1990", bd.str("year"))
        val ev = out.arr("events")[0].jsonObject.getValue("date").jsonObject
        assertFalse(ev.containsKey("year"))
        val ms = out.arr("memberships").map { it.jsonObject.getValue("contactGroupMembership").jsonObject.str("contactGroupResourceName") }
        assertTrue("contactGroups/myContacts" in ms)
        assertTrue("contactGroups/abc" in ms)
    }

    @Test
    fun organizationBeyondFirstAndUnitsArePreserved() {
        val p2 = JsonObject(person + ("organizations" to Json.parseToJsonElement("""[{"name":"A"},{"name":"B","title":"x"}]""")))
        val c = PeopleMapper.toContact(p2, groups).let { it.copy(org = it.org.copy(units = listOf("U1"), title = "T")) }
        val orgs = PeopleMapper.toPerson(c, groupIds).arr("organizations")
        assertEquals(2, orgs.size)
        assertEquals("T", orgs[0].jsonObject.str("title"))
        assertEquals(p2.arr("organizations")[1], orgs[1])
    }

    @Test
    fun unicodeDigitsInEventDates() {
        val c = PeopleMapper.toContact(person, groups).copy(id = "", extras = emptyList(), events = listOf(Typed("birthday", "١٩٩٠-٠٢-٠١")))
        val d = PeopleMapper.toPerson(c, groupIds).arr("birthdays")[0].jsonObject.getValue("date").jsonObject
        assertEquals("1990", d.str("year"))
        assertEquals("2", d.str("month"))
    }

    @Test
    fun yearOnlyEventIsNotReadAsSpreadsheetSerial() {
        val p2 = JsonObject(person + ("events" to Json.parseToJsonElement("""[{"date":{"year":1990},"type":"anniversary"}]""")))
        val c = PeopleMapper.toContact(p2, groups)
        assertEquals(Typed("anniversary", "1990"), c.events[1])
        val edited = c.copy(events = listOf(c.events[0], c.events[1].copy(label = "other")))
        val d = PeopleMapper.toPerson(edited, groupIds).arr("events")[0].jsonObject.getValue("date").jsonObject
        assertEquals("1990", d.str("year"))
        assertFalse(d.containsKey("month"))
    }
}
