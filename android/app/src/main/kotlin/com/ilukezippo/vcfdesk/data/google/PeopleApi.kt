package com.ilukezippo.vcfdesk.data.google

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

const val PERSON_FIELDS = "names,nicknames,phoneNumbers,emailAddresses,addresses,organizations,urls,birthdays,events,relations,imClients,biographies,memberships,photos,metadata"

/**
 * A People API failure with a readable [message]. [httpCode] is the HTTP status (0 for network
 * errors); [status] is the Google RPC status name or code when the server gave one.
 */
class PeopleApiException(message: String, val httpCode: Int, val status: String? = null, cause: Throwable? = null) :
    IOException(message, cause) {
    /** The person changed on Google since it was read (stale etag). */
    val etagMismatch: Boolean get() = status == "FAILED_PRECONDITION" || status == "9"
    val notFound: Boolean get() = httpCode == 404 || status == "NOT_FOUND" || status == "5"
}

/**
 * Thin client for the People API REST endpoints used by [GoogleSource]. Calls go through [http]
 * with a bearer token from [token]. 429/503 are retried with backoff up to [MAX_RETRIES] times.
 * Batch calls split their input at the API limits; an HTTP-level failure of one chunk is reported
 * as a failure of every person in that chunk, and the other chunks still run.
 */
