package com.ilukezippo.vcfdesk.data.google

import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.data.ContactSource
import com.ilukezippo.vcfdesk.data.PhotoUnavailableException
import com.ilukezippo.vcfdesk.data.SourceId
import com.ilukezippo.vcfdesk.data.WriteResult
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * One Google account's contacts through the People API. Ids are person resource names
 * ("people/c123"); etag is the person's etag, sent with every update so a contact changed in
 * Google since it was read is refused (reported in `failed`) instead of overwritten.
 * Updates send only the changed fields (updateMask), starting from the person as read, so
 * everything the model doesn't cover is kept. Callers should reload after writing.
 */
class GoogleSource(private val email: String, private val api: PeopleApi) : ContactSource {
    override val id: SourceId = SourceId.Google(email)
    val account = AccountTag(GoogleAuth.ACCOUNT_TYPE, email, "Google", readOnly = false)

    private val lock = Mutex()
    private val state = MutableStateFlow<List<Contact>?>(null)

    // guarded by lock
    private val byId = LinkedHashMap<String, Contact>()
    /** User contact groups: resource name → name. */
    private var groupNames: Map<String, String> = emptyMap()
    private var groupsLoaded = false
    /** Photo bytes this source fetched or uploaded, with the photo URL they belong to. */
    private val photos = HashMap<String, Pair<String?, ByteArray>>()

    // ---- reading ----

    override fun contacts(): Flow<List<Contact>> = flow {
        if (state.value == null) refresh()
        emitAll(state.filterNotNull())
    }

    override suspend fun refresh() = lock.withLock { reload() }

    private suspend fun reload() {
        loadGroups()
        val people = ArrayList<JsonObject>()
        var page: String? = null
        do {
            val (got, next) = api.listConnections(page)
            people += got
            page = next
        } while (page != null)
        byId.clear()
        for (p in people) {
            val c = contactOf(p)
            if (c.id.isNotEmpty()) byId[c.id] = c
        }
        photos.keys.retainAll(byId.keys)
        // a photo that changed in Google since it was fetched is fetched again
        photos.entries.removeAll { (id, v) -> photoUrl(byId.getValue(id)) != v.first }
        publish(force = true)
    }

    private suspend fun loadGroups() {
        groupNames = api.contactGroups()
            .filter { it.str("groupType") == "USER_CONTACT_GROUP" }
            .mapNotNull { g -> g.str("resourceName")?.let { rn -> (g.str("name") ?: g.str("formattedName"))?.let { rn to it } } }
            .toMap()
        groupsLoaded = true
    }

    /** Publishes the cache; before the first load there is nothing to publish (contacts() loads). */
    private fun publish(force: Boolean = false) {
        if (force || state.value != null) state.value = byId.values.toList()
    }

    private fun contactOf(p: JsonObject) = PeopleMapper.toContact(p, groupNames).copy(account = account)

    private suspend fun ensureLoaded() {
        if (state.value == null) reload() else if (!groupsLoaded) loadGroups()
    }

    override suspend fun load(id: String): Contact = lock.withLock {
        ensureLoaded()
        val c = byId[id] ?: throw NoSuchElementException("no Google contact $id")
        val url = photoUrl(c) ?: return@withLock c
        val bytes = photos[id]?.takeIf { it.first == url }?.second ?: try {
            api.fetchPhoto(fullSize(url)).also { photos[id] = url to it }
        } catch (e: IOException) {
            // never hand out a contact without the photo it has: a backup or a move would lose it
            throw PhotoUnavailableException(id, e)
        }
        c.copy(photo = bytes, thumbnail = bytes)
    }

    override suspend fun groups(): List<String> = lock.withLock {
        if (!groupsLoaded) loadGroups()
        groupNames.values.filter { it.isNotBlank() }.distinct().sorted()
    }

