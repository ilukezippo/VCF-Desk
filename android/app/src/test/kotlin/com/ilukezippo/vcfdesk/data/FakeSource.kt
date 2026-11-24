package com.ilukezippo.vcfdesk.data

import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.text.Names
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [ContactSource] that behaves like the real ones where it matters:
 * - every write bumps the etag; `update` refuses stale etags; read-only accounts are skipped;
 * - writes are diff-based, and the parts that are written get normalised the way a provider
 *   does it (a changed name gets a computed display name; changed phones/emails get default
 *   labels for blank ones), while unchanged parts are left exactly as they were;
 * - photo null on update means "unchanged"; `contacts()` carries thumbnails only, `load` the
 *   full photo, and throws [PhotoUnavailableException] for ids in [photoFails];
 * - `create` reports new ids in `written` and keys failures by input id (or "#index").
 * It records the order of calls in [log], and [beforeWrite] runs before any write.
 */
class FakeSource(override val id: SourceId = SourceId.Device) : ContactSource {
    private val store = LinkedHashMap<String, Contact>()
    private var nextId = 1
    private var version = 1
    private val flow = MutableStateFlow<List<Contact>>(emptyList())

    val log = ArrayList<String>()
    var beforeWrite: (String) -> Unit = {}

    /** Ids (current or input) whose next write fails, with the reason. */
    val failUpdate = HashMap<String, String>()
    val failCreate = HashMap<String, String>()
    val failDelete = HashMap<String, String>()
    /** Ids whose load throws something other than "not found" (e.g. a network error). */
    val failLoad = HashMap<String, String>()
    /** Ids whose full photo can't be read. */
    val photoFails = HashSet<String>()

    /** Stores [c] as it is (an existing contact, not normalised). */
    fun seed(c: Contact): Contact {
        val id = (nextId++).toString()
        val stored = c.copy(id = id, etag = (version++).toString(), thumbnail = thumb(c.photo))
        store[id] = stored
        publish()
        return stored
    }

    fun all(): List<Contact> = store.values.toList()
    fun get(id: String): Contact? = store[id]

    private fun thumb(p: ByteArray?) = p?.let { it.copyOf(minOf(16, it.size)) }

    private fun publish() { flow.value = store.values.map { it.copy(photo = null) } }

    /** What a provider makes of [new] written over [old] (null for an insert). */
    private fun normalise(old: Contact?, new: Contact): Contact {
        var c = new
        if (old == null || c.name != old.name) {
            c = c.copy(name = c.name.copy(display = c.name.display.ifBlank { Names.compose(c.name) }))
        }
        if (old == null || c.phones != old.phones) c = c.copy(phones = c.phones.map { if (it.label.isBlank()) it.copy(label = "Mobile") else it })
        if (old == null || c.emails != old.emails) c = c.copy(emails = c.emails.map { if (it.label.isBlank()) it.copy(label = "Other") else it })
        val photo = c.photo ?: old?.photo
        return c.copy(photo = photo, thumbnail = thumb(photo))
    }

    override fun contacts(): Flow<List<Contact>> = flow

    override suspend fun load(id: String): Contact {
        log += "load:$id"
        failLoad[id]?.let { throw java.io.IOException(it) }
        val c = store[id] ?: throw NoSuchElementException("no contact $id")
        if (id in photoFails && c.photo != null) throw PhotoUnavailableException(id)
        return c
    }

    override suspend fun groups(): List<String> = store.values.flatMap { it.groups }.distinct().sorted()

    override suspend fun update(cs: List<Contact>, onProgress: (Int) -> Unit): WriteResult {
        beforeWrite("update")
        log += "update:" + cs.joinToString(",") { it.id }
        val written = ArrayList<String>(); val skipped = ArrayList<String>(); val failed = LinkedHashMap<String, String>()
        var done = 0
        for (c in cs) {
            val cur = store[c.id]
            when {
                cur == null -> failed[c.id] = "not found"
                cur.account?.readOnly == true -> skipped += c.id
                c.etag != cur.etag -> failed[c.id] = "stale"
                failUpdate.containsKey(c.id) -> failed[c.id] = failUpdate.getValue(c.id)
                else -> {
                    store[c.id] = normalise(cur, c).copy(account = cur.account, etag = (version++).toString())
                    written += c.id
                }
            }
            onProgress(++done)
        }
        publish()
        return WriteResult(written, skipped, failed)
    }

    override suspend fun create(cs: List<Contact>, target: AccountTag?, onProgress: (Int) -> Unit): WriteResult {
        beforeWrite("create")
        log += "create:" + cs.joinToString(",") { it.id }
        val written = ArrayList<String>(); val skipped = ArrayList<String>(); val failed = LinkedHashMap<String, String>()
        var done = 0
        cs.forEachIndexed { i, c ->
            val key = c.id.ifEmpty { "#$i" }
            when {
                target?.readOnly == true -> skipped += key
                failCreate.containsKey(key) -> failed[key] = failCreate.getValue(key)
                else -> {
                    val id = (nextId++).toString()
                    store[id] = normalise(null, c).copy(id = id, account = target, etag = (version++).toString())
                    written += id
                }
            }
            onProgress(++done)
        }
        publish()
        return WriteResult(written, skipped, failed)
    }

    override suspend fun delete(ids: List<String>, onProgress: (Int) -> Unit): WriteResult {
        beforeWrite("delete")
        log += "delete:" + ids.joinToString(",")
        val written = ArrayList<String>(); val skipped = ArrayList<String>(); val failed = LinkedHashMap<String, String>()
        var done = 0
        for (id in ids) {
            val cur = store[id]
            when {
                cur == null -> failed[id] = "not found"
                cur.account?.readOnly == true -> skipped += id
                failDelete.containsKey(id) -> failed[id] = failDelete.getValue(id)
                else -> { store.remove(id); written += id }
            }
            onProgress(++done)
        }
        publish()
        return WriteResult(written, skipped, failed)
    }

    override suspend fun refresh() {}
}
