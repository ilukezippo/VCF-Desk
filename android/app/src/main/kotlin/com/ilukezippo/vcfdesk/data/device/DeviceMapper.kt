package com.ilukezippo.vcfdesk.data.device

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Address
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Extra
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Org
import com.ilukezippo.vcfdesk.core.model.Typed

/**
 * One row of ContactsContract.Data. [values] is keyed by raw column name ("data1".."data14",
 * "is_primary", "is_super_primary"); [blob] is data15 of a Photo row (the thumbnail).
 */
data class DataRow(val id: Long?, val mimetype: String, val values: Map<String, String?>, val blob: ByteArray? = null) {
    override fun equals(other: Any?) = other is DataRow && id == other.id && mimetype == other.mimetype &&
        values == other.values && blob.contentEquals(other.blob)
    override fun hashCode() = ((id?.hashCode() ?: 0) * 31 + mimetype.hashCode()) * 31 + values.hashCode()
}

/** A change to one Data row. Only ever produced for rows of the 13 modelled mimetypes. */
sealed interface RowOp {
    data class Insert(val row: DataRow) : RowOp

    /** [values] holds only the columns to change; a non-null [blob] replaces the photo. */
    data class Update(val id: Long, val values: Map<String, String?>, val blob: ByteArray? = null) : RowOp {
        override fun equals(other: Any?) = other is Update && id == other.id && values == other.values &&
            blob.contentEquals(other.blob)
        override fun hashCode() = id.hashCode() * 31 + values.hashCode()
    }

    data class Delete(val id: Long) : RowOp

    /**
     * Add a GroupMembership row for a group titled [title] that the contact's account doesn't
     * have yet: the writer creates the group in that account (or fails the contact).
     */
    data class JoinNewGroup(val title: String) : RowOp
}

/**
 * Maps raw-contact Data rows to the model and back. Rows the model can't represent (unknown
 * mimetypes, a second Organization/Note/Name row, membership of an unknown or system group) become
 * `Extra("device", mimetype)` and are never touched by [diff]; nor are columns the model
 * doesn't cover (diff writes only the columns whose mapped value changed).
 */
object DeviceMapper {
    private const val NAME = StructuredName.CONTENT_ITEM_TYPE
    private const val NICK = Nickname.CONTENT_ITEM_TYPE
    private const val ORG = Organization.CONTENT_ITEM_TYPE
    private const val NOTE = Note.CONTENT_ITEM_TYPE
    private const val PHOTO = Photo.CONTENT_ITEM_TYPE
    private const val GROUP = GroupMembership.CONTENT_ITEM_TYPE
    private const val POSTAL = StructuredPostal.CONTENT_ITEM_TYPE
    private const val PRIMARY = "is_primary"
    private const val D1 = "data1"

    private val SINGLES = setOf(NAME, NICK, ORG, NOTE, PHOTO)

    /** Labelled single-value kinds; the value is always data1. */
    private class Kind(
        val mime: String,
        val codec: LabelCodec,
        val get: (Contact) -> List<Typed>,
        val insertExtra: Map<String, String> = emptyMap(),
    )

    private val TYPED = listOf(
        Kind(Phone.CONTENT_ITEM_TYPE, DeviceLabels.PHONE, { it.phones }),
        Kind(Email.CONTENT_ITEM_TYPE, DeviceLabels.EMAIL, { it.emails }),
        Kind(Website.CONTENT_ITEM_TYPE, DeviceLabels.WEBSITE, { it.websites }),
        Kind(Event.CONTENT_ITEM_TYPE, DeviceLabels.EVENT, { it.events }),
        Kind(Relation.CONTENT_ITEM_TYPE, DeviceLabels.RELATION, { it.relations }),
        // IM TYPE (home/work/other) is not modelled: new rows get TYPE_OTHER, existing ones keep theirs
        Kind(Im.CONTENT_ITEM_TYPE, DeviceLabels.IM_PROTOCOL, { it.ims }, mapOf("data2" to Im.TYPE_OTHER.toString())),
    )
    private val TYPED_MIMES = TYPED.map { it.mime }.toSet() + POSTAL

    // StructuredPostal: street, pobox, neighborhood (= vCard extended), city, region, postcode, country
    private val POSTAL_COLS = listOf("data4", "data5", "data6", "data7", "data8", "data9", "data10")

