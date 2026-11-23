package com.ilukezippo.vcfdesk.data.device

import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.data.ContactSource
import com.ilukezippo.vcfdesk.data.SourceId
import com.ilukezippo.vcfdesk.data.WriteResult
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Resolves a raw contact's account to a tag (label + whether we may write to it). */
fun interface DeviceAccounts {
    fun tag(type: String?, name: String?): AccountTag
}

/**
 * Labels: Google, Samsung, "Phone" for local accounts, otherwise the authenticator's label.
 * Read-only when the account type's contacts sync adapter doesn't support uploading.
 */
class SystemDeviceAccounts(private val context: Context) : DeviceAccounts {
    private val cache = HashMap<String, Pair<String, Boolean>>()

    override fun tag(type: String?, name: String?): AccountTag {
        val t = type.orEmpty()
        if (t.isEmpty() || t in LOCAL_TYPES) return AccountTag(t, name.orEmpty(), "Phone", readOnly = false)
        val (label, ro) = synchronized(cache) { cache.getOrPut(t) { label(t) to readOnly(t) } }
        return AccountTag(t, name.orEmpty(), label, ro)
    }

    private fun readOnly(type: String): Boolean {
        val adapter = ContentResolver.getSyncAdapterTypes()
            .firstOrNull { it.accountType == type && it.authority == ContactsContract.AUTHORITY }
        return adapter != null && !adapter.supportsUploading()
    }

    private fun label(type: String): String = when (type) {
        "com.google" -> "Google"
        "com.osp.app.signin" -> "Samsung"
        else -> runCatching {
            val d = AccountManager.get(context).authenticatorTypes.first { it.type == type }
            context.packageManager.getResourcesForApplication(d.packageName).getString(d.labelId)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: type
    }

    companion object {
        /** Phone-local raw contact account types (null/empty is the AOSP local account). */
        val LOCAL_TYPES = setOf("vnd.sec.contact.phone", "com.android.localphone", "com.local.contacts")
    }
}

/**
 * The phone's own contacts, read and written as RawContacts through ContactsContract.
 * Ids are RawContact `_ID`s. Never touches the network.
 */
class DeviceSource(
    context: Context,
    private val accounts: DeviceAccounts = SystemDeviceAccounts(context),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ContactSource {
    private val resolver: ContentResolver = context.contentResolver
    override val id: SourceId = SourceId.Device
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private class RawInfo(val id: Long, val type: String?, val name: String?, val deleted: Boolean, val version: String?)
    private class GroupInfo(val id: Long, val title: String, val type: String?, val name: String?)

    // ---- reading ----

    override fun contacts(): Flow<List<Contact>> = callbackFlow {
        val signal = Channel<Unit>(Channel.CONFLATED)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                signal.trySend(Unit)
            }
        }
        resolver.registerContentObserver(RawContacts.CONTENT_URI, true, observer)
        launch { refreshes.collect { signal.trySend(Unit) } }
        signal.trySend(Unit)
        launch { for (s in signal) send(withContext(io) { queryAll() }) }
        awaitClose {
            resolver.unregisterContentObserver(observer)
            signal.close()
        }
    }.conflate()

    /** etag = RawContacts.VERSION, so update can refuse to overwrite a contact that changed since it was read. */
    private fun contactOf(r: RawInfo, rows: List<DataRow>, titles: Map<Long, String>) =
        DeviceMapper.toContact(r.id, accounts.tag(r.type, r.name), rows, titles).copy(etag = r.version)

    private fun queryAll(): List<Contact> {
        val raws = queryRaws("${RawContacts.DELETED}=0", emptyArray()).filter { !it.deleted }
        val titles = queryGroups().associate { it.id to it.title }
        val rows = queryData(null, emptyArray())
        return raws.map { r -> contactOf(r, rows[r.id].orEmpty(), titles) }
    }

    override suspend fun load(id: String): Contact = withContext(io) {
        val rawId = id.toLongOrNull() ?: throw NoSuchElementException("no device contact $id")
        val raw = queryRaws("${RawContacts._ID}=?", arrayOf(id)).firstOrNull { it.id == rawId && !it.deleted }
            ?: throw NoSuchElementException("no device contact $id")
        val titles = queryGroups().associate { it.id to it.title }
        val c = contactOf(raw, queryData(listOf(rawId))[rawId].orEmpty(), titles)
        c.copy(photo = readDisplayPhoto(rawId) ?: c.thumbnail)
    }

