package com.ilukezippo.vcfdesk.core.table

import com.ilukezippo.vcfdesk.core.model.Address
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Org
import com.ilukezippo.vcfdesk.core.model.Typed
import com.ilukezippo.vcfdesk.core.text.Dates
import com.ilukezippo.vcfdesk.core.text.Names
import com.ilukezippo.vcfdesk.core.text.Phones
import java.util.Base64

object RowImport {
    private val phoneSplit = Regex("\\s*[/,;|]\\s*|\\n")
    private val groupSplit = Regex("[,;،]")

    /**
     * One contact per row that has any mapped value. [mapping] has one [Field] per column.
     * [oneMiddle] keeps only the first middle name (and builds the display name from the parts).
     */
    fun toContacts(t: Table, mapping: List<Field>, cc: String = "", oneMiddle: Boolean = false): List<Contact> {
        val out = ArrayList<Contact>()
        for (row in t.rows) {
            val vals = LinkedHashMap<Field, MutableList<Pair<String, String>>>()
            mapping.forEachIndexed { i, f ->
                val v = row.getOrNull(i)?.trim().orEmpty()
                if (f != Field.SKIP && v.isNotEmpty()) vals.getOrPut(f) { ArrayList() }.add((t.headers.getOrNull(i) ?: "") to v)
            }
            if (vals.isEmpty()) continue
            fun g(f: Field) = vals[f].orEmpty().joinToString(" ") { it.second }

            var first = g(Field.FIRST)
            var middle = g(Field.MIDDLE)
            var last = g(Field.LAST)
            val full = g(Field.FULL_NAME)
            if (full.isNotEmpty() && first.isEmpty() && last.isEmpty()) {
                val n = Names.split(full, oneMiddle)
                first = n.first; last = n.last
                if (middle.isEmpty()) middle = n.middle
            }
            if (oneMiddle) middle = Names.firstMiddleOnly(middle)

            val notes = vals[Field.NOTES].orEmpty()
            val note = notes.joinToString("\n") { (h, v) -> if (notes.size > 1 && h.isNotEmpty()) "$h: $v" else v }

            val phones = ArrayList<Typed>()
            for ((f, label) in listOf(Field.MOBILE to "Mobile", Field.PHONE_WORK to "Work", Field.PHONE_HOME to "Home", Field.PHONE_OTHER to "Other")) {
                for ((_, v) in vals[f].orEmpty()) for (part in v.split(phoneSplit)) {
                    if (part.isNotBlank()) phones.add(Typed(label, Phones.normalise(asciiDigits(part), cc)))
                }
            }
            val emails = ArrayList<Typed>()
            for ((f, label) in listOf(Field.EMAIL to "Home", Field.EMAIL_WORK to "Work")) {
                for ((_, v) in vals[f].orEmpty()) emails.add(Typed(label, v))
            }
            val adr = Address(
                label = "Home", street = g(Field.STREET), city = g(Field.CITY), region = g(Field.REGION),
                postcode = g(Field.POSTCODE), country = g(Field.COUNTRY),
            )
            val hasAdr = listOf(adr.street, adr.city, adr.region, adr.postcode, adr.country).any { it.isNotEmpty() }
            val bday = g(Field.BIRTHDAY)

            out.add(
                Contact(
                    name = Name(
                        prefix = g(Field.PREFIX), first = first, middle = middle, last = last, suffix = g(Field.SUFFIX),
                        nickname = g(Field.NICKNAME), display = if (oneMiddle) "" else full,
                    ),
                    phones = phones,
                    emails = emails,
                    addresses = if (hasAdr) listOf(adr) else emptyList(),
                    org = Org(company = g(Field.COMPANY), department = g(Field.DEPARTMENT), title = g(Field.TITLE)),
                    websites = vals[Field.WEBSITE].orEmpty().map { Typed("", it.second) },
                    events = if (bday.isNotEmpty()) listOf(Typed("birthday", Dates.normalise(bday))) else emptyList(),
                    notes = note,
                    groups = vals[Field.GROUPS].orEmpty().flatMap { it.second.split(groupSplit) }.map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet()),
                    photo = vals[Field.PHOTO]?.firstOrNull()?.let { photoBytes(it.second) },
                ),
            )
        }
        return out
    }

    /** Any Unicode decimal digit becomes its ASCII digit, so Arabic-Indic numbers normalise like Latin ones. */
    private fun asciiDigits(s: String): String =
        if (s.all { it.code < 128 }) s else buildString { for (ch in s) append(if (ch.isDigit()) ('0' + Character.digit(ch, 10)) else ch) }

    /** A base64 (optionally data: URL) cell that decodes to a JPEG/PNG/GIF; anything else (e.g. a file name) is ignored. */
    private fun photoBytes(cell: String): ByteArray? {
        var v = cell.trim()
        if (v.startsWith("data:", ignoreCase = true)) v = v.substringAfter(',', "")
        v = v.filterNot { it.isWhitespace() }.trimEnd('=')
        if (v.length < 8) return null
        val b = try { Base64.getDecoder().decode(v) } catch (_: IllegalArgumentException) { return null }
        val img = (b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) ||
            (b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) ||
            (b.size > 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte())
        return if (img) b else null
    }
}
