package com.ilukezippo.vcfdesk.data

import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [ContactSource] that behaves like the real ones where it matters:
 * every write bumps the etag, `update` refuses stale etags, read-only accounts are skipped,
 * `create` reports new ids in `written` and keys failures by input id (or "#index").
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

    fun seed(c: Contact): Contact {
        val id = (nextId++).toString()
        val stored = c.copy(id = id, etag = (version++).toString(), thumbnail = c.photo?.let { it.copyOf(minOf(16, it.size)) })
        store[id] = stored
        publish()
        return stored
    }

    fun all(): List<Contact> = store.values.toList()
    fun get(id: String): Contact? = store[id]

    private fun publish() { flow.value = store.values.map { it.copy(photo = null) } }

    override fun contacts(): Flow<List<Contact>> = flow

    override suspend fun load(id: String): Contact {
        log += "load:$id"
        failLoad[id]?.let { throw java.io.IOException(it) }
        return store[id] ?: throw NoSuchElementException("no contact $id")
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
                    store[c.id] = c.copy(account = cur.account, etag = (version++).toString())
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
                    store[id] = c.copy(id = id, account = target, etag = (version++).toString())
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