    override suspend fun groups(): List<String> = withContext(io) {
        queryGroups().map { it.title }.filter { it.isNotBlank() }.distinct().sorted()
    }

    override suspend fun refresh() {
        refreshes.tryEmit(Unit)
    }

    private fun Cursor.str(col: String) = getColumnIndex(col).let { if (it < 0 || isNull(it)) null else getString(it) }

    private fun queryRaws(selection: String, args: Array<String>): List<RawInfo> {
        val proj = arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME, RawContacts.DELETED, RawContacts.VERSION)
        val out = ArrayList<RawInfo>()
        resolver.query(RawContacts.CONTENT_URI, proj, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                out += RawInfo(
                    c.getLong(0), c.str(RawContacts.ACCOUNT_TYPE), c.str(RawContacts.ACCOUNT_NAME), c.getInt(3) != 0,
                    c.str(RawContacts.VERSION),
                )
            }
        }
        return out
    }

    /** Raw contacts by id, in chunks; missing ids are absent from the map. */
    private fun rawsById(ids: List<Long>): Map<Long, RawInfo> = ids.distinct().chunked(QUERY_CHUNK).flatMap { ch ->
        queryRaws("${RawContacts._ID} IN (${ch.joinToString(",") { "?" }})", ch.map { it.toString() }.toTypedArray())
            .filter { it.id in ch }
    }.associateBy { it.id }

    private fun queryGroups(): List<GroupInfo> {
        val proj = arrayOf(Groups._ID, Groups.TITLE, Groups.ACCOUNT_TYPE, Groups.ACCOUNT_NAME)
        val out = ArrayList<GroupInfo>()
        resolver.query(Groups.CONTENT_URI, proj, "${Groups.DELETED}=0", emptyArray(), null)?.use { c ->
            while (c.moveToNext()) {
                val title = c.str(Groups.TITLE) ?: continue
                out += GroupInfo(c.getLong(0), title, c.str(Groups.ACCOUNT_TYPE), c.str(Groups.ACCOUNT_NAME))
            }
        }
        return out
    }

    private val dataProjection = arrayOf(Data._ID, Data.RAW_CONTACT_ID, Data.MIMETYPE, Data.IS_PRIMARY, Data.IS_SUPER_PRIMARY) +
        (1..15).map { "data$it" }

    /** Data rows grouped by raw contact; [rawIds] null reads every row. Only Photo rows' data15 blob is read. */
    private fun queryData(rawIds: List<Long>?, all: Array<String> = emptyArray()): Map<Long, List<DataRow>> {
        val out = HashMap<Long, MutableList<DataRow>>()
        fun read(selection: String?, args: Array<String>, keep: Set<Long>?) {
            resolver.query(Data.CONTENT_URI, dataProjection, selection, args, null)?.use { c ->
                val iBlob = c.getColumnIndexOrThrow(Data.DATA15)
                while (c.moveToNext()) {
                    val raw = c.getLong(1)
                    if (keep != null && raw !in keep) continue
                    val mime = c.getString(2) ?: continue
                    val values = HashMap<String, String?>()
                    for (i in 3 until iBlob) values[c.getColumnName(i)] = if (c.isNull(i)) null else c.getString(i)
                    val blob = if (mime == ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE &&
                        c.getType(iBlob) == Cursor.FIELD_TYPE_BLOB
                    ) c.getBlob(iBlob) else null
                    out.getOrPut(raw) { ArrayList() } += DataRow(c.getLong(0), mime, values, blob)
                }
            }
        }
        if (rawIds == null) read(null, all, null)
        else rawIds.distinct().chunked(QUERY_CHUNK).forEach { ch ->
            read("${Data.RAW_CONTACT_ID} IN (${ch.joinToString(",") { "?" }})", ch.map { it.toString() }.toTypedArray(), ch.toSet())
        }
        return out
    }

    private fun readDisplayPhoto(rawId: Long): ByteArray? {
        val uri = Uri.withAppendedPath(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), RawContacts.DisplayPhoto.CONTENT_DIRECTORY,
        )
        return try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { fd -> fd.createInputStream().use { it.readBytes() } }
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    private fun groupIdsFor(all: List<GroupInfo>, type: String?, name: String?): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        for (g in all) if (g.type.orEmpty() == type.orEmpty() && g.name.orEmpty() == name.orEmpty()) m.putIfAbsent(g.title, g.id)
        return m
    }

    // ---- writing ----

    /** One contact's operations; [build] gets the batch offset of its first op (for back-references). */
    private class Pending(
        val key: String,
        val size: Int,
        val bytes: Int,
        /** Applies a contact too big for one batch; returns its raw contact id. */
        val alone: (() -> Long?)? = null,
        val build: (Int) -> List<ContentProviderOperation>,
    )

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

    /**
     * Groups pending contacts into applyBatch calls of at most [MAX_OPS] operations (and a
     * bounded photo payload). A contact's operations are never split across batches unless it
     * alone exceeds the limit. [onBatchFailed] decides what to do with the contacts of a failed batch.
     */
    private inner class Batcher(private val onDone: (List<Pending>, List<Long>?) -> Unit, private val onBatchFailed: (List<Pending>, Exception) -> Unit) {
        private val queue = ArrayList<Pending>()
        private var ops = 0
        private var bytes = 0

        suspend fun add(p: Pending) {
            if (queue.isNotEmpty() && (ops + p.size > MAX_OPS || bytes + p.bytes > MAX_BATCH_BYTES)) flush()
            queue += p; ops += p.size; bytes += p.bytes
            if (ops >= MAX_OPS) flush()
        }

        suspend fun flush() {
            if (queue.isEmpty()) return
            currentCoroutineContext().ensureActive()
            val batch = ArrayList(queue)
            queue.clear(); ops = 0; bytes = 0
            if (batch.size == 1 && batch[0].size > MAX_OPS) {
                // a single huge contact: its own batches, in order
                val p = batch[0]
                try {
                    val newId = p.alone?.invoke() ?: run {
                        p.build(0).chunked(MAX_OPS).forEach { resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) }
                        -1L
                    }
                    onDone(batch, listOf(newId))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    onBatchFailed(batch, e)
                }
                return
            }
            val list = ArrayList<ContentProviderOperation>()
            val starts = ArrayList<Int>()
            for (p in batch) { starts += list.size; list += p.build(list.size) }
            try {
                val r = resolver.applyBatch(ContactsContract.AUTHORITY, list)
                onDone(batch, starts.map { r[it].uri?.let(ContentUris::parseId) ?: -1L })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onBatchFailed(batch, e)
            }
        }
    }

    private fun opsFor(
        rawId: Long?, rawRef: Int, row: RowOp, yieldHere: Boolean, mimeOf: Map<Long, String> = emptyMap(),
    ): ContentProviderOperation {
        fun ContentProviderOperation.Builder.put(mime: String, values: Map<String, String?>, blob: ByteArray?) = apply {
            for ((k, v) in values) {
                val n = if (v != null && DeviceMapper.isIntColumn(mime, k)) v.trim().toLongOrNull() else null
                withValue(k, n ?: v)
            }
            if (blob != null) withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, blob)
        }
        val b = when (row) {
            is RowOp.Insert -> ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                if (rawId != null) withValue(Data.RAW_CONTACT_ID, rawId) else withValueBackReference(Data.RAW_CONTACT_ID, rawRef)
                withValue(Data.MIMETYPE, row.row.mimetype)
            }.put(row.row.mimetype, row.row.values, row.row.blob)
            is RowOp.Update -> ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, row.id))
                .put(mimeOf[row.id].orEmpty(), row.values, row.blob)
            is RowOp.Delete -> ContentProviderOperation.newDelete(ContentUris.withAppendedId(Data.CONTENT_URI, row.id))
        }
        return b.withYieldAllowed(yieldHere).build()
    }

    private fun RowOp.bytes() = when (this) {
        is RowOp.Insert -> row.blob?.size ?: 0
        is RowOp.Update -> blob?.size ?: 0
        is RowOp.Delete -> 0
    }

    private sealed interface Prep {
        class Ops(val rawId: Long, val ops: List<RowOp>, val mimeOf: Map<Long, String>) : Prep
        object ReadOnly : Prep
        class Fail(val why: String) : Prep
    }

    /** Reads the contact's current rows and diffs; [raw]/[rows]/[groups] may be prefetched. */
    private fun prepare(c: Contact, raw: RawInfo?, rows: List<DataRow>, groups: List<GroupInfo>): Prep {
        val rawId = c.id.toLongOrNull() ?: return Prep.Fail("not a device contact id")
        if (raw == null || raw.deleted) return Prep.Fail("contact no longer exists")
        if (accounts.tag(raw.type, raw.name).readOnly) return Prep.ReadOnly
        // the diff writes the whole contact, so a stale copy would undo changes made since (e.g. by sync)
        if (c.etag != null && raw.version != null && c.etag != raw.version) {
            return Prep.Fail("changed on the phone since it was read; reload and try again")
        }
        val photoRow = rows.firstOrNull { it.mimetype == ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE }
        val current = if (c.photo != null) readDisplayPhoto(rawId) ?: photoRow?.blob else null
        val mimeOf = rows.mapNotNull { r -> r.id?.let { it to r.mimetype } }.toMap()
        return Prep.Ops(rawId, DeviceMapper.diff(rows, c, groupIdsFor(groups, raw.type, raw.name), current), mimeOf)
    }

    private fun prepareFresh(c: Contact): Prep {
        val rawId = c.id.toLongOrNull() ?: return Prep.Fail("not a device contact id")
        return prepare(c, rawsById(listOf(rawId))[rawId], queryData(listOf(rawId))[rawId].orEmpty(), queryGroups())
    }

    private fun updateOps(p: Prep.Ops) = p.ops.mapIndexed { i, op -> opsFor(p.rawId, 0, op, i == 0, p.mimeOf) }

    override suspend fun update(cs: List<Contact>, onProgress: (Int) -> Unit): WriteResult = withContext(io) {
        val res = Results(onProgress)
        val byKey = HashMap<String, Contact>()
        fun applySingly(c: Contact, firstError: Exception) {
            // re-diff against fresh state, so a retry never repeats an insert that did land
            try {
                when (val p = prepareFresh(c)) {
                    is Prep.Fail -> res.fail(c.id, p.why)
                    Prep.ReadOnly -> res.skip(c.id)
                    is Prep.Ops -> {
                        if (p.ops.isNotEmpty()) updateOps(p).chunked(MAX_OPS).forEach {
                            resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(it))
                        }
                        res.ok(c.id)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                res.fail(c.id, e.message ?: firstError.message ?: e.javaClass.simpleName)
            }
        }
        val batcher = Batcher(
            onDone = { batch, _ -> batch.forEach { res.ok(it.key) } },
            onBatchFailed = { batch, e ->
                if (batch.size == 1) res.fail(batch[0].key, e.message ?: e.javaClass.simpleName)
                else batch.forEach { applySingly(byKey.getValue(it.key), e) }
            },
        )
        // contacts already known to be in a read-only account are skipped without touching the provider
        val (readOnly, writable) = cs.partition { it.account?.readOnly == true }
        readOnly.forEach { res.skip(it.id) }
        // two edits of one contact would both be diffed against the same state: keep the first
        val seen = HashSet<String>()
        val rest = writable.filter { c ->
            seen.add(c.id).also { first -> if (!first) res.fail(c.id, "listed more than once") }
        }
        if (rest.isNotEmpty()) {
            val groups = queryGroups()
            for (chunk in rest.chunked(QUERY_CHUNK)) {
                val ids = chunk.mapNotNull { it.id.toLongOrNull() }
                val raws = rawsById(ids)
                val rows = queryData(ids)
                for (c in chunk) {
                    when (val p = prepare(c, c.id.toLongOrNull()?.let(raws::get), c.id.toLongOrNull()?.let(rows::get).orEmpty(), groups)) {
                        is Prep.Fail -> res.fail(c.id, p.why)
                        Prep.ReadOnly -> res.skip(c.id)
                        is Prep.Ops -> if (p.ops.isEmpty()) res.ok(c.id) else {
                            byKey[c.id] = c
                            batcher.add(Pending(c.id, p.ops.size, p.ops.sumOf { it.bytes() }) { _ -> updateOps(p) })
                        }
                    }
                }
            }
            batcher.flush()
        }
        res.result()
    }

    /**
     * Inserts each contact as a new raw contact in [target] (null = the phone's local account).
     * `written` holds the new raw contact ids; `failed` is keyed by the input contact's id (or
     * "#index" when it has none). If a batch fails, some of its contacts may still have been
     * created (the provider may commit at yield points), so they are reported as failed with
     * that warning rather than retried, which could create duplicates.
     */
    override suspend fun create(cs: List<Contact>, target: AccountTag?, onProgress: (Int) -> Unit): WriteResult =
        withContext(io) {
            val res = Results(onProgress)
            fun key(i: Int) = cs[i].id.ifEmpty { "#$i" }
            if (target?.readOnly == true) {
                cs.indices.forEach { res.skip(key(it)) }
                return@withContext res.result()
            }
            val type = target?.type?.ifEmpty { null }
            val name = target?.name?.ifEmpty { null }
            val groupIds = groupIdsFor(queryGroups(), type, name)
            val batcher = Batcher(
                onDone = { batch, ids ->
                    batch.forEachIndexed { i, p ->
                        val newId = ids?.getOrNull(i)
                        if (newId != null && newId >= 0) res.ok(newId.toString()) else res.fail(p.key, "created, but no id returned")
                    }
                },
                onBatchFailed = { batch, e ->
                    val why = (e.message ?: e.javaClass.simpleName) +
                        if (batch.size > 1) " (batch failed; check for partially created contacts)" else ""
                    batch.forEach { res.fail(it.key, why) }
                },
            )
            fun rawInsert() = ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, type)
                .withValue(RawContacts.ACCOUNT_NAME, name)
                .withYieldAllowed(true).build()
            cs.forEachIndexed { i, c ->
                val rows = DeviceMapper.diff(emptyList(), c.copy(photo = c.photo ?: c.thumbnail), groupIds)
                // too big for one batch: create the raw contact first, then add rows by its literal id
                val alone = {
                    val r = resolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(rawInsert()))
                    val newId = ContentUris.parseId(requireNotNull(r[0].uri))
                    rows.map { opsFor(newId, 0, it, false) }.chunked(MAX_OPS).forEach {
                        resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(it))
                    }
                    newId
                }
                batcher.add(
                    Pending(key(i), rows.size + 1, rows.sumOf { it.bytes() }, alone) { base ->
                        listOf(rawInsert()) + rows.map { opsFor(null, base, it, false) }
                    },
                )
            }
            batcher.flush()
            res.result()
        }

    /** Deletes raw contacts as a normal caller (not as a sync adapter), so the delete syncs. */
    override suspend fun delete(ids: List<String>, onProgress: (Int) -> Unit): WriteResult = withContext(io) {
        val res = Results(onProgress)
        val raws = rawsById(ids.mapNotNull { it.toLongOrNull() })
        fun op(id: Long) = ContentProviderOperation.newDelete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id))
            .withYieldAllowed(true).build()
        val batcher = Batcher(
            onDone = { batch, _ -> batch.forEach { res.ok(it.key) } },
            onBatchFailed = { batch, e ->
                // deleting is idempotent, so retry one by one
                for (p in batch) try {
                    resolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(op(p.key.toLong())))
                    res.ok(p.key)
                } catch (e2: Exception) {
                    if (e2 is CancellationException) throw e2
                    res.fail(p.key, e2.message ?: e.message ?: e2.javaClass.simpleName)
                }
            },
        )
        for (id in ids) {
            val raw = id.toLongOrNull()?.let(raws::get)
            when {
                raw == null -> res.fail(id, "contact not found")
                raw.deleted -> res.ok(id)
                accounts.tag(raw.type, raw.name).readOnly -> res.skip(id)
                else -> batcher.add(Pending(id, 1, 0) { listOf(op(raw.id)) })
            }
        }
        batcher.flush()
        res.result()
    }

    private companion object {
        const val MAX_OPS = 400
        /** Keep each applyBatch well under the 1 MB binder transaction limit when photos are written. */
        const val MAX_BATCH_BYTES = 512 * 1024
        const val QUERY_CHUNK = 400
    }
}
