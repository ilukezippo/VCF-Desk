package com.ilukezippo.vcfdesk.data.google

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PeopleApiTest {
    private val server = MockWebServer()
    private lateinit var api: PeopleApi

    @Before
    fun setUp() {
        server.start()
        api = PeopleApi(OkHttpClient(), { "tok-1" }, server.url("/v1/").toString(), retryDelayMs = { 0L })
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").body(body).build()

    private fun person(n: Int) = JsonObject(mapOf("resourceName" to JsonPrimitive("people/c$n"), "etag" to JsonPrimitive("e$n")))

    @Test
    fun pagesThroughConnections() = runTest {
        server.enqueue(json("""{"connections":[{"resourceName":"people/c1"}],"nextPageToken":"p2"}"""))
        server.enqueue(json("""{"connections":[{"resourceName":"people/c2"}],"nextPageToken":"p3"}"""))
        server.enqueue(json("""{"connections":[{"resourceName":"people/c3"}]}"""))
        val all = ArrayList<JsonObject>()
        var token: String? = null
        var pages = 0
        do {
            val (people, next) = api.listConnections(token)
            all += people; token = next; pages++
        } while (token != null)
        assertEquals(3, pages)
        assertEquals(listOf("people/c1", "people/c2", "people/c3"), all.map { it.getValue("resourceName").jsonPrimitive.content })
        val first = server.takeRequest()
        assertEquals("/v1/people/me/connections", first.url.encodedPath)
        assertEquals("1000", first.url.queryParameter("pageSize"))
        assertEquals(PERSON_FIELDS, first.url.queryParameter("personFields"))
        assertNull(first.url.queryParameter("pageToken"))
        assertEquals("p2", server.takeRequest().url.queryParameter("pageToken"))
        assertEquals("p3", server.takeRequest().url.queryParameter("pageToken"))
    }

    @Test
    fun batchUpdateSplitsAt200() = runTest {
        repeat(3) { chunk ->
            val n = if (chunk < 2) 200 else 50
            val entries = (0 until n).joinToString(",") { i ->
                val id = chunk * 200 + i
                """"people/c$id":{"httpStatusCode":200,"person":{"resourceName":"people/c$id","etag":"new$id"}}"""
            }
            server.enqueue(json("""{"updateResult":{$entries}}"""))
        }
        val people = (0 until 450).associate { "people/c$it" to person(it) }
        val res = api.batchUpdate(people, "organizations")
        assertEquals(3, server.requestCount)
        assertEquals(450, res.size)
        assertTrue(res.values.all { it.isSuccess })
        assertEquals("new449", res.getValue("people/c449").getOrThrow().getValue("etag").jsonPrimitive.content)
        val r1 = server.takeRequest()
        assertEquals("/v1/people:batchUpdateContacts", r1.url.encodedPath)
        val body = Json.parseToJsonElement(r1.body!!.utf8()).jsonObject
        assertEquals("organizations", body.getValue("updateMask").jsonPrimitive.content)
        assertEquals(200, body.getValue("contacts").jsonObject.size)
        assertTrue(body.containsKey("readMask"))
    }

    @Test
    fun batchUpdateReportsPerPersonStatus() = runTest {
        server.enqueue(
            json(
                """{"updateResult":{
                  "people/c1":{"httpStatusCode":200,"person":{"resourceName":"people/c1"}},
                  "people/c2":{"httpStatusCode":400,"status":{"code":9,"message":"Request person.etag is different than the current person.etag."}}
                }}""",
            ),
        )
        val res = api.batchUpdate(mapOf("people/c1" to person(1), "people/c2" to person(2)), "names")
        assertTrue(res.getValue("people/c1").isSuccess)
        val e = res.getValue("people/c2").exceptionOrNull() as PeopleApiException
        assertTrue(e.etagMismatch)
    }

    @Test
    fun sendsBearerToken() = runTest {
        server.enqueue(json("""{"contactGroups":[{"resourceName":"contactGroups/abc","name":"Family","groupType":"USER_CONTACT_GROUP"}]}"""))
        val groups = api.contactGroups()
        assertEquals(1, groups.size)
        assertEquals("Bearer tok-1", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun retriesRateLimitThenSucceeds() = runTest {
        server.enqueue(json("""{"error":{"code":429,"message":"slow down"}}""", 429))
        server.enqueue(json("""{"error":{"code":503,"message":"busy"}}""", 503))
        server.enqueue(json("""{"connections":[]}"""))
        val (people, next) = api.listConnections(null)
        assertTrue(people.isEmpty())
        assertNull(next)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun httpFailureFailsEveryContactOfTheChunk() = runTest {
        repeat(4) { server.enqueue(json("""{"error":{"code":429,"message":"Quota exceeded"}}""", 429)) }
        val res = api.batchUpdate(mapOf("people/c1" to person(1), "people/c2" to person(2)), "names")
        assertEquals(4, server.requestCount) // first try + 3 retries
        assertTrue(res.values.all { it.isFailure })
        assertTrue(res.getValue("people/c1").exceptionOrNull()!!.message!!.contains("rate limit", ignoreCase = true))
    }

    @Test
    fun unauthorizedIsNotRetried() = runTest {
        server.enqueue(json("""{"error":{"code":401,"message":"Invalid Credentials"}}""", 401))
        val res = api.batchCreate(listOf(person(1)))
        assertEquals(1, server.requestCount)
        assertTrue(res.single().exceptionOrNull()!!.message!!.contains("sign in", ignoreCase = true))
    }

    @Test
    fun batchCreateAndDeleteChunkSizes() = runTest {
        repeat(2) { c ->
            val n = if (c == 0) 200 else 1
            val items = (0 until n).joinToString(",") { """{"httpStatusCode":200,"person":{"resourceName":"people/n$c$it"}}""" }
            server.enqueue(json("""{"createdPeople":[$items]}"""))
        }
        val created = api.batchCreate((0 until 201).map { person(it) })
        assertEquals(201, created.size)
        assertEquals("people/n10", created[200].getOrThrow().getValue("resourceName").jsonPrimitive.content)
        val req = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(200, req.getValue("contacts").jsonArray.size)
        assertTrue(req.getValue("contacts").jsonArray[0].jsonObject.containsKey("contactPerson"))
        server.takeRequest()

        server.enqueue(json("{}")); server.enqueue(json("{}"))
        api.batchDelete((0 until 501).map { "people/c$it" })
        val d = server.takeRequest()
        assertEquals("/v1/people:batchDeleteContacts", d.url.encodedPath)
        assertEquals(500, Json.parseToJsonElement(d.body!!.utf8()).jsonObject.getValue("resourceNames").jsonArray.size)
    }

    @Test
    fun updatePhotoAndGroupCalls() = runTest {
        server.enqueue(json("{}"))
        api.updatePhoto("people/c1", byteArrayOf(1, 2, 3))
        val p = server.takeRequest()
        assertEquals("PATCH", p.method)
        assertEquals("/v1/people/c1:updateContactPhoto", p.url.encodedPath)
        assertEquals("AQID", Json.parseToJsonElement(p.body!!.utf8()).jsonObject.getValue("photoBytes").jsonPrimitive.content)

        server.enqueue(json("""{"resourceName":"contactGroups/new1","name":"Friends"}"""))
        val g = api.createGroup("Friends")
        assertEquals("contactGroups/new1", g.getValue("resourceName").jsonPrimitive.content)
        assertEquals("/v1/contactGroups", server.takeRequest().url.encodedPath)

        server.enqueue(json("{}"))
        api.modifyGroupMembers("contactGroups/new1", listOf("people/c1"), emptyList())
        val m = server.takeRequest()
        assertEquals("/v1/contactGroups/new1/members:modify", m.url.encodedPath)
        assertEquals("people/c1", Json.parseToJsonElement(m.body!!.utf8()).jsonObject.getValue("resourceNamesToAdd").jsonArray[0].jsonPrimitive.content)
    }
}
