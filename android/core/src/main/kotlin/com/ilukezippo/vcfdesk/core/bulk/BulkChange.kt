package com.ilukezippo.vcfdesk.core.bulk

import com.ilukezippo.vcfdesk.core.model.Address
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.text.Names
import com.ilukezippo.vcfdesk.core.text.Phones

enum class TextField { COMPANY, DEPARTMENT, TITLE, NOTES, CITY, COUNTRY, PREFIX, SUFFIX, FIRST, MIDDLE, LAST, NICKNAME }

sealed interface BulkChange {
    data class SetField(val field: TextField, val value: String) : BulkChange
    data class AppendNotes(val text: String) : BulkChange
    data class FindReplace(val field: TextField, val find: String, val replace: String, val ignoreCase: Boolean = true) : BulkChange
    data class AddGroup(val group: String) : BulkChange
    data class RemoveGroup(val group: String) : BulkChange
    data object FirstMiddleOnly : BulkChange
    data class AddCountryCode(val code: String = "+965", val localLength: Int = 8) : BulkChange
}

data class FieldDiff(val label: String, val before: String, val after: String)
data class ContactDiff(val contact: Contact, val after: Contact, val changes: List<FieldDiff>)

object Bulk {
    fun apply(c: Contact, changes: List<BulkChange>): Contact = changes.fold(c) { acc, ch -> applyOne(acc, ch) }

    fun preview(cs: List<Contact>, changes: List<BulkChange>): List<ContactDiff> = cs.mapNotNull { c ->
        val after = apply(c, changes)
        if (after == c) null else ContactDiff(c, after, diff(c, after))
    }

    private fun get(c: Contact, f: TextField): String = when (f) {
        TextField.COMPANY -> c.org.company
        TextField.DEPARTMENT -> c.org.department
        TextField.TITLE -> c.org.title
        TextField.NOTES -> c.notes
        TextField.CITY -> c.addresses.joinToString(" / ") { it.city }
        TextField.COUNTRY -> c.addresses.joinToString(" / ") { it.country }
        TextField.PREFIX -> c.name.prefix
        TextField.SUFFIX -> c.name.suffix
        TextField.FIRST -> c.name.first
        TextField.MIDDLE -> c.name.middle
        TextField.LAST -> c.name.last
        TextField.NICKNAME -> c.name.nickname
    }

    private fun label(f: TextField) = when (f) {
        TextField.COMPANY -> "Company"; TextField.DEPARTMENT -> "Department"; TextField.TITLE -> "Job title"
        TextField.NOTES -> "Notes"; TextField.CITY -> "City"; TextField.COUNTRY -> "Country"
        TextField.PREFIX -> "Prefix"; TextField.SUFFIX -> "Suffix"; TextField.FIRST -> "First name"
        TextField.MIDDLE -> "Middle name"; TextField.LAST -> "Last name"; TextField.NICKNAME -> "Nickname"
    }

    private fun diff(a: Contact, b: Contact): List<FieldDiff> {
        val out = ArrayList<FieldDiff>()
        for (f in TextField.entries) {
            val x = get(a, f); val y = get(b, f)
            if (x != y) out.add(FieldDiff(label(f), x, y))
        }
        val ga = a.groups.joinToString(", "); val gb = b.groups.joinToString(", ")
        if (a.groups != b.groups) out.add(FieldDiff("Groups", ga, gb))
        if (a.phones.size == b.phones.size) {
            a.phones.indices.forEach { i ->
                if (a.phones[i].value != b.phones[i].value) out.add(FieldDiff("Phone", a.phones[i].value, b.phones[i].value))
            }
        }
        return out
    }

    private fun applyOne(c: Contact, ch: BulkChange): Contact = when (ch) {
        is BulkChange.SetField -> set(c, ch.field) { ch.value }
        is BulkChange.FindReplace ->
            if (ch.find.isEmpty()) c
            else set(c, ch.field, create = false) { it.replace(ch.find, ch.replace, ch.ignoreCase) }
        is BulkChange.AppendNotes ->
            if (ch.text.isEmpty()) c
            else c.copy(notes = if (c.notes.isBlank()) ch.text else c.notes.trimEnd() + "\n" + ch.text)
        is BulkChange.AddGroup -> if (ch.group.isBlank()) c else c.copy(groups = c.groups + ch.group.trim())
        is BulkChange.RemoveGroup -> c.copy(groups = c.groups - ch.group.trim())
        BulkChange.FirstMiddleOnly -> {
            if (c.name.middle.isBlank()) c
            else renamed(c, c.name.copy(middle = Names.firstMiddleOnly(c.name.middle)))
        }
        is BulkChange.AddCountryCode ->
            c.copy(phones = c.phones.map { it.copy(value = Phones.addCountryCode(it.value, ch.code, ch.localLength)) })
    }

    /** Applies a new name; refreshes display only if it used to be the auto-composed name. */
    private fun renamed(c: Contact, n: Name): Contact {
        val auto = c.name.display == Names.compose(c.name)
        return c.copy(name = if (auto) n.copy(display = Names.compose(n)) else n)
    }

    private fun set(c: Contact, f: TextField, create: Boolean = true, fn: (String) -> String): Contact = when (f) {
        TextField.COMPANY -> c.copy(org = c.org.copy(company = fn(c.org.company)))
        TextField.DEPARTMENT -> c.copy(org = c.org.copy(department = fn(c.org.department)))
        TextField.TITLE -> c.copy(org = c.org.copy(title = fn(c.org.title)))
        TextField.NOTES -> c.copy(notes = fn(c.notes))
        TextField.CITY -> addrs(c, create, fn, { it.city }) { a, v -> a.copy(city = v) }.let { list -> if (list == null) c else c.copy(addresses = list) }
        TextField.COUNTRY -> addrs(c, create, fn, { it.country }) { a, v -> a.copy(country = v) }.let { list -> if (list == null) c else c.copy(addresses = list) }
        TextField.PREFIX -> renamed(c, c.name.copy(prefix = fn(c.name.prefix)))
        TextField.SUFFIX -> renamed(c, c.name.copy(suffix = fn(c.name.suffix)))
        TextField.FIRST -> renamed(c, c.name.copy(first = fn(c.name.first)))
        TextField.MIDDLE -> renamed(c, c.name.copy(middle = fn(c.name.middle)))
        TextField.LAST -> renamed(c, c.name.copy(last = fn(c.name.last)))
        TextField.NICKNAME -> c.copy(name = c.name.copy(nickname = fn(c.name.nickname)))
    }

    // City/country are per-address: apply fn to each; create one "home" address when none and the new value is non-empty.
    private fun addrs(c: Contact, create: Boolean, fn: (String) -> String, cur: (Address) -> String, upd: (Address, String) -> Address): List<Address>? {
        if (c.addresses.isNotEmpty()) {
            return c.addresses.map { a -> upd(a, fn(cur(a))) }
        }
        if (!create) return null
        val v = fn("")
        return if (v.isEmpty()) null else listOf(upd(Address(label = "home"), v))
    }
}
