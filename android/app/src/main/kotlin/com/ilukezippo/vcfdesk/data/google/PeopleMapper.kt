package com.ilukezippo.vcfdesk.data.google

import com.ilukezippo.vcfdesk.core.model.Address
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Extra
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Org
import com.ilukezippo.vcfdesk.core.model.Typed
import com.ilukezippo.vcfdesk.core.text.Dates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * People API person JSON ↔ [Contact].
 *
 * Data preservation: [toContact] keeps the whole original person as `Extra("google", json)`.
 * [toPerson] starts from that original and rewrites only the fields whose modelled content
 * changed; within a changed list, entries are paired with the original ones (identical, then same
 * value, then same position) and keep their unmodelled keys (`metadata`, `countryCode`,
 * `jobDescription`, keys added to the API later, ...). Unknown top-level keys, other names and
 * organizations, system/domain memberships and anything else the model lacks pass through untouched.
 */
object PeopleMapper {
    const val EXTRA_KIND = "google"
    const val MY_CONTACTS = "contactGroups/myContacts"

    /** Mask field names, in the order [updateMask] lists them. */
    private val MASK_ORDER = listOf(
        "names", "nicknames", "phoneNumbers", "emailAddresses", "addresses", "organizations", "urls",
        "birthdays", "events", "relations", "imClients", "biographies", "memberships",
    )

    // ---- labels: People API `type` ↔ readable labels (custom types kept verbatim) ----

    private class Labels(val pairs: List<Pair<String, String>>) {
        fun read(type: String?): String = type?.let { t -> pairs.firstOrNull { it.first == t }?.second ?: t }.orEmpty()
        fun write(label: String): String? {
            val l = label.trim()
            if (l.isEmpty()) return null
            return pairs.firstOrNull { it.second.equals(l, ignoreCase = true) || it.first.equals(l, ignoreCase = true) }?.first ?: label
        }
    }

    private val PHONE = Labels(
        listOf(
            "mobile" to "Mobile", "home" to "Home", "work" to "Work", "homeFax" to "Home fax", "workFax" to "Work fax",
            "otherFax" to "Other fax", "pager" to "Pager", "workMobile" to "Work mobile", "workPager" to "Work pager",
            "main" to "Main", "googleVoice" to "Google Voice", "other" to "Other",
        ),
    )
    private val HOME_WORK = Labels(listOf("home" to "Home", "work" to "Work", "other" to "Other"))
    private val URL = Labels(
        listOf(
            "homePage" to "Homepage", "blog" to "Blog", "profile" to "Profile", "home" to "Home", "work" to "Work",
            "ftp" to "FTP", "reservations" to "Reservations", "appInstallPage" to "App install page", "other" to "Other",
        ),
    )
    private val EVENT = Labels(listOf("anniversary" to "anniversary", "other" to "other"))
    private val RELATION = Labels(
        listOf(
            "spouse" to "Spouse", "child" to "Child", "mother" to "Mother", "father" to "Father", "parent" to "Parent",
            "brother" to "Brother", "sister" to "Sister", "friend" to "Friend", "relative" to "Relative",
            "domesticPartner" to "Domestic partner", "manager" to "Manager", "assistant" to "Assistant",
            "referredBy" to "Referred by", "partner" to "Partner",
        ),
    )
    private val IM = Labels(
        listOf(
            "aim" to "AIM", "msn" to "Windows Live", "yahoo" to "Yahoo", "skype" to "Skype", "qq" to "QQ",
            "googleTalk" to "Google Talk", "icq" to "ICQ", "jabber" to "Jabber", "netMeeting" to "NetMeeting",
        ),
    )

    // ---- JSON helpers ----

