package com.ilukezippo.vcfdesk.data.device

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.OperationApplicationException
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import java.io.File
import java.io.FileNotFoundException

/**
 * Small in-memory stand-in for the contacts provider: raw contacts, data rows and groups.
 * Honors the simple selections DeviceSource uses (`col=?`, `col=0`, `col IN (?,..)`, joined by AND)
 * and records every call so tests can assert what was (not) touched.
 */
class FakeContactsProvider : ContentProvider() {
    class Raw(
        val id: Long, val type: String?, val name: String?, var deleted: Boolean = false, var version: Int = 1,
        var starred: Int = 0, var ringtone: String? = null, var voicemail: Int = 0,
    )
    class Row(val id: Long, val rawId: Long, val mimetype: String, val values: MutableMap<String, Any?>)
    class Group(
        val id: Long, val title: String, val type: String?, val name: String?,
        val deleted: Boolean = false, val visible: Boolean = true,
        val systemId: String? = null, val readOnly: Boolean = false, val autoAdd: Boolean = false,
    )

    val raws = linkedMapOf<Long, Raw>()
    val data = linkedMapOf<Long, Row>()
    val groups = linkedMapOf<Long, Group>()
    val displayPhotos = mutableMapOf<Long, ByteArray>()

    val calls = mutableListOf<String>()
    val batches = mutableListOf<List<ContentProviderOperation>>()
    /** Make an applyBatch fail (whole batch) when this returns true. */
    var failBatch: (List<ContentProviderOperation>) -> Boolean = { false }
    /** Make every group insert throw (e.g. an account that doesn't allow groups). */
    var failGroupInsert = false
    /** With no sortOrder, return rows newest first (a provider is free to use any order). */
    var reverseWhenUnsorted = false

    private var nextRaw = 1L
    private var nextData = 1000L
    private var nextGroup = 500L

    fun addRaw(type: String? = null, name: String? = null, deleted: Boolean = false): Long {
        val id = nextRaw++
        raws[id] = Raw(id, type, name, deleted)
        return id
    }

    fun addRow(rawId: Long, mimetype: String, vararg values: Pair<String, Any?>): Long {
        val id = nextData++
        data[id] = Row(id, rawId, mimetype, mutableMapOf(*values))
        return id
    }

    fun addGroup(
        title: String, type: String? = null, name: String? = null,
        systemId: String? = null, readOnly: Boolean = false, autoAdd: Boolean = false,
    ): Long {
        val id = nextGroup++
        groups[id] = Group(id, title, type, name, systemId = systemId, readOnly = readOnly, autoAdd = autoAdd)
        return id
    }

    fun rowsOf(rawId: Long) = data.values.filter { it.rawId == rawId }

    fun writeCalls() = calls.filter { !it.startsWith("query") && !it.startsWith("openAssetFile") }

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null

    private fun table(uri: Uri) = uri.pathSegments.firstOrNull()

