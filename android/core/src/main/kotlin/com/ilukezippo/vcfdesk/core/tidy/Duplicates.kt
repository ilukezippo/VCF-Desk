package com.ilukezippo.vcfdesk.core.tidy

import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Address
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Typed

/** Exact-duplicate finding, mirroring `duplicate_key` on desktop. */
object Duplicates {

    private data class Key(
        val account: AccountTag?,
        val name: String,
        val phones: Set<String>,
        val emails: Set<String>,
    )

    /** Groups of two or more contacts with the same key, each within one account. */
    fun groups(cs: List<Contact>): List<List<Contact>> {
        val byKey = LinkedHashMap<Key, MutableList<Contact>>()
        for (c in cs) {
            val k = keyOf(c)
            if (k.name.isEmpty() && k.phones.isEmpty() && k.emails.isEmpty()) continue
            byKey.getOrPut(k) { mutableListOf() }.add(c)
        }
        return byKey.values.filter { it.size > 1 }
    }

    /** The member with the most non-empty fields (a photo counts as 3); ties go to the earliest. */
    fun keeper(group: List<Contact>): Contact =
        group.reduce { best, c -> if (filled(c) > filled(best)) c else best }

    private fun keyOf(c: Contact): Key = Key(
        account = c.account,
        name = c.name.display.trim().lowercase(),
        phones = c.phones.map { phoneKey(it.value) }.filter { it.isNotEmpty() }.toSortedSet(),
        emails = c.emails.map { it.value.trim().lowercase() }.filter { it.isNotEmpty() }.toSortedSet(),
    )

    /**
     * Every Unicode decimal digit mapped to its ASCII value, last 9 kept: the same rule as
     * `duplicate_key` on desktop, so Arabic-Indic and ASCII forms of one number match.
     */
    private fun phoneKey(v: String): String {
        val digits = buildString {
            for (ch in v) if (ch.isDigit()) append(Character.digit(ch, 10))
        }
        return digits.takeLast(9)
    }

    private fun filled(c: Contact): Int {
        val n = c.name
        val names = listOf(n.prefix, n.first, n.middle, n.last, n.suffix,
            n.phoneticFirst, n.phoneticLast, n.nickname)
        val org = listOf(c.org.company, c.org.department, c.org.title) + c.org.units
        var count = names.count { it.isNotBlank() } + org.count { it.isNotBlank() }
        count += listOf(c.phones, c.emails, c.websites, c.events, c.relations, c.ims)
            .sumOf { list: List<Typed> -> list.count { it.value.isNotBlank() } }
        count += c.addresses.count(::hasAddressText)
        if (c.notes.isNotBlank()) count++
        count += c.groups.size
        if (c.photo?.isNotEmpty() == true) count += 3
        return count
    }

    private fun hasAddressText(a: Address): Boolean =
        listOf(a.street, a.city, a.region, a.postcode, a.country, a.poBox, a.extended).any { it.isNotBlank() }
}