    private fun JsonObject.s(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.list(k: String): List<JsonObject> = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    /**
     * Entries the user's own contact holds. With READ_SOURCE_TYPE_CONTACT Google sends only these,
     * but entries from another source (e.g. the person's PROFILE) are read-only and ignored by
     * updates, so they are never shown as editable; on write they are sent back unchanged.
     */
    private fun isOwn(e: JsonObject): Boolean {
        val t = (e.obj("metadata")?.obj("source")?.get("type") as? JsonPrimitive)?.contentOrNull
        return t.isNullOrEmpty() || t == "CONTACT"
    }
    private fun JsonObject.own(k: String) = list(k).filter(::isOwn)
    private fun JsonObject.foreign(k: String) = list(k).filterNot(::isOwn)

    /** True when the entry belongs to the contact itself (not to a profile or other read-only source). */
    fun isContactSourced(e: JsonObject) = isOwn(e)

    private fun isPrimary(e: JsonObject) = e.obj("metadata")?.get("primary")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true

    /** Index of the entry the model reads (the primary one, else the first). */
    private fun primaryIndex(entries: List<JsonObject>) = entries.indexOfFirst(::isPrimary).takeIf { it >= 0 } ?: 0

    private class Edit(base: JsonObject) {
        val m = LinkedHashMap<String, JsonElement>(base)
        /** Sets [k] to [v], or removes it when [v] is blank or null. */
        fun put(k: String, v: String?) {
            if (v.isNullOrEmpty()) m.remove(k) else m[k] = JsonPrimitive(v)
        }
        fun drop(vararg ks: String) = ks.forEach { m.remove(it) }
        fun build() = JsonObject(m)
    }

    private fun edit(base: JsonObject?, block: Edit.() -> Unit) = Edit(base ?: JsonObject(emptyMap())).apply(block).build()

    // ---- dates ----

    private fun asciiDigits(s: String) = buildString {
        for (ch in s) append(if (ch.isDigit()) Character.forDigit(Character.digit(ch, 10), 10) else ch)
    }

    private fun readDate(e: JsonObject): String {
        val d = e.obj("date") ?: return e.s("text")
        fun n(k: String) = (d[k] as? JsonPrimitive)?.intOrNull ?: 0
        val y = n("year"); val m = n("month"); val day = n("day")
        return when {
            m == 0 || day == 0 -> if (y != 0) "%04d".format(y) else e.s("text")
            y == 0 -> "--%02d-%02d".format(m, day)
            else -> "%04d-%02d-%02d".format(y, m, day)
        }
    }

    private val ymd = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
    private val md = Regex("--(\\d{2})-(\\d{2})")
    private val yearOnly = Regex("\\d{4}")

    /** A Google Date object for [value], or null when it is not a date we understand. */
    private fun dateOf(value: String): JsonObject? {
        fun obj(vararg kv: Pair<String, Int>) = JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })
        val ascii = asciiDigits(value).trim()
        // a bare year (Google allows year-only dates); Dates.normalise would read it as an Excel serial
        if (yearOnly.matches(ascii)) return obj("year" to ascii.toInt())
        val v = Dates.normalise(ascii)
        ymd.matchEntire(v)?.let { m ->
            val (y, mo, d) = m.destructured
            return obj("year" to y.toInt(), "month" to mo.toInt(), "day" to d.toInt())
        }
        md.matchEntire(v)?.let { m ->
            val (mo, d) = m.destructured
            return obj("month" to mo.toInt(), "day" to d.toInt())
        }
        return null
    }

    // ---- reading ----

    private fun readName(p: JsonObject): Name {
        val names = p.own("names")
        val n = names.getOrNull(primaryIndex(names)) ?: JsonObject(emptyMap())
        return Name(
            prefix = n.s("honorificPrefix"), first = n.s("givenName"), middle = n.s("middleName"), last = n.s("familyName"),
            suffix = n.s("honorificSuffix"), phoneticFirst = n.s("phoneticGivenName"), phoneticLast = n.s("phoneticFamilyName"),
            nickname = p.own("nicknames").firstOrNull()?.s("value").orEmpty(),
            display = n.s("displayName").ifEmpty { n.s("unstructuredName") },
        )
    }

    private fun readPhone(e: JsonObject) = Typed(PHONE.read(e.s("type").ifEmpty { null }), e.s("value"), isPrimary(e))
    private fun readEmail(e: JsonObject) = Typed(HOME_WORK.read(e.s("type").ifEmpty { null }), e.s("value"), isPrimary(e))
    private fun readUrl(e: JsonObject) = Typed(URL.read(e.s("type").ifEmpty { null }), e.s("value"), isPrimary(e))
    private fun readRelation(e: JsonObject) = Typed(RELATION.read(e.s("type").ifEmpty { null }), e.s("person"), isPrimary(e))
    private fun readIm(e: JsonObject) = Typed(IM.read(e.s("protocol").ifEmpty { null }), e.s("username"), isPrimary(e))
    private fun readBirthday(e: JsonObject) = Typed("birthday", readDate(e), isPrimary(e))
    private fun readEvent(e: JsonObject) = Typed(EVENT.read(e.s("type").ifEmpty { null }), readDate(e), isPrimary(e))

    private fun readAddress(e: JsonObject): Address {
        val structured = listOf("streetAddress", "city", "region", "postalCode", "country", "poBox", "extendedAddress")
            .any { e.s(it).isNotEmpty() }
        return Address(
            label = HOME_WORK.read(e.s("type").ifEmpty { null }),
            street = if (structured) e.s("streetAddress") else e.s("formattedValue"),
            city = e.s("city"), region = e.s("region"), postcode = e.s("postalCode"), country = e.s("country"),
            poBox = e.s("poBox"), extended = e.s("extendedAddress"), pref = isPrimary(e),
        )
    }

    private fun orgEntries(p: JsonObject) = p.own("organizations")

    private fun readOrg(p: JsonObject): Org {
        val orgs = orgEntries(p)
        val o = orgs.getOrNull(primaryIndex(orgs)) ?: return Org()
        return Org(o.s("name"), o.s("department"), o.s("title"))
    }

    private fun membershipName(e: JsonObject) = e.obj("contactGroupMembership")?.s("contactGroupResourceName")

    private fun readGroups(p: JsonObject, groupNames: Map<String, String>): Set<String> =
        p.list("memberships").mapNotNullTo(LinkedHashSet()) { e -> membershipName(e)?.let(groupNames::get) }

    /**
     * [groupNames] maps contact group resource names to the names shown as groups (user groups);
     * memberships of other groups are not modelled and are kept as they are.
     */
    fun toContact(person: JsonObject, groupNames: Map<String, String>): Contact = Contact(
        id = person.s("resourceName"),
        etag = person.s("etag").ifEmpty { null },
        name = readName(person),
        phones = person.own("phoneNumbers").map(::readPhone),
        emails = person.own("emailAddresses").map(::readEmail),
        addresses = person.own("addresses").map(::readAddress),
        org = readOrg(person),
        websites = person.own("urls").map(::readUrl),
        events = person.own("birthdays").map(::readBirthday) + person.own("events").map(::readEvent),
        relations = person.own("relations").map(::readRelation),
        ims = person.own("imClients").map(::readIm),
        notes = person.own("biographies").firstOrNull()?.s("value").orEmpty(),
        groups = readGroups(person, groupNames),
        extras = listOf(Extra(EXTRA_KIND, person.toString())),
    )

    /** The original person this contact was read from, if it carries one for the same resource. */
    fun original(c: Contact): JsonObject? {
        if (c.id.isEmpty()) return null
        return c.extras.asSequence().filter { it.kind == EXTRA_KIND }
            .mapNotNull { runCatching { Json.parseToJsonElement(it.raw) as? JsonObject }.getOrNull() }
            .firstOrNull { it.s("resourceName") == c.id }
    }

    // ---- duplicate removal: does one person hold anything the other lacks? ----

    /** Keys that belong to one person record, not to the contact's data. */
    private val PER_RECORD = setOf("resourceName", "etag", "metadata", "photos", "coverPhotos")

    /** Sub-fields the server derives from the others; every other sub-field counts as data. */
    private val DERIVED = setOf("metadata", "formattedType", "formattedValue", "canonicalForm")

    private fun dataOf(key: String, e: JsonElement): JsonElement {
        if (e !is JsonObject) return e
        val drop = if (key == "addresses") DERIVED + "countryCode" else DERIVED
        return JsonObject(e.filterKeys { it !in drop })
    }

    private fun entries(key: String, v: JsonElement?): List<JsonElement> = when (v) {
        null -> emptyList()
        is JsonArray -> v.map { dataOf(key, it) }
        else -> listOf(dataOf(key, v))
    }

    private fun persons(c: Contact): List<JsonObject?> = c.extras.filter { it.kind == EXTRA_KIND }
        .map { runCatching { Json.parseToJsonElement(it.raw) as? JsonObject }.getOrNull() }

    private fun groupRefs(p: JsonObject): Set<String> = p.list("memberships").mapNotNullTo(HashSet(), ::membershipName)

    /**
     * True when [c]'s Google person holds anything [keeper]'s person doesn't — deliberately
     * conservative: every key except the per-record ones is compared as whole entries (minus
     * derived sub-fields), group memberships (starred included) by group, and a person JSON that
     * can't be read counts as holding unknown data. May keep duplicates that could have gone.
     */
    fun hasUnmodelledDataMissingFrom(c: Contact, keeper: Contact): Boolean {
        val mine = persons(c)
        if (mine.isEmpty()) return false // not a Google contact
        if (mine.any { it == null }) return true
        val theirs = persons(keeper).filterNotNull()
        for (p in mine.filterNotNull()) {
            if (!theirs.flatMapTo(HashSet()) { groupRefs(it) }.containsAll(groupRefs(p))) return true
            for ((key, value) in p) {
                if (key in PER_RECORD || key == "memberships") continue
                val pool = theirs.flatMap { entries(key, it[key]) }
                if (entries(key, value).any { it !in pool }) return true
            }
        }
        return false
    }

    /** Top-level person keys the model reads (or that only describe the record). */
    private val MODELLED_KEYS = MASK_ORDER.toSet() + PER_RECORD

    /**
     * What [c]'s Google person holds that a copy elsewhere won't get, readably: unmodelled keys
     * ("userDefined", "occupations"…), "starred", and organizations or names beyond the first.
     * Empty for a contact that isn't from Google.
     */
    fun unmodelledFields(c: Contact): List<String> {
        val p = persons(c).filterNotNull().firstOrNull() ?: return emptyList()
        val out = ArrayList<String>()
        for ((k, v) in p) {
            if (k in MODELLED_KEYS) continue
            if (v is JsonArray && v.isEmpty()) continue
            out += k
        }
        if (groupRefs(p).contains("contactGroups/starred")) out += "starred"
        if (orgEntries(p).size > 1) out += "more organizations"
        if (p.own("names").size > 1) out += "more names"
        return out
    }

    // ---- comparing ----

    private fun nameKey(n: Name) = listOf(n.prefix, n.first, n.middle, n.last, n.suffix, n.phoneticFirst, n.phoneticLast, n.display)
    private fun Org.key() = Triple(company, department, title)
    private fun isBirthday(t: Typed) = t.label.trim().equals("birthday", ignoreCase = true)

    /** Mask fields whose modelled content differs between [before] and [after]. */
    private fun changedFields(before: Contact, after: Contact): Set<String> {
        val out = HashSet<String>()
        if (nameKey(before.name) != nameKey(after.name)) out += "names"
        if (before.name.nickname != after.name.nickname) out += "nicknames"
        if (before.phones != after.phones) out += "phoneNumbers"
        if (before.emails != after.emails) out += "emailAddresses"
        if (before.addresses != after.addresses) out += "addresses"
        if (before.org.key() != after.org.key()) out += "organizations"
        if (before.websites != after.websites) out += "urls"
        if (before.events.filter(::isBirthday) != after.events.filter(::isBirthday)) out += "birthdays"
        if (before.events.filterNot(::isBirthday) != after.events.filterNot(::isBirthday)) out += "events"
        if (before.relations != after.relations) out += "relations"
        if (before.ims != after.ims) out += "imClients"
        if (before.notes != after.notes) out += "biographies"
        if (before.groups != after.groups) out += "memberships"
        return out
    }

    /** Comma-separated People API fields that differ, e.g. "organizations,memberships"; "" when nothing does. */
    fun updateMask(before: Contact, after: Contact): String {
        val changed = changedFields(before, after)
        return MASK_ORDER.filter { it in changed }.joinToString(",")
    }

    // ---- writing ----

    /**
     * Pairs original entries with wanted items (identical, then same [key], then same position),
     * returning the new list: paired entries unchanged when identical, otherwise rewritten from
     * the original via [write]; unpaired items written fresh; unpaired originals dropped.
     */
    private fun <T> merge(
        orig: List<JsonObject>, cur: List<T>, items: List<T>, key: (T) -> String, blank: (T) -> Boolean,
        write: (JsonObject?, T) -> JsonObject,
    ): List<JsonObject> {
        val rowOf = arrayOfNulls<Int>(items.size)
        val used = BooleanArray(orig.size)
        fun pass(pick: (Int, Int) -> Boolean) {
            for (i in items.indices) if (rowOf[i] == null) {
                val j = orig.indices.firstOrNull { !used[it] && pick(i, it) } ?: continue
                rowOf[i] = j; used[j] = true
            }
        }
        pass { i, j -> cur[j] == items[i] }
        pass { i, j -> !blank(items[i]) && key(cur[j]) == key(items[i]) }
        pass { i, j -> !blank(items[i]) && i == j }
        return items.indices.mapNotNull { i ->
            val j = rowOf[i]
            when {
                j != null && cur[j] == items[i] -> orig[j]
                blank(items[i]) -> null
                else -> write(j?.let(orig::get), items[i])
            }
        }
    }

    private fun digits(s: String) = buildString { for (ch in s) if (ch.isDigit()) append(Character.digit(ch, 10)) }

    private fun writePhone(base: JsonObject?, t: Typed) = edit(base) {
        put("value", t.value); put("type", PHONE.write(t.label)); drop("formattedType", "canonicalForm")
    }
    private fun writeEmail(base: JsonObject?, t: Typed) = edit(base) {
        put("value", t.value); put("type", HOME_WORK.write(t.label)); drop("formattedType")
    }
    private fun writeUrl(base: JsonObject?, t: Typed) = edit(base) {
        put("value", t.value); put("type", URL.write(t.label)); drop("formattedType")
    }
    private fun writeRelation(base: JsonObject?, t: Typed) = edit(base) {
        put("person", t.value); put("type", RELATION.write(t.label)); drop("formattedType")
    }
    private fun writeIm(base: JsonObject?, t: Typed) = edit(base) {
        put("username", t.value); put("protocol", IM.write(t.label)); drop("formattedProtocol", "formattedType")
    }
    private fun writeBirthday(base: JsonObject?, t: Typed) = edit(base) {
        val d = dateOf(t.value)
        if (d != null) { m["date"] = d; drop("text") } else { put("text", t.value); drop("date") }
    }
    private fun writeEvent(base: JsonObject?, t: Typed) = edit(base) {
        m["date"] = dateOf(t.value) ?: throw IllegalArgumentException("event date \"${t.value}\" is not a date Google accepts")
        put("type", EVENT.write(t.label)); drop("formattedType")
    }
    private fun writeAddress(base: JsonObject?, a: Address) = edit(base) {
        put("type", HOME_WORK.write(a.label))
        put("streetAddress", a.street); put("city", a.city); put("region", a.region); put("postalCode", a.postcode)
        put("country", a.country); put("poBox", a.poBox); put("extendedAddress", a.extended)
        // Google rebuilds these from the structured parts
        drop("formattedValue", "formattedType")
    }

    private fun Address.parts() = listOf(street, poBox, extended, city, region, postcode, country)

    /** System groups a re-created person may rejoin (they exist in every account). */
    private val SYSTEM_GROUPS = setOf(
        MY_CONTACTS, "contactGroups/starred", "contactGroups/friends", "contactGroups/family", "contactGroups/coworkers",
    )

    /** Keys that describe one person record (or are read-only) and never go into a new one. */
    private val NOT_FOR_NEW = setOf("resourceName", "etag", "metadata", "photos", "coverPhotos", "ageRange", "ageRanges")

    /**
     * A person to re-create [person] from, in the same account (undo of a delete or move): every
     * key the contact itself holds — userDefined, occupations, starred and other system
     * memberships, entries' unmodelled keys — minus what belongs to the old record (resource
     * name, etag, metadata, photos), entries from other sources (profile), entry source ids,
     * and user-group memberships (those are re-added by name, so a deleted group can't fail it).
     */
    fun seedFor(person: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in person) {
            if (k in NOT_FOR_NEW) continue
            if (k == "memberships") {
                out[k] = JsonArray((v as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { membershipName(it) in SYSTEM_GROUPS })
                continue
            }
            out[k] = if (v is JsonArray) JsonArray(v.mapNotNull { e ->
                if (e is JsonObject) {
                    if (!isOwn(e)) null
                    else {
                        val m = e.obj("metadata")
                        val primary = m?.get("primary")
                        val rest = LinkedHashMap<String, JsonElement>(e)
                        rest.remove("metadata")
                        if (primary != null) rest["metadata"] = JsonObject(mapOf("primary" to primary))
                        JsonObject(rest)
                    }
                } else e
            }) else v
        }
        return JsonObject(out)
    }

    /**
     * The person to send for [c]: its original (when [c] carries one for the same resource) with
     * the changed modelled fields rewritten; otherwise a new person (which also joins My Contacts),
     * built on [seed] (from [seedFor]) when given, so data the model lacks is re-created too.
     * [groupIds] maps group names to resource names and must hold every name in `c.groups`.
     */
    fun toPerson(c: Contact, groupIds: Map<String, String>, seed: JsonObject? = null): JsonObject {
        val orig = original(c)
        val base = orig ?: seed
        val p = base ?: JsonObject(emptyMap())
        val before = base?.let { toContact(it, groupIds.entries.associate { (k, v) -> v to k }) } ?: Contact()
        val changed = changedFields(before, c)
        val out = LinkedHashMap<String, JsonElement>(p)
        fun set(k: String, own: List<JsonObject>) {
            val list = own + p.foreign(k)
            if (list.isEmpty() && orig == null) out.remove(k) else out[k] = JsonArray(list)
        }

        if (orig == null) out.remove("resourceName")
        if (c.etag != null) out["etag"] = JsonPrimitive(c.etag) else if (orig == null) out.remove("etag")

        if ("names" in changed) {
            val names = p.own("names").toMutableList()
            val i = primaryIndex(names)
            val n = c.name
            val e = edit(names.getOrNull(i)) {
                put("honorificPrefix", n.prefix); put("givenName", n.first); put("middleName", n.middle)
                put("familyName", n.last); put("honorificSuffix", n.suffix)
                put("phoneticGivenName", n.phoneticFirst); put("phoneticFamilyName", n.phoneticLast)
                // derived by Google from the parts; a stale free-form name could override them
                drop("displayName", "displayNameLastFirst", "unstructuredName", "phoneticFullName")
                val structured = listOf(n.prefix, n.first, n.middle, n.last, n.suffix).any { it.isNotBlank() }
                if (!structured) put("unstructuredName", n.display)
            }
            val keep = e.keys.any { it != "metadata" }
            if (i < names.size) { if (keep) names[i] = e else names.removeAt(i) } else if (keep) names += e
            set("names", names)
        }
        if ("nicknames" in changed) {
            val nicks = p.own("nicknames").toMutableList()
            val nick = c.name.nickname
            if (nick.isEmpty()) { if (nicks.isNotEmpty()) nicks.removeAt(0) }
            else if (nicks.isEmpty()) nicks += edit(null) { put("value", nick) }
            else nicks[0] = edit(nicks[0]) { put("value", nick) }
            set("nicknames", nicks)
        }
        if ("phoneNumbers" in changed) {
            set("phoneNumbers", merge(p.own("phoneNumbers"), before.phones, c.phones, { digits(it.value) }, { it.value.isBlank() }, ::writePhone))
        }
        if ("emailAddresses" in changed) {
            set("emailAddresses", merge(p.own("emailAddresses"), before.emails, c.emails, { it.value.trim().lowercase() }, { it.value.isBlank() }, ::writeEmail))
        }
        if ("addresses" in changed) {
            set(
                "addresses",
                merge(p.own("addresses"), before.addresses, c.addresses, { it.parts().joinToString("\u0000") }, { a -> a.parts().all { it.isBlank() } }, ::writeAddress),
            )
        }
        if ("organizations" in changed) {
            val orgs = orgEntries(p).toMutableList()
            val i = primaryIndex(orgs)
            val e = edit(orgs.getOrNull(i)) {
                put("name", c.org.company); put("department", c.org.department); put("title", c.org.title)
            }
            // an entry left with nothing but bookkeeping keys is the user clearing the organization
            val keep = e.keys.any { it !in setOf("metadata", "type", "formattedType", "current") }
            if (i < orgs.size) { if (keep) orgs[i] = e else orgs.removeAt(i) } else if (keep) orgs += e
            set("organizations", orgs)
        }
        if ("urls" in changed) {
            set("urls", merge(p.own("urls"), before.websites, c.websites, { it.value.trim() }, { it.value.isBlank() }, ::writeUrl))
        }
        if ("birthdays" in changed) {
            set(
                "birthdays",
                merge(p.own("birthdays"), before.events.filter(::isBirthday), c.events.filter(::isBirthday), { it.value }, { it.value.isBlank() }, ::writeBirthday),
            )
        }
        if ("events" in changed) {
            set(
                "events",
                merge(p.own("events"), before.events.filterNot(::isBirthday), c.events.filterNot(::isBirthday), { it.value }, { it.value.isBlank() }, ::writeEvent),
            )
        }
        if ("relations" in changed) {
            set("relations", merge(p.own("relations"), before.relations, c.relations, { it.value.trim() }, { it.value.isBlank() }, ::writeRelation))
        }
        if ("imClients" in changed) {
            set("imClients", merge(p.own("imClients"), before.ims, c.ims, { it.value.trim() }, { it.value.isBlank() }, ::writeIm))
        }
        if ("biographies" in changed) {
            val bios = p.own("biographies").toMutableList()
            if (c.notes.isEmpty()) { if (bios.isNotEmpty()) bios.removeAt(0) }
            else if (bios.isEmpty()) bios += edit(null) { put("value", c.notes); put("contentType", "TEXT_PLAIN") }
            else bios[0] = edit(bios[0]) { put("value", c.notes) }
            set("biographies", bios)
        }
        if ("memberships" in changed || orig == null) {
            val userGroups = groupIds.values.toSet()
            val existing = p.list("memberships")
            // memberships the model doesn't show (system groups, domain, unknown groups) stay as they are
            val kept = existing.filter { e -> membershipName(e).let { it == null || it !in userGroups } }.toMutableList()
            if (orig == null && kept.none { membershipName(it) == MY_CONTACTS }) kept += membership(MY_CONTACTS)
            for (g in c.groups) {
                val rn = groupIds[g] ?: throw IllegalArgumentException("no Google group named \"$g\"")
                kept += existing.firstOrNull { membershipName(it) == rn } ?: membership(rn)
            }
            // all of the original memberships were considered above, so none are appended again
            out["memberships"] = JsonArray(kept.distinctBy { membershipName(it) ?: it.toString() })
        }
        return JsonObject(out)
    }

    private fun membership(rn: String) =
        JsonObject(mapOf("contactGroupMembership" to JsonObject(mapOf("contactGroupResourceName" to JsonPrimitive(rn)))))
}