    /** True for integer columns, so writes put numbers rather than text. */
    internal fun isIntColumn(mime: String, col: String) = when {
        col == PRIMARY -> true
        col == "data2" -> mime in TYPED_MIMES || mime == ORG
        col == "data5" -> mime == Im.CONTENT_ITEM_TYPE
        col == D1 -> mime == GROUP
        else -> false
    }

    private class Sorted(
        val typed: Map<String, List<DataRow>>,
        val single: Map<String, DataRow>,
        val groups: List<Pair<DataRow, String>>,
        val untouched: List<DataRow>,
    )

    private fun sort(rows: List<DataRow>, titleOf: (Long) -> String?): Sorted {
        val typed = LinkedHashMap<String, MutableList<DataRow>>()
        val single = LinkedHashMap<String, DataRow>()
        val groups = ArrayList<Pair<DataRow, String>>()
        val untouched = ArrayList<DataRow>()
        // "first row of a kind" and positional pairing must not depend on the order rows arrive in
        for (r in rows.sortedWith(compareBy(nullsLast()) { it.id })) when {
            r.mimetype in TYPED_MIMES -> typed.getOrPut(r.mimetype) { ArrayList() }.add(r)
            r.mimetype in SINGLES && r.mimetype !in single -> single[r.mimetype] = r
            r.mimetype == GROUP -> {
                val t = r.values[D1]?.trim()?.toLongOrNull()?.let(titleOf)
                if (t != null) groups += r to t else untouched += r
            }
            else -> untouched += r
        }
        return Sorted(typed, single, groups, untouched)
    }

    private fun Map<String, String?>?.s(k: String) = this?.get(k).orEmpty()
    private fun isPrimary(v: Map<String, String?>) = (v[PRIMARY]?.trim()?.toIntOrNull() ?: 0) != 0

    private fun readTyped(k: Kind, r: DataRow) = Typed(k.codec.read(r.values), r.values.s(D1), isPrimary(r.values))

    private fun readAddress(r: DataRow): Address {
        val v = r.values
        val p = POSTAL_COLS.map { v.s(it) }
        // only a formatted address (common from some sync adapters): show it as the street
        val street = if (p.all { it.isBlank() } && v.s(D1).isNotBlank()) v.s(D1) else p[0]
        return Address(
            DeviceLabels.POSTAL.read(v), street = street, city = p[3], region = p[4], postcode = p[5],
            country = p[6], poBox = p[1], extended = p[2], pref = isPrimary(v),
        )
    }

    fun toContact(rawId: Long, account: AccountTag, rows: List<DataRow>, groupTitles: Map<Long, String>): Contact {
        val s = sort(rows) { groupTitles[it] }
        val n = s.single[NAME]?.values
        val o = s.single[ORG]?.values
        return Contact(
            id = rawId.toString(),
            account = account,
            name = Name(
                prefix = n.s("data4"), first = n.s("data2"), middle = n.s("data5"), last = n.s("data3"),
                suffix = n.s("data6"), phoneticFirst = n.s("data7"), phoneticLast = n.s("data9"),
                nickname = s.single[NICK]?.values.s(D1), display = n.s(D1),
            ),
            phones = typedOf(s, 0), emails = typedOf(s, 1), websites = typedOf(s, 2),
            events = typedOf(s, 3), relations = typedOf(s, 4), ims = typedOf(s, 5),
            addresses = s.typed[POSTAL].orEmpty().map(::readAddress),
            org = Org(company = o.s(D1), department = o.s("data5"), title = o.s("data4")),
            notes = s.single[NOTE]?.values.s(D1),
            groups = s.groups.mapTo(LinkedHashSet()) { it.second },
            thumbnail = s.single[PHOTO]?.blob,
            extras = s.untouched.map { Extra("device", it.mimetype) },
        )
    }

    /** The rows [toContact] leaves to the source (other than photos), for a lossless snapshot. */
    fun unmodelledRows(rows: List<DataRow>, groupTitles: Map<Long, String>): List<DataRow> =
        sort(rows) { groupTitles[it] }.untouched.filter { it.mimetype != PHOTO }

    private fun typedOf(s: Sorted, i: Int) = TYPED[i].let { k -> s.typed[k.mime].orEmpty().map { readTyped(k, it) } }