    private val dataCols = listOf(
        "_id", "raw_contact_id", "mimetype", "is_primary", "is_super_primary",
    ) + (1..15).map { "data$it" }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        calls += "query ${table(uri)}" + (sortOrder?.let { " order=$it" } ?: "")
        val records: List<Map<String, Any?>> = when (table(uri)) {
            "raw_contacts" -> raws.values.map {
                mapOf(
                    "_id" to it.id, "account_type" to it.type, "account_name" to it.name,
                    "deleted" to if (it.deleted) 1 else 0, "version" to it.version,
                    "starred" to it.starred, "custom_ringtone" to it.ringtone, "send_to_voicemail" to it.voicemail,
                )
            }
            "data" -> data.values.map { r ->
                val m = HashMap<String, Any?>()
                m["_id"] = r.id; m["raw_contact_id"] = r.rawId; m["mimetype"] = r.mimetype
                m.putAll(r.values)
                m
            }
            "groups" -> groups.values.map {
                mapOf(
                    "_id" to it.id, "title" to it.title, "account_type" to it.type,
                    "account_name" to it.name, "deleted" to if (it.deleted) 1 else 0,
                    "system_id" to it.systemId, "group_is_read_only" to if (it.readOnly) 1 else 0,
                    "auto_add" to if (it.autoAdd) 1 else 0,
                )
            }
            else -> error("unexpected query $uri")
        }
        val allCols = when (table(uri)) {
            "data" -> dataCols
            "raw_contacts" -> listOf("_id", "account_type", "account_name", "deleted", "version", "starred", "custom_ringtone", "send_to_voicemail")
            else -> listOf("_id", "title", "account_type", "account_name", "deleted", "system_id", "group_is_read_only", "auto_add")
        }
        val cols = projection?.toList() ?: allCols
        cols.forEach { require(it in allCols) { "unknown column $it" } }
        val filter = parseSelection(selection, selectionArgs)
        val cursor = MatrixCursor(cols.toTypedArray())
        for (rec in sort(records.filter(filter), sortOrder)) cursor.addRow(cols.map { rec[it] }.toTypedArray())
        return cursor
    }

    private fun sort(recs: List<Map<String, Any?>>, order: String?): List<Map<String, Any?>> {
        if (order.isNullOrBlank()) return if (reverseWhenUnsorted) recs.reversed() else recs
        var cmp: Comparator<Map<String, Any?>> = Comparator { _, _ -> 0 }
        for (term in order.split(",")) {
            val (col, dir) = term.trim().split(Regex("\\s+")).let { it[0] to it.getOrElse(1) { "ASC" }.uppercase() }
            val c = compareBy<Map<String, Any?>> { (it[col] as? Number)?.toLong() ?: it[col]?.toString()?.toLongOrNull() }
            cmp = cmp.then(if (dir == "DESC") c.reversed() else c)
        }
        return recs.sortedWith(cmp)
    }

    private fun parseSelection(sel: String?, args: Array<out String>?): (Map<String, Any?>) -> Boolean {
        if (sel.isNullOrBlank()) return { true }
        val argIt = (args ?: emptyArray()).iterator()
        val preds = sel.split(Regex("\\s+AND\\s+", RegexOption.IGNORE_CASE)).map { part ->
            val p = part.trim()
            val inM = Regex("^(\\w+)\\s+IN\\s*\\(([?,\\s]*)\\)$", RegexOption.IGNORE_CASE).find(p)
            val eqM = Regex("^(\\w+)\\s*=\\s*(\\?|\\d+)$").find(p)
            when {
                inM != null -> {
                    val n = inM.groupValues[2].count { it == '?' }
                    val vals = (0 until n).map { argIt.next() }.toSet()
                    val col = inM.groupValues[1];
                    { rec: Map<String, Any?> -> rec[col]?.toString() in vals }
                }
                eqM != null -> {
                    val v = if (eqM.groupValues[2] == "?") argIt.next() else eqM.groupValues[2]
                    val col = eqM.groupValues[1];
                    { rec: Map<String, Any?> -> rec[col]?.toString() == v }
                }
                else -> error("unsupported selection: $p")
            }
        }
        return { rec -> preds.all { it(rec) } }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        calls += "insert ${table(uri)}"
        val v = values ?: ContentValues()
        val out = when (table(uri)) {
            "raw_contacts" -> {
                val id = addRaw(v.getAsString("account_type"), v.getAsString("account_name"))
                raws.getValue(id).apply {
                    starred = v.getAsInteger("starred") ?: 0
                    ringtone = v.getAsString("custom_ringtone")
                    voicemail = v.getAsInteger("send_to_voicemail") ?: 0
                }
                ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)
            }
            "groups" -> {
                if (failGroupInsert) throw IllegalArgumentException("groups not supported for this account")
                val id = nextGroup++
                groups[id] = Group(
                    id, requireNotNull(v.getAsString("title")), v.getAsString("account_type"),
                    v.getAsString("account_name"), visible = v.getAsInteger("group_visible") == 1,
                )
                ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, id)
            }
            "data" -> {
                val rawId = requireNotNull(v.getAsLong("raw_contact_id")) { "raw_contact_id missing" }
                require(raws.containsKey(rawId)) { "no raw contact $rawId" }
                val mime = requireNotNull(v.getAsString("mimetype"))
                val map = mutableMapOf<String, Any?>()
                for (k in v.keySet()) if (k != "raw_contact_id" && k != "mimetype") map[k] = norm(v.get(k))
                val id = nextData++
                data[id] = Row(id, rawId, mime, map)
                ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id)
            }
            else -> error("unexpected insert $uri")
        }
        notifyChanged()
        return out
    }

    private fun norm(v: Any?): Any? = when (v) {
        null -> null
        is ByteArray -> v
        else -> v.toString()
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        calls += "update ${table(uri)}"
        require(table(uri) == "data" && uri.pathSegments.size == 2) { "unexpected update $uri" }
        val row = data[uri.lastPathSegment!!.toLong()] ?: return 0
        val v = values ?: return 0
        for (k in v.keySet()) {
            require(k != "raw_contact_id" && k != "mimetype" && k != "_id") { "must not rewrite $k" }
            row.values[k] = norm(v.get(k))
        }
        raws[row.rawId]?.let { it.version++ }
        notifyChanged()
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        calls += "delete $uri"
        val id = uri.lastPathSegment!!.toLong()
        val n = when (table(uri)) {
            "raw_contacts" -> {
                val r = raws[id] ?: return 0
                if (uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER) == "true") {
                    raws.remove(id); data.values.removeAll { it.rawId == id }
                } else r.deleted = true
                1
            }
            "data" -> if (data.remove(id) != null) 1 else 0
            else -> error("unexpected delete $uri")
        }
        notifyChanged()
        return n
    }

    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        calls += "applyBatch ${operations.size}"
        batches += operations.toList()
        if (failBatch(operations)) throw OperationApplicationException("injected failure")
        return super.applyBatch(operations)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        calls += "openAssetFile $uri"
        val segs = uri.pathSegments
        val bytes = if (segs.size == 3 && segs[0] == "raw_contacts" && segs[2] == "display_photo") {
            displayPhotos[segs[1].toLong()]
        } else null
        bytes ?: throw FileNotFoundException(uri.toString())
        val f = File.createTempFile("display", ".jpg").apply { writeBytes(bytes); deleteOnExit() }
        return AssetFileDescriptor(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), 0, f.length())
    }

    private fun notifyChanged() {
        context?.contentResolver?.notifyChange(RawContacts.CONTENT_URI, null)
    }
}