    /** The contact's own (non-placeholder) photo URL, from the person as read. */
    private fun photoUrl(c: Contact): String? = PeopleMapper.original(c)?.let { p ->
        (p["photos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { PeopleMapper.isContactSourced(it) && (it["default"] as? JsonPrimitive)?.booleanOrNull != true }
            ?.str("url")
    }

    // ---- writing ----

    private class Results(private val onProgress: (Int) -> Unit) {
        val written = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        private var done = 0
        fun ok(id: String) { written += id; tick() }
        fun skip(id: String) { skipped += id; tick() }
        fun fail(id: String, why: String) { failed[id] = why; tick() }
        private fun tick() { done++; onProgress(done) }
        fun result() = WriteResult(written, skipped, failed)
    }

    private fun reason(e: Throwable) = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    private fun groupIds(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for ((rn, name) in groupNames) m.putIfAbsent(name, rn)
        return m
    }

    /** Creates each missing group once; returns the names that could not be created, with why. */
    private suspend fun ensureGroups(names: Set<String>): Map<String, String> {
        val have = groupIds()
        val errors = HashMap<String, String>()
        for (n in names) if (n !in have) {
            try {
                val g = api.createGroup(n)
                val rn = g.str("resourceName") ?: throw PeopleApiException("Google returned no id for the new group", 0)
                groupNames = groupNames + (rn to (g.str("name") ?: n))
            } catch (e: IOException) {
                errors[n] = "group \"$n\" could not be created in Google: ${reason(e)}"
            }
        }
        return errors
    }

    /** Merges an updateContactPhoto response into the cached contact (new etag and photos). */
    private fun afterPhoto(id: String, response: JsonObject, bytes: ByteArray) {
        val c = byId[id] ?: return
        val orig = PeopleMapper.original(c) ?: return
        val p = response["person"] as? JsonObject
        val merged = LinkedHashMap<String, JsonElement>(orig)
        if (p != null) for (k in listOf("etag", "photos", "metadata")) p[k]?.let { merged[k] = it }
        val updated = contactOf(JsonObject(merged))
        byId[id] = updated
        photos[id] = photoUrl(updated) to bytes
    }

    override suspend fun update(cs: List<Contact>, onProgress: (Int) -> Unit): WriteResult = lock.withLock {
        val res = Results(onProgress)
        ensureLoaded()
        val seen = HashSet<String>()
        class Job(val c: Contact, val before: Contact, val mask: String, val photo: ByteArray?)
        val jobs = ArrayList<Job>()
        for (c in cs) {
            if (!seen.add(c.id)) { res.fail(c.id, "listed more than once"); continue }
            if (c.account?.readOnly == true) { res.skip(c.id); continue }
            // diff against the person this copy was made from (its etag guards the rest)
            val cached = byId[c.id]
            val base = PeopleMapper.original(c)?.let { contactOf(it) } ?: cached
            if (base == null) { res.fail(c.id, "not found in this Google account; reload and try again"); continue }
            val src = if (PeopleMapper.original(c) != null) c else c.copy(extras = c.extras + base.extras.filter { it.kind == PeopleMapper.EXTRA_KIND })
            val known = photos[c.id]?.second
            val photo = c.photo?.takeIf { known == null || !it.contentEquals(known) }
            jobs += Job(src.copy(etag = c.etag ?: base.etag), base, PeopleMapper.updateMask(base, src), photo)
        }
        val groupErrors = ensureGroups(jobs.filter { it.mask.isNotEmpty() }.flatMapTo(LinkedHashSet()) { it.c.groups - it.before.groups })
        val ids = groupIds()

        // field updates, one batch per distinct mask
        val pending = LinkedHashMap<String, Pair<Job, JsonObject>>()
        val photoNext = ArrayList<Job>()
        for (j in jobs) {
            val gErr = j.c.groups.firstNotNullOfOrNull { groupErrors[it] }
            when {
                gErr != null -> res.fail(j.c.id, gErr)
                j.mask.isEmpty() -> if (j.photo != null) photoNext += j else res.ok(j.c.id)
                else -> try {
                    pending[j.c.id] = j to PeopleMapper.toPerson(j.c, ids)
                } catch (e: IllegalArgumentException) {
                    res.fail(j.c.id, reason(e))
                }
            }
        }
        for ((mask, group) in pending.values.groupBy { it.first.mask }) {
            val out = api.batchUpdate(group.associate { it.first.c.id to it.second }, mask)
            for ((j, _) in group) {
                val r = out[j.c.id] ?: Result.failure(PeopleApiException("Google returned no result for this contact", 0))
                r.fold(
                    onSuccess = { person ->
                        // the response holds only readMask fields: keep the rest from what was sent
                        val merged = LinkedHashMap<String, JsonElement>(pending.getValue(j.c.id).second)
                        merged.putAll(person)
                        byId[j.c.id] = contactOf(JsonObject(merged))
                        if (j.photo != null) photoNext += j else res.ok(j.c.id)
                    },
                    onFailure = { e -> res.fail(j.c.id, reason(e)) },
                )
            }
        }
        // photos after the fields, so a stale etag stops both
        for (j in photoNext) {
            try {
                afterPhoto(j.c.id, api.updatePhoto(j.c.id, j.photo!!), j.photo)
                res.ok(j.c.id)
            } catch (e: IOException) {
                val saved = if (j.mask.isNotEmpty()) "details saved, but " else ""
                res.fail(j.c.id, "${saved}the photo could not be uploaded: ${reason(e)}")
            }
        }
        publish()
        res.result()
    }

    /**
     * Creates the contacts in this account. `written` holds the new resource names; `failed` is
     * keyed by the input id (or "#index"). A contact whose photo upload fails after it was created
     * is in both: written (it exists) and failed (with that reason).
     */
    override suspend fun create(cs: List<Contact>, target: AccountTag?, onProgress: (Int) -> Unit): WriteResult =
        lock.withLock {
            val res = Results(onProgress)
            fun key(i: Int) = cs[i].id.ifEmpty { "#$i" }
            if (target?.readOnly == true) {
                cs.indices.forEach { res.skip(key(it)) }
                return@withLock res.result()
            }
            if (target != null && (target.type != account.type || !target.name.equals(email, ignoreCase = true))) {
                cs.indices.forEach { res.fail(key(it), "this Google source writes only to $email") }
                return@withLock res.result()
            }
            ensureLoaded()
            val groupErrors = ensureGroups(cs.flatMapTo(LinkedHashSet()) { it.groups })
            val ids = groupIds()
            val toSend = ArrayList<Pair<Int, JsonObject>>()
            cs.forEachIndexed { i, c ->
                val gErr = c.groups.firstNotNullOfOrNull { groupErrors[it] }
                if (gErr != null) { res.fail(key(i), gErr); return@forEachIndexed }
                // a new person: nothing from another source's ids, etag or person JSON is carried over,
                // except when re-creating a person of this very account (undo): then everything the
                // model doesn't cover (userDefined, starred, occupations…) is re-created from it
                val seed = if (c.account?.type == account.type && c.account?.name.equals(email, ignoreCase = true)) {
                    PeopleMapper.original(c)?.let(PeopleMapper::seedFor)
                } else null
                val fresh = c.copy(id = "", etag = null, extras = c.extras.filter { it.kind != PeopleMapper.EXTRA_KIND })
                try {
                    toSend += i to PeopleMapper.toPerson(fresh, ids, seed)
                } catch (e: IllegalArgumentException) {
                    res.fail(key(i), reason(e))
                }
            }
            val out = api.batchCreate(toSend.map { it.second })
            toSend.forEachIndexed { n, (i, _) ->
                out[n].fold(
                    onSuccess = { person ->
                        val created = contactOf(person)
                        if (created.id.isEmpty()) { res.fail(key(i), "created, but Google returned no id"); return@fold }
                        byId[created.id] = created
                        val photo = cs[i].photo ?: cs[i].thumbnail
                        if (photo == null) { res.ok(created.id); return@fold }
                        try {
                            afterPhoto(created.id, api.updatePhoto(created.id, photo), photo)
                            res.ok(created.id)
                        } catch (e: IOException) {
                            res.written += created.id
                            res.fail(key(i), "created as ${created.id}, but the photo could not be uploaded: ${reason(e)}")
                        }
                    },
                    onFailure = { e ->
                        val maybe = (e as? PeopleApiException)?.let { it.httpCode == 0 || it.httpCode >= 500 } == true
                        res.fail(key(i), reason(e) + if (maybe) " (the contact may have been created anyway; check before retrying)" else "")
                    },
                )
            }
            publish()
            res.result()
        }

    override suspend fun delete(ids: List<String>, onProgress: (Int) -> Unit): WriteResult = lock.withLock {
        val res = Results(onProgress)
        for (chunk in ids.distinct().chunked(PeopleApi.MAX_DELETE)) {
            try {
                api.batchDelete(chunk)
                chunk.forEach { byId.remove(it); res.ok(it) }
            } catch (e: PeopleApiException) {
                if (e.httpCode == 401) { chunk.forEach { res.fail(it, reason(e)) }; continue }
                // one bad id fails the whole call: deleting is idempotent, so retry one by one
                for (id in chunk) try {
                    api.batchDelete(listOf(id))
                    byId.remove(id); res.ok(id)
                } catch (e2: PeopleApiException) {
                    if (e2.notFound) { byId.remove(id); res.ok(id) } else res.fail(id, reason(e2))
                }
            }
        }
        publish()
        res.result()
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        private val SIZE_SUFFIX = Regex("=[swh]\\d+(-[A-Za-z0-9-]*)?$")

        /**
         * The full-size variant of a Google photo URL: `photos[].url` carries a size suffix
         * (e.g. `=s100`) giving a ~100 px image, which must never become a contact's full photo.
         */
        fun fullSize(url: String): String =
            if (SIZE_SUFFIX.containsMatchIn(url)) url.replace(SIZE_SUFFIX, "=s0") else "$url=s0"
    }
}