    /**
     * Row operations that turn [before] into [after]. `after.photo == null` means "not loaded"
     * and never removes a photo; a photo is written only when it differs from [currentPhoto]
     * (the display photo, falling back to the row's thumbnail). [groupIds] maps the titles of the
     * contact's account's groups to their ids; a title not in it gives [RowOp.JoinNewGroup].
     */
    fun diff(
        before: List<DataRow>,
        after: Contact,
        groupIds: Map<String, Long>,
        currentPhoto: ByteArray? = null,
    ): List<RowOp> {
        val titles = groupIds.entries.associate { (t, id) -> id to t }
        // rows without an id can't be addressed; leave them alone
        val s = sort(before.filter { it.id != null }) { titles[it] }
        val ops = ArrayList<RowOp>()
        diffName(s.single[NAME], after.name, ops)
        diffSingle(NICK, s.single[NICK], mapOf(D1 to after.name.nickname), ops)
        diffSingle(
            ORG, s.single[ORG],
            mapOf(D1 to after.org.company, "data4" to after.org.title, "data5" to after.org.department), ops,
            insertExtra = mapOf("data2" to Organization.TYPE_WORK.toString()),
        )
        diffSingle(NOTE, s.single[NOTE], mapOf(D1 to after.notes), ops)
        for (k in TYPED) diffTyped(k, s.typed[k.mime].orEmpty(), k.get(after), ops)
        diffPostal(s.typed[POSTAL].orEmpty(), after.addresses, ops)
        // groups
        val current = s.groups.map { it.second }.toSet()
        for ((row, t) in s.groups) if (t !in after.groups) ops += RowOp.Delete(row.id!!)
        for (t in after.groups) if (t !in current) {
            val gid = groupIds[t]
            ops += if (gid != null) RowOp.Insert(DataRow(null, GROUP, mapOf(D1 to gid.toString()))) else RowOp.JoinNewGroup(t)
        }
        // photo
        val photo = after.photo
        if (photo != null) {
            val row = s.single[PHOTO]
            val cur = currentPhoto ?: row?.blob
            if (cur == null || !cur.contentEquals(photo)) {
                ops += if (row != null) RowOp.Update(row.id!!, emptyMap(), photo)
                else RowOp.Insert(DataRow(null, PHOTO, emptyMap(), photo))
            }
        }
        return ops
    }

    /** Keeps only changes that differ from what the row already holds. */
    private fun changed(row: DataRow, out: Map<String, String?>) =
        out.filter { (k, v) -> row.values[k].orEmpty() != v.orEmpty() }

    private fun Map<String, String>.nonBlank() = filterValues { it.isNotBlank() }

    private fun diffSingle(
        mime: String, row: DataRow?, desired: Map<String, String>, ops: MutableList<RowOp>,
        insertExtra: Map<String, String> = emptyMap(),
    ) {
        if (row == null) {
            val v = desired.nonBlank()
            if (v.isNotEmpty()) ops += RowOp.Insert(DataRow(null, mime, v + insertExtra))
            return
        }
        val out = changed(row, desired.mapValues { it.value.ifEmpty { null } })
        if (out.isNotEmpty()) ops += RowOp.Update(row.id!!, out)
    }

    private fun diffName(row: DataRow?, n: Name, ops: MutableList<RowOp>) {
        // prefix, given, middle, family, suffix: the parts the provider joins into the display name
        val struct = mapOf("data4" to n.prefix, "data2" to n.first, "data5" to n.middle, "data3" to n.last, "data6" to n.suffix)
        val phonetic = mapOf("data7" to n.phoneticFirst, "data9" to n.phoneticLast)
        if (row == null) {
            val v = (mapOf(D1 to n.display) + struct + phonetic).nonBlank()
            if (v.isNotEmpty()) ops += RowOp.Insert(DataRow(null, NAME, v))
            return
        }
        val out = LinkedHashMap<String, String?>()
        if (n.display != row.values.s(D1)) {
            out[D1] = n.display.ifEmpty { null }
            // with the display name and the parts both written the provider keeps both as given;
            // the display name alone would make it re-split the display name over the parts
            struct.nonBlank().forEach { (k, v) -> out[k] = v }
        }
        out.putAll(changed(row, (struct + phonetic).mapValues { it.value.ifEmpty { null } }))
        if (out.isNotEmpty()) ops += RowOp.Update(row.id!!, out)
    }

