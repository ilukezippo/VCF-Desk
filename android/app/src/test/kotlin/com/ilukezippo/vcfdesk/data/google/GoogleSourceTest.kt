package com.ilukezippo.vcfdesk.data.google

import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Typed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

class GoogleSourceTest {
    private val server = MockWebServer()
    private val requests = Collections.synchronizedList(ArrayList<Pair<RecordedRequest, JsonObject?>>())

    /** Path → handler; unmatched paths get 404. */
    private val routes = LinkedHashMap<String, (JsonObject?) -> MockResponse>()

    private lateinit var source: GoogleSource

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").body(body).build()

    private fun personJson(n: Int, title: String = "Engineer", extra: String = "") = """
        {"resourceName":"people/c$n","etag":"e$n","names":[{"givenName":"Name $n","displayName":"Name $n"}],
         "phoneNumbers":[{"value":"5555555$n","type":"mobile"}],
         "organizations":[{"name":"Acme","title":"$title"}],
         "memberships":[{"contactGroupMembership":{"contactGroupResourceName":"contactGroups/myContacts"}}],
         "userDefined":[{"key":"k","value":"v$n"}]$extra}
    """.trimIndent()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8()?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject }
                requests += request to body
                val h = routes["${request.method} ${request.url.encodedPath}"] ?: return json("""{"error":{"code":404,"message":"not found"}}""", 404)
                return h(body)
            }
        }
        server.start()
        routes["GET /v1/people/me/connections"] = { json("""{"connections":[${personJson(1)},${personJson(2)},${personJson(3)}]}""") }
        routes["GET /v1/contactGroups"] = {
            json(
                """{"contactGroups":[
                {"resourceName":"contactGroups/myContacts","name":"myContacts","formattedName":"My Contacts","groupType":"SYSTEM_CONTACT_GROUP"},
                {"resourceName":"contactGroups/abc","name":"Family","formattedName":"Family","groupType":"USER_CONTACT_GROUP"}]}""",
            )
        }
        val api = PeopleApi(OkHttpClient(), { "tok" }, server.url("/v1/").toString(), retryDelayMs = { 0L })
        source = GoogleSource("name1@example.com", api)
    }

    @After
    fun tearDown() = server.close()

    private fun calls(key: String) = requests.filter { "${it.first.method} ${it.first.url.encodedPath}" == key }

    private fun echoUpdate(failEtagOf: Set<String> = emptySet()): (JsonObject?) -> MockResponse = { body ->
        val contacts = body!!.getValue("contacts").jsonObject
        val entries = contacts.entries.joinToString(",") { (rn, p) ->
            if (rn in failEtagOf) {
                """"$rn":{"httpStatusCode":400,"status":{"code":9,"message":"Request person.etag is different than the current person.etag. Clear local cache and get the latest person."}}"""
            } else {
                val updated = JsonObject(p.jsonObject + ("etag" to kotlinx.serialization.json.JsonPrimitive("new-" + rn)))
                """"$rn":{"httpStatusCode":200,"person":$updated}"""
            }
        }
        json("""{"updateResult":{$entries}}""")
    }

    @Test
    fun listsContactsWithAccountAndUserGroups() = runTest {
        val list = source.contacts().first()
        assertEquals(3, list.size)
        val c = list.first { it.id == "people/c1" }
        assertEquals("com.google", c.account!!.type)
        assertEquals("name1@example.com", c.account!!.name)
        assertEquals("Google", c.account!!.label)
        assertEquals("e1", c.etag)
        assertEquals(listOf("Family"), source.groups())
    }

    @Test
    fun etagConflictReportedPerContact() = runTest {
        routes["POST /v1/people:batchUpdateContacts"] = echoUpdate(failEtagOf = setOf("people/c2"))
        val list = source.contacts().first()
        val edited = list.map { it.copy(org = it.org.copy(title = "Manager")) }
        val res = source.update(edited)
        assertEquals(listOf("people/c1", "people/c3"), res.written.sorted())
        assertEquals(setOf("people/c2"), res.failed.keys)
        assertTrue(res.failed.getValue("people/c2").contains("reload", ignoreCase = true))
        val sent = calls("POST /v1/people:batchUpdateContacts").single().second!!
        assertEquals("organizations", sent.getValue("updateMask").jsonPrimitive.content)
        val p1 = sent.getValue("contacts").jsonObject.getValue("people/c1").jsonObject
        assertEquals("e1", p1.getValue("etag").jsonPrimitive.content)
        assertEquals("v1", p1.getValue("userDefined").jsonArray[0].jsonObject.getValue("value").jsonPrimitive.content)
        // the cache now holds the new etag
        assertEquals("new-people/c1", source.contacts().first().first { it.id == "people/c1" }.etag)
    }

    @Test
    fun unchangedContactsMakeNoCalls() = runTest {
        val list = source.contacts().first()
        val res = source.update(list)
        assertEquals(3, res.written.size)
        assertTrue(calls("POST /v1/people:batchUpdateContacts").isEmpty())
    }

    @Test
    fun differentMasksGoInSeparateBatches() = runTest {
        routes["POST /v1/people:batchUpdateContacts"] = echoUpdate()
        val list = source.contacts().first().sortedBy { it.id }
        val res = source.update(
            listOf(list[0].copy(org = list[0].org.copy(title = "X")), list[1].copy(notes = "hello"), list[2].copy(org = list[2].org.copy(title = "Y"))),
        )
        assertEquals(3, res.written.size)
        val masks = calls("POST /v1/people:batchUpdateContacts").map { it.second!!.getValue("updateMask").jsonPrimitive.content }.sorted()
        assertEquals(listOf("biographies", "organizations"), masks)
    }

    @Test
    fun missingGroupIsCreatedOnce() = runTest {
        routes["POST /v1/people:batchUpdateContacts"] = echoUpdate()
        routes["POST /v1/contactGroups"] = { b ->
            assertEquals("Friends", b!!.getValue("contactGroup").jsonObject.getValue("name").jsonPrimitive.content)
            json("""{"resourceName":"contactGroups/f1","name":"Friends","groupType":"USER_CONTACT_GROUP"}""")
        }
        val list = source.contacts().first()
        val res = source.update(list.map { it.copy(groups = it.groups + "Friends") })
        assertEquals(3, res.written.size)
        assertEquals(1, calls("POST /v1/contactGroups").size)
        val sent = calls("POST /v1/people:batchUpdateContacts").single().second!!
        assertEquals("memberships", sent.getValue("updateMask").jsonPrimitive.content)
        val ms = sent.getValue("contacts").jsonObject.getValue("people/c1").jsonObject.getValue("memberships").jsonArray
            .map { it.jsonObject.getValue("contactGroupMembership").jsonObject.getValue("contactGroupResourceName").jsonPrimitive.content }
        assertEquals(listOf("contactGroups/myContacts", "contactGroups/f1"), ms)
        assertTrue("Friends" in source.groups())
    }

    @Test
    fun groupCreationFailureFailsOnlyThoseContacts() = runTest {
        routes["POST /v1/people:batchUpdateContacts"] = echoUpdate()
        routes["POST /v1/contactGroups"] = { json("""{"error":{"code":400,"message":"bad name"}}""", 400) }
        val list = source.contacts().first().sortedBy { it.id }
        val res = source.update(listOf(list[0].copy(groups = setOf("Bad")), list[1].copy(notes = "n")))
        assertEquals(listOf("people/c2"), res.written)
        assertTrue(res.failed.getValue("people/c1").contains("Bad"))
    }

    @Test
    fun photoUploadedOnlyWhenChanged() = runTest {
        routes["POST /v1/people:batchUpdateContacts"] = echoUpdate()
        routes["PATCH /v1/people/c1:updateContactPhoto"] = { json("""{"person":{"resourceName":"people/c1","etag":"p1"}}""") }
        val list = source.contacts().first().sortedBy { it.id }
        // photo null = unchanged: no photo call
        source.update(listOf(list[0].copy(notes = "a")))
        assertTrue(calls("PATCH /v1/people/c1:updateContactPhoto").isEmpty())
        val fresh = source.contacts().first().first { it.id == "people/c1" }
        val res = source.update(listOf(fresh.copy(photo = byteArrayOf(9, 8, 7))))
        assertEquals(listOf("people/c1"), res.written)
        assertTrue(calls("POST /v1/people:batchUpdateContacts").size == 1) // photo-only change: no batchUpdate
        assertEquals(1, calls("PATCH /v1/people/c1:updateContactPhoto").size)
        assertEquals("p1", source.contacts().first().first { it.id == "people/c1" }.etag)
    }

    @Test
    fun failedPhotoFetchMakesLoadThrow() = runTest {
        routes["GET /v1/people/me/connections"] = {
            json("""{"connections":[${personJson(1, extra = ""","photos":[{"url":"${server.url("/img/p1.jpg")}"}]""")}]}""")
        }
        routes["GET /img/p1.jpg=s0"] = { MockResponse.Builder().code(500).build() }
        source.refresh()
        val thrown = try { source.load("people/c1"); null } catch (e: com.ilukezippo.vcfdesk.data.PhotoUnavailableException) { e }
        assertTrue(thrown != null)
    }

    @Test
    fun loadedPhotoPassedBackUnchangedIsNotReuploaded() = runTest {
        val photoBytes = byteArrayOf(1, 2, 3, 4)
        routes["GET /v1/people/me/connections"] = {
            json("""{"connections":[${personJson(1, extra = ""","photos":[{"url":"${server.url("/img/p1.jpg")}"}]""")}]}""")
        }
        routes["GET /img/p1.jpg=s0"] = { MockResponse.Builder().body(okio.Buffer().write(photoBytes)).build() }
        source.refresh()
        val loaded = source.load("people/c1")
        assertArrayEquals(photoBytes, loaded.photo)
        val res = source.update(listOf(loaded))
        assertEquals(listOf("people/c1"), res.written)
        assertTrue(calls("PATCH /v1/people/c1:updateContactPhoto").isEmpty())
    }

    @Test
    fun serverErrorFailsWholeChunkWithReadableReason() = runTest {
        routes["POST /v1/people:batchUpdateContacts"] = { json("""{"error":{"code":500,"message":"Internal error"}}""", 500) }
        val list = source.contacts().first()
        val res = source.update(list.map { it.copy(notes = "x") })
        assertTrue(res.written.isEmpty())
        assertEquals(3, res.failed.size)
        assertTrue(res.failed.values.all { it.contains("Google") })
    }

    @Test
    fun createsContactsAndUploadsPhotos() = runTest {
        routes["POST /v1/people:batchCreateContacts"] = { b ->
            val n = b!!.getValue("contacts").jsonArray.size
            json("""{"createdPeople":[${(0 until n).joinToString(",") { """{"httpStatusCode":200,"person":{"resourceName":"people/n$it","etag":"x"}}""" }}]}""")
        }
        routes["PATCH /v1/people/n1:updateContactPhoto"] = { json("{}") }
        val res = source.create(
            listOf(
                Contact(name = Name(first = "Name 4"), phones = listOf(Typed("Mobile", "55555555"))),
                Contact(id = "dev1", name = Name(first = "Name 5"), photo = byteArrayOf(5)),
            ),
            null,
        )
        assertEquals(listOf("people/n0", "people/n1"), res.written)
        assertTrue(res.failed.isEmpty())
        val sent = calls("POST /v1/people:batchCreateContacts").single().second!!.getValue("contacts").jsonArray
        assertEquals("Name 4", sent[0].jsonObject.getValue("contactPerson").jsonObject.getValue("names").jsonArray[0].jsonObject.getValue("givenName").jsonPrimitive.content)
        assertEquals(1, calls("PATCH /v1/people/n1:updateContactPhoto").size)
    }

    @Test
    fun recreatingAPersonOfThisAccountKeepsWhatTheModelLacks() = runTest {
        routes["POST /v1/people:batchCreateContacts"] = { b ->
            val n = b!!.getValue("contacts").jsonArray.size
            json("""{"createdPeople":[${(0 until n).joinToString(",") { """{"httpStatusCode":200,"person":{"resourceName":"people/n$it","etag":"x"}}""" }}]}""")
        }
        routes["GET /v1/people/me/connections"] = {
            json(
                """{"connections":[${personJson(
                    1,
                    extra = ""","occupations":[{"value":"Pilot"}],"metadata":{"sources":[{"type":"CONTACT","id":"1"}]},""" +
                        """"photos":[{"url":"https://x/p","default":true}],""" +
                        """"emailAddresses":[{"value":"n1@example.com","metadata":{"primary":true,"source":{"type":"CONTACT","id":"1"}}},""" +
                        """{"value":"profile@example.com","metadata":{"source":{"type":"PROFILE","id":"2"}}}]""",
                ).replace(
                    """"memberships":[""",
                    """"memberships":[{"contactGroupMembership":{"contactGroupResourceName":"contactGroups/starred"}},""" +
                        """{"contactGroupMembership":{"contactGroupResourceName":"contactGroups/gone"}},""" +
                        """{"contactGroupMembership":{"contactGroupResourceName":"contactGroups/abc"}},""",
                )}]}""",
            )
        }
        val c = source.load("people/c1")
        assertEquals(setOf("Family"), c.groups)
        // undo of a delete: the backed-up contact, with its person JSON, back into the same account
        source.create(listOf(c.copy(etag = null)), source.account)
        // a copy from elsewhere (no account of this source) starts from nothing
        source.create(listOf(c.copy(etag = null, account = null)), null)
        val sent = calls("POST /v1/people:batchCreateContacts").map {
            it.second!!.getValue("contacts").jsonArray.single().jsonObject.getValue("contactPerson").jsonObject
        }
        val p = sent[0]
        assertTrue("resourceName" !in p && "etag" !in p && "metadata" !in p && "photos" !in p)
        assertEquals("v1", p.getValue("userDefined").jsonArray[0].jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals("Pilot", p.getValue("occupations").jsonArray[0].jsonObject.getValue("value").jsonPrimitive.content)
        val groups = p.getValue("memberships").jsonArray.map {
            it.jsonObject.getValue("contactGroupMembership").jsonObject.getValue("contactGroupResourceName").jsonPrimitive.content
        }
        assertEquals(setOf("contactGroups/starred", "contactGroups/myContacts", "contactGroups/abc"), groups.toSet())
        val emails = p.getValue("emailAddresses").jsonArray.map { it.jsonObject }
        assertEquals(listOf("n1@example.com"), emails.map { it.getValue("value").jsonPrimitive.content })
        assertEquals("""{"primary":true}""", emails[0].getValue("metadata").toString())
        assertEquals("Engineer", p.getValue("organizations").jsonArray[0].jsonObject.getValue("title").jsonPrimitive.content)

        val q = sent[1]
        assertTrue("userDefined" !in q && "occupations" !in q)
        assertEquals("5555555" + "1", q.getValue("phoneNumbers").jsonArray[0].jsonObject.getValue("value").jsonPrimitive.content)
    }

    @Test
    fun deleteRetriesSinglyAndTreatsNotFoundAsDone() = runTest {
        var first = true
        routes["POST /v1/people:batchDeleteContacts"] = { b ->
            val names = b!!.getValue("resourceNames").jsonArray.map { it.jsonPrimitive.content }
            when {
                first -> { first = false; json("""{"error":{"code":400,"message":"bad"}}""", 400) }
                names == listOf("people/c2") -> json("""{"error":{"code":404,"message":"gone"}}""", 404)
                names == listOf("people/c3") -> json("""{"error":{"code":403,"message":"nope"}}""", 403)
                else -> json("{}")
            }
        }
        val res = source.delete(listOf("people/c1", "people/c2", "people/c3"))
        assertEquals(listOf("people/c1", "people/c2"), res.written)
        assertEquals(setOf("people/c3"), res.failed.keys)
    }

    @Test
    fun loadFetchesFullSizePhotoFromContactSource() = runTest {
        val big = byteArrayOf(7, 7, 7)
        routes["GET /v1/people/me/connections"] = {
            val p1 = personJson(
                1,
                extra = ""","photos":[{"metadata":{"source":{"type":"PROFILE"}},"url":"${server.url("/img/profile=s100")}"},
                                       {"metadata":{"source":{"type":"CONTACT"}},"url":"${server.url("/img/c1=s100")}"}]""",
            )
            val p2 = personJson(2, extra = ""","photos":[{"url":"${server.url("/img/c2")}"}]""")
            val p3 = personJson(3, extra = ""","photos":[{"url":"${server.url("/img/c3=s96-c")}"}]""")
            json("""{"connections":[$p1,$p2,$p3]}""")
        }
        for (path in listOf("/img/c1=s0", "/img/c2=s0", "/img/c3=s0")) {
            routes["GET $path"] = { MockResponse.Builder().body(okio.Buffer().write(big)).build() }
        }
        source.refresh()
        assertArrayEquals(big, source.load("people/c1").photo)
        assertArrayEquals(big, source.load("people/c2").photo)
        assertArrayEquals(big, source.load("people/c3").photo)
        val fetched = requests.map { it.first.url.encodedPath }.filter { it.startsWith("/img/") }
        assertEquals(listOf("/img/c1=s0", "/img/c2=s0", "/img/c3=s0"), fetched)
        assertTrue(calls("GET /v1/people/me/connections").all { it.first.url.queryParameter("sources") == "READ_SOURCE_TYPE_CONTACT" })
    }

    @Test
    fun chunkLevelEtagFailureFailsOnlyTheStaleContact() = runTest {
        val stale = "Request person.etag is different than the current person.etag."
        routes["POST /v1/people:batchUpdateContacts"] = {
            json("""{"error":{"code":400,"message":"$stale","status":"FAILED_PRECONDITION"}}""", 400)
        }
        for (n in 1..3) {
            routes["PATCH /v1/people/c$n:updateContact"] = { b ->
                if (n == 2) json("""{"error":{"code":400,"message":"$stale","status":"FAILED_PRECONDITION"}}""", 400)
                else json(JsonObject(b!! + ("etag" to kotlinx.serialization.json.JsonPrimitive("fresh$n"))).toString())
            }
        }
        val list = source.contacts().first()
        val res = source.update(list.map { it.copy(org = it.org.copy(title = "Manager")) })
        assertEquals(listOf("people/c1", "people/c3"), res.written.sorted())
        assertEquals(setOf("people/c2"), res.failed.keys)
        assertTrue(res.failed.getValue("people/c2").contains("reload", ignoreCase = true))
        assertEquals("fresh1", source.contacts().first().first { it.id == "people/c1" }.etag)
    }
}