class PeopleApi(
    private val http: OkHttpClient,
    private val token: suspend () -> String,
    base: String = "https://people.googleapis.com/v1/",
    /** Delay before retry number `n` (1-based). */
    private val retryDelayMs: (Int) -> Long = { n -> 1000L shl (n - 1) },
) {
    private val base: HttpUrl = base.toHttpUrl()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun listConnections(pageToken: String?): Pair<List<JsonObject>, String?> {
        val url = url("people/me/connections") {
            addQueryParameter("pageSize", "1000")
            addQueryParameter("personFields", PERSON_FIELDS)
            addQueryParameter("sortOrder", "FIRST_NAME_ASCENDING")
            if (pageToken != null) addQueryParameter("pageToken", pageToken)
        }
        val r = call("GET", url, null)
        return r.arr("connections").map { it.jsonObject } to r.str("nextPageToken")?.ifEmpty { null }
    }

    /** All contact groups (system and user), following pages. */
    suspend fun contactGroups(): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var page: String? = null
        do {
            val url = url("contactGroups") {
                addQueryParameter("pageSize", "1000")
                addQueryParameter("groupFields", "name,groupType,metadata")
                if (page != null) addQueryParameter("pageToken", page)
            }
            val r = call("GET", url, null)
            out += r.arr("contactGroups").map { it.jsonObject }
            page = r.str("nextPageToken")?.ifEmpty { null }
        } while (page != null)
        return out
    }

    /** Creates a user contact group; returns it (with its resourceName). */
    suspend fun createGroup(name: String): JsonObject =
        call("POST", url("contactGroups"), buildJsonObject { put("contactGroup", buildJsonObject { put("name", name) }) })

    /**
     * Updates [people] (resourceName → person, each carrying its etag) with one [updateMask],
     * in calls of at most [MAX_UPDATE]. Every input key gets a result.
     */
    suspend fun batchUpdate(people: Map<String, JsonObject>, updateMask: String): Map<String, Result<JsonObject>> {
        val out = LinkedHashMap<String, Result<JsonObject>>()
        for (chunk in people.entries.chunked(MAX_UPDATE)) {
            val body = buildJsonObject {
                put("contacts", JsonObject(chunk.associate { it.key to it.value }))
                put("updateMask", updateMask)
                put("readMask", PERSON_FIELDS)
            }
            val r = try {
                call("POST", url("people:batchUpdateContacts"), body)
            } catch (e: PeopleApiException) {
                chunk.forEach { out[it.key] = Result.failure(e) }
                continue
            }
            val results = r["updateResult"] as? JsonObject ?: JsonObject(emptyMap())
            for ((rn, _) in chunk) {
                val one = results[rn] as? JsonObject
                out[rn] = if (one == null) Result.failure(PeopleApiException("Google returned no result for this contact", 0))
                else personResult(one)
            }
        }
        return out
    }

    /** Creates [people] in calls of at most [MAX_CREATE]; results are in input order. */
    suspend fun batchCreate(people: List<JsonObject>): List<Result<JsonObject>> {
        val out = ArrayList<Result<JsonObject>>(people.size)
        for (chunk in people.chunked(MAX_CREATE)) {
            val body = buildJsonObject {
                put("contacts", JsonArray(chunk.map { JsonObject(mapOf("contactPerson" to it)) }))
                put("readMask", PERSON_FIELDS)
            }
            val r = try {
                call("POST", url("people:batchCreateContacts"), body)
            } catch (e: PeopleApiException) {
                chunk.forEach { _ -> out += Result.failure(e) }
                continue
            }
            val created = r.arr("createdPeople")
            chunk.indices.forEach { i ->
                val one = created.getOrNull(i) as? JsonObject
                out += if (one == null) Result.failure(PeopleApiException("Google returned no result for this contact", 0))
                else personResult(one)
            }
        }
        return out
    }

    /** Deletes in calls of at most [MAX_DELETE]; throws [PeopleApiException] on the first failed call. */
    suspend fun batchDelete(resourceNames: List<String>) {
        for (chunk in resourceNames.chunked(MAX_DELETE)) {
            call("POST", url("people:batchDeleteContacts"), buildJsonObject { put("resourceNames", JsonArray(chunk.map(::JsonPrimitive))) })
        }
    }

    /** Replaces the contact's photo; returns the response (its `person`, if any, has the new etag). */
    suspend fun updatePhoto(resourceName: String, jpeg: ByteArray): JsonObject = call(
        "PATCH", url("$resourceName:updateContactPhoto"),
        buildJsonObject {
            put("photoBytes", Base64.getEncoder().encodeToString(jpeg))
            put("personFields", "photos,metadata")
        },
    )

    suspend fun modifyGroupMembers(groupResource: String, add: List<String>, remove: List<String>) {
        val chunks = maxOf(add.chunked(MAX_MEMBERS).size, remove.chunked(MAX_MEMBERS).size)
        for (i in 0 until chunks) {
            val a = add.chunked(MAX_MEMBERS).getOrNull(i).orEmpty()
            val r = remove.chunked(MAX_MEMBERS).getOrNull(i).orEmpty()
            call(
                "POST", url("$groupResource/members:modify"),
                buildJsonObject {
                    put("resourceNamesToAdd", JsonArray(a.map(::JsonPrimitive)))
                    put("resourceNamesToRemove", JsonArray(r.map(::JsonPrimitive)))
                },
            )
        }
    }

    /** Downloads a photo URL from a person's `photos` (no credentials are sent: it is a public CDN URL). */
    suspend fun fetchPhoto(url: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        return http.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw PeopleApiException("photo download failed (HTTP ${resp.code})", resp.code)
            withContext(Dispatchers.IO) { resp.body.bytes() }
        }
    }

    // ---- plumbing ----

    private fun url(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl {
        val b = base.newBuilder()
        // resource names contain "/" and ":" — add them as path segments verbatim
        for (seg in path.split('/')) b.addEncodedPathSegment(seg)
        b.query()
        return b.build()
    }

    private fun personResult(one: JsonObject): Result<JsonObject> {
        val http = one["httpStatusCode"]?.jsonPrimitive?.intOrNull ?: 200
        val status = one["status"] as? JsonObject
        val code = status?.get("code")?.jsonPrimitive?.intOrNull ?: 0
        val person = one["person"] as? JsonObject
        return if (http in 200..299 && code == 0 && person != null) Result.success(person)
        else Result.failure(errorOf(if (http in 200..299) 400 else http, status?.get("message")?.jsonPrimitive?.contentOrNull, code.toString()))
    }

    private suspend fun call(method: String, url: HttpUrl, body: JsonObject?): JsonObject {
        var attempt = 0
        while (true) {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer ${token()}")
                .method(method, body?.toString()?.toRequestBody(jsonType))
                .build()
            val (code, text) = try {
                http.newCall(req).await().use { resp -> resp.code to withContext(Dispatchers.IO) { resp.body.string() } }
            } catch (e: IOException) {
                throw PeopleApiException("network error talking to Google: ${e.message ?: e.javaClass.simpleName}", 0, cause = e)
            }
            if (code in 200..299) {
                return if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
            }
            if ((code == 429 || code == 503) && attempt < MAX_RETRIES) {
                attempt++
                delay(retryDelayMs(attempt))
                continue
            }
            val err = runCatching { Json.parseToJsonElement(text).jsonObject["error"] as? JsonObject }.getOrNull()
            throw errorOf(code, err?.get("message")?.jsonPrimitive?.contentOrNull, err?.get("status")?.jsonPrimitive?.contentOrNull)
        }
    }

    private fun errorOf(code: Int, message: String?, status: String?): PeopleApiException {
        val detail = message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        val text = when {
            status == "FAILED_PRECONDITION" || status == "9" ->
                "changed in Google since it was read; reload and try again"
            code == 401 -> "Google sign-in expired or was revoked; sign in again"
            code == 403 -> "Google refused access$detail"
            code == 404 -> "not found in Google$detail"
            code == 429 -> "Google rate limit reached; try again later$detail"
            code >= 500 -> "Google server error (HTTP $code)$detail"
            else -> "Google rejected the request (HTTP $code)$detail"
        }
        return PeopleApiException(text, code, status)
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resumeWith(Result.success(response)) else response.close()
            }
        })
    }

    private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray).orEmpty()
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val MAX_UPDATE = 200
        const val MAX_CREATE = 200
        const val MAX_DELETE = 500
        const val MAX_MEMBERS = 1000
        const val MAX_RETRIES = 3
    }
}