    private fun digits(s: String) = buildString { for (ch in s) if (ch.isDigit()) append(Character.digit(ch, 10)) }

    private fun diffTyped(k: Kind, rows: List<DataRow>, items: List<Typed>, ops: MutableList<RowOp>) {
        val cur = rows.map { readTyped(k, it) }
        val key: (Typed) -> String = if (k.mime == Phone.CONTENT_ITEM_TYPE) {
            { t -> digits(t.value).ifEmpty { t.value.trim() } }
        } else {
            { t -> t.value.trim().lowercase() }
        }
        match(rows, cur, items, key, { it.value.isBlank() }, ops,
            update = { row, c, item ->
                val out = LinkedHashMap<String, String?>()
                if (item.value != c.value) out[D1] = item.value.ifEmpty { null }
                if (item.label != c.label) out.putAll(k.codec.write(item.label))
                if (item.pref != c.pref) out[PRIMARY] = if (item.pref) "1" else "0"
                changed(row, out)
            },
            insert = { item ->
                val v = LinkedHashMap<String, String?>()
                v[D1] = item.value
                v.putAll(k.codec.write(item.label))
                if (item.pref) v[PRIMARY] = "1"
                v.putAll(k.insertExtra)
                DataRow(null, k.mime, v.filterValues { it != null })
            },
        )
    }

    private fun Address.parts() = listOf(street, poBox, extended, city, region, postcode, country)

    private fun diffPostal(rows: List<DataRow>, items: List<Address>, ops: MutableList<RowOp>) {
        val cur = rows.map(::readAddress)
        match(rows, cur, items, { a -> a.parts().joinToString("\u0000") { it.trim().lowercase() } },
            { a -> a.parts().all { it.isBlank() } }, ops,
            update = { row, c, item ->
                val out = LinkedHashMap<String, String?>()
                // all structured parts together, so the provider rebuilds the formatted address from them
                if (item.parts() != c.parts()) POSTAL_COLS.zip(item.parts()).forEach { (col, v) -> out[col] = v.ifEmpty { null } }
                if (item.label != c.label) out.putAll(DeviceLabels.POSTAL.write(item.label))
                if (item.pref != c.pref) out[PRIMARY] = if (item.pref) "1" else "0"
                changed(row, out)
            },
            insert = { item ->
                val v = LinkedHashMap<String, String?>()
                POSTAL_COLS.zip(item.parts()).forEach { (col, s) -> if (s.isNotBlank()) v[col] = s }
                v.putAll(DeviceLabels.POSTAL.write(item.label))
                if (item.pref) v[PRIMARY] = "1"
                DataRow(null, POSTAL, v.filterValues { it != null })
            },
        )
    }

    /**
     * Pairs existing rows with wanted items: identical first, then same value (key), then by
     * position. Paired rows are updated in place (keeping their id and unmodelled columns);
     * leftover rows are deleted; leftover non-blank items are inserted.
     */
    private fun <T> match(
        rows: List<DataRow>, cur: List<T>, items: List<T>, key: (T) -> String, blank: (T) -> Boolean,
        ops: MutableList<RowOp>,
        update: (DataRow, T, T) -> Map<String, String?>,
        insert: (T) -> DataRow,
    ) {
        val rowOf = arrayOfNulls<Int>(items.size)
        val used = BooleanArray(rows.size)
        fun pass(pick: (Int, Int) -> Boolean) {
            for (i in items.indices) if (rowOf[i] == null) {
                val j = rows.indices.firstOrNull { !used[it] && pick(i, it) } ?: continue
                rowOf[i] = j; used[j] = true
            }
        }
        pass { i, j -> cur[j] == items[i] }
        pass { i, j -> !blank(items[i]) && key(cur[j]) == key(items[i]) }
        pass { i, _ -> !blank(items[i]) }
        for (i in items.indices) {
            val j = rowOf[i]
            if (j == null) {
                if (!blank(items[i])) ops += RowOp.Insert(insert(items[i]))
            } else {
                val out = update(rows[j], cur[j], items[i])
                if (out.isNotEmpty()) ops += RowOp.Update(rows[j].id!!, out)
            }
        }
        for (j in rows.indices) if (!used[j]) ops += RowOp.Delete(rows[j].id!!)
    }
}
