package com.ilukezippo.vcfdesk.core.export

import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Typed
import com.ilukezippo.vcfdesk.core.table.Csv
import com.ilukezippo.vcfdesk.core.table.Table
import com.ilukezippo.vcfdesk.core.table.Xlsx
import com.ilukezippo.vcfdesk.core.text.Names
import com.ilukezippo.vcfdesk.core.vcard.VCardVersion
import com.ilukezippo.vcfdesk.core.vcard.VCardWriter
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val ext: String) {
    ANDROID_VCF("vcf"), IPHONE_VCF("vcf"), VCARD4("vcf"), GOOGLE_CSV("csv"), OUTLOOK_CSV("csv"),
    EXCEL("xlsx"), CSV("csv"), SPLIT_VCF_ZIP("zip"),
}

object Exporters {
    /** Excel refuses cells longer than this, so a bigger photo is left out of the .xlsx (never out of CSV/vCard). */
    private const val EXCEL_CELL_LIMIT = 32767

    /** How many contacts' photos [export] to [ExportFormat.EXCEL] leaves blank because the cell would be too long. */
    fun excelPhotosOmitted(cs: List<Contact>): Int =
        cs.count { c -> c.photo?.takeIf { it.isNotEmpty() }?.let { Base64.getEncoder().encodeToString(it).length > EXCEL_CELL_LIMIT } == true }

    fun export(cs: List<Contact>, f: ExportFormat): ByteArray = when (f) {
        ExportFormat.ANDROID_VCF, ExportFormat.IPHONE_VCF -> VCardWriter.write(cs, VCardVersion.V30).toByteArray(Charsets.UTF_8)
        ExportFormat.VCARD4 -> VCardWriter.write(cs, VCardVersion.V40).toByteArray(Charsets.UTF_8)
        ExportFormat.GOOGLE_CSV -> Csv.write(googleTable(cs))
        ExportFormat.OUTLOOK_CSV -> Csv.write(outlookTable(cs))
        ExportFormat.CSV -> Csv.write(contactTable(cs, Int.MAX_VALUE))
        ExportFormat.EXCEL -> Xlsx.write(contactTable(cs, EXCEL_CELL_LIMIT), rtl = Names.isMostlyArabic(cs.map { displayName(it) }))
        ExportFormat.SPLIT_VCF_ZIP -> splitZip(cs)
    }

    internal fun displayName(c: Contact): String =
        c.name.display.trim().ifEmpty { Names.compose(c.name) }
            .ifEmpty { c.org.company.trim() }
            .ifEmpty { c.emails.firstOrNull { it.value.isNotBlank() }?.value.orEmpty() }
            .ifEmpty { c.phones.firstOrNull { it.value.isNotBlank() }?.value.orEmpty() }

    internal fun safeFilename(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), " ").trim(' ', '.').take(80)

    private fun splitZip(cs: List<Contact>): ByteArray {
        val bos = ByteArrayOutputStream()
        StreamWriter(ExportFormat.SPLIT_VCF_ZIP, bos).use { it.add(cs) }
        return bos.toByteArray()
    }

    /** Formats that can be written chunk by chunk, so a big photo-heavy book never has to fit in memory. */
    fun isStreamable(f: ExportFormat): Boolean = when (f) {
        ExportFormat.ANDROID_VCF, ExportFormat.IPHONE_VCF, ExportFormat.VCARD4, ExportFormat.SPLIT_VCF_ZIP -> true
        else -> false
    }

    /**
     * Writes a streamable format to [out] in chunks: call [add] for each batch, then [close].
     * The bytes are identical to [export] of all the contacts at once.
     */
    class StreamWriter(private val f: ExportFormat, private val out: java.io.OutputStream) : java.io.Closeable {
        init { require(isStreamable(f)) { "$f can't be streamed" } }
        private val zip = if (f == ExportFormat.SPLIT_VCF_ZIP) ZipOutputStream(out) else null
        private val used = HashSet<String>()

        fun add(cs: List<Contact>) {
            val z = zip
            if (z == null) {
                val ver = if (f == ExportFormat.VCARD4) VCardVersion.V40 else VCardVersion.V30
                out.write(VCardWriter.write(cs, ver).toByteArray(Charsets.UTF_8))
                return
            }
            for (c in cs) {
                val base = safeFilename(displayName(c)).ifEmpty { "contact" }
                var name = base
                var n = 2
                while (name.lowercase() in used) { name = "$base ($n)"; n++ }
                used.add(name.lowercase())
                z.putNextEntry(ZipEntry("$name.vcf"))
                z.write(VCardWriter.write(listOf(c), VCardVersion.V30).toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }

        override fun close() {
            try {
                zip?.finish()
                out.flush()
            } finally {
                out.close()
            }
        }
    }

    // ---- generic table (CSV / Excel) -----------------------------------------------------------

    private val TABLE_HEADERS = listOf(
        "Display name", "First name", "Middle name", "Last name", "Prefix", "Suffix", "Company", "Department", "Job title",
        "Mobile", "Home phone", "Work phone", "Other phones", "Email", "Work email", "Other emails", "Street", "City",
        "State / Region", "Postal code", "Country", "Website", "Birthday", "Notes", "Groups",
    )

    private fun birthday(c: Contact) = c.events.firstOrNull { it.label.equals("birthday", true) }?.value.orEmpty()

    /** [photoLimit]: longest base64 photo cell to write; a Photo column appears only when some contact has a photo. */
    private fun contactTable(cs: List<Contact>, photoLimit: Int): Table {
        val photos = cs.map { c -> c.photo?.takeIf { it.isNotEmpty() }?.let { Base64.getEncoder().encodeToString(it) }?.takeIf { it.length <= photoLimit } ?: "" }
        val withPhoto = cs.any { it.photo?.isNotEmpty() == true }
        val rows = cs.mapIndexed { i, c ->
            val phones = mutableMapOf("Mobile" to ArrayList<String>(), "Home" to ArrayList(), "Work" to ArrayList(), "Other" to ArrayList())
            for (p in c.phones) phones.getValue(if (p.label in phones) p.label else "Other").add(p.value)
            val emails = mutableMapOf("Home" to ArrayList<String>(), "Work" to ArrayList(), "Other" to ArrayList())
            for (e in c.emails) emails.getValue(if (e.label in emails) e.label else "Other").add(e.value)
            val plain = emails.getValue("Home") + emails.getValue("Other")
            val a = c.addresses.firstOrNull()
            val j = { l: List<String> -> l.joinToString(" / ") }
            val row = listOf(
                displayName(c), c.name.first, c.name.middle, c.name.last, c.name.prefix, c.name.suffix,
                c.org.company, c.org.department, c.org.title,
                j(phones.getValue("Mobile")), j(phones.getValue("Home")), j(phones.getValue("Work")), j(phones.getValue("Other")),
                plain.firstOrNull().orEmpty(), j(emails.getValue("Work")), j(plain.drop(1)),
                a?.street.orEmpty(), a?.city.orEmpty(), a?.region.orEmpty(), a?.postcode.orEmpty(), a?.country.orEmpty(),
                j(c.websites.map { it.value }), birthday(c), c.notes.replace("\r\n", "\n"), c.groups.joinToString(","),
            )
            if (withPhoto) row + photos[i] else row
        }
        return Table(if (withPhoto) TABLE_HEADERS + "Photo" else TABLE_HEADERS, rows)
    }

    // ---- Google CSV ------------------------------------------------------------------------------

    private val GOOGLE_PHONE_LABEL = mapOf(
        "Mobile" to "Mobile", "Home" to "Home", "Work" to "Work", "Home fax" to "Home Fax",
        "Work fax" to "Work Fax", "Pager" to "Pager", "Other" to "Other",
    )

    private fun googleTable(cs: List<Contact>): Table {
        val maxE = maxOf(1, cs.maxOfOrNull { it.emails.size } ?: 0)
        val maxP = maxOf(1, cs.maxOfOrNull { it.phones.size } ?: 0)
        val maxA = maxOf(1, cs.maxOfOrNull { it.addresses.size } ?: 0)
        val maxW = maxOf(1, cs.maxOfOrNull { it.websites.size } ?: 0)
        val h = ArrayList(
            listOf(
                "First Name", "Middle Name", "Last Name", "Name Prefix", "Name Suffix", "Nickname", "File As",
                "Organization Name", "Organization Title", "Organization Department", "Birthday", "Notes", "Labels",
            ),
        )
        for (i in 1..maxE) h += listOf("E-mail $i - Label", "E-mail $i - Value")
        for (i in 1..maxP) h += listOf("Phone $i - Label", "Phone $i - Value")
        for (i in 1..maxA) h += listOf("Label", "Formatted", "Street", "City", "PO Box", "Region", "Postal Code", "Country", "Extended Address").map { "Address $i - $it" }
        for (i in 1..maxW) h += listOf("Website $i - Label", "Website $i - Value")
        val rows = cs.map { c ->
            val n = c.name
            val r = ArrayList(
                listOf(
                    n.first, n.middle, n.last, n.prefix, n.suffix, n.nickname, "", c.org.company, c.org.title,
                    c.org.department, birthday(c), c.notes.replace("\r\n", "\n"),
                    (listOf("* myContacts") + c.groups.map { it.trim() }.filter { it.isNotEmpty() }).joinToString(" ::: "),
                ),
            )
            for (i in 0 until maxE) r += c.emails.getOrNull(i)?.let { listOf(it.label, it.value) } ?: listOf("", "")
            for (i in 0 until maxP) r += c.phones.getOrNull(i)?.let { listOf(GOOGLE_PHONE_LABEL[it.label] ?: "Other", it.value) } ?: listOf("", "")
            for (i in 0 until maxA) {
                val a = c.addresses.getOrNull(i)
                if (a == null) r += List(9) { "" } else {
                    val fmt = listOf(a.street, listOf(a.city, a.region, a.postcode).filter { it.isNotEmpty() }.joinToString(" "), a.country)
                        .filter { it.isNotEmpty() }.joinToString("\n")
                    r += listOf(a.label, fmt, a.street, a.city, a.poBox, a.region, a.postcode, a.country, a.extended)
                }
            }
            for (i in 0 until maxW) r += c.websites.getOrNull(i)?.let { listOf("", it.value) } ?: listOf("", "")
            r
        }
        return Table(h, rows)
    }

    // ---- Outlook CSV -----------------------------------------------------------------------------

    private val OUTLOOK_HEADERS = listOf(
        "First Name", "Middle Name", "Last Name", "Title", "Suffix", "Company", "Department", "Job Title",
        "Business Street", "Business City", "Business State", "Business Postal Code", "Business Country/Region",
        "Home Street", "Home City", "Home State", "Home Postal Code", "Home Country/Region", "Business Fax",
        "Business Phone", "Business Phone 2", "Home Fax", "Home Phone", "Home Phone 2", "Mobile Phone", "Other Phone",
        "Pager", "E-mail Address", "E-mail Display Name", "E-mail 2 Address", "E-mail 3 Address", "Birthday", "Notes",
        "Web Page", "Categories",
    )

    private val OUTLOOK_SLOTS = mapOf(
        "Mobile" to listOf("Mobile Phone"), "Home" to listOf("Home Phone", "Home Phone 2"),
        "Work" to listOf("Business Phone", "Business Phone 2"), "Home fax" to listOf("Home Fax"),
        "Work fax" to listOf("Business Fax"), "Pager" to listOf("Pager"), "Other" to listOf("Other Phone"),
    )

    private fun outlookTable(cs: List<Contact>): Table {
        val rows = cs.map { c ->
            val r = OUTLOOK_HEADERS.associateWith { "" }.toMutableMap()
            r["First Name"] = c.name.first; r["Middle Name"] = c.name.middle; r["Last Name"] = c.name.last
            r["Title"] = c.name.prefix; r["Suffix"] = c.name.suffix
            r["Company"] = c.org.company; r["Department"] = c.org.department; r["Job Title"] = c.org.title
            r["Notes"] = c.notes.replace("\r\n", "\n")
            r["Web Page"] = c.websites.firstOrNull()?.value.orEmpty()
            r["Categories"] = c.groups.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(";")
            Regex("(\\d{4})-(\\d{2})-(\\d{2})").matchEntire(birthday(c))?.let { m ->
                val (y, mo, d) = m.destructured
                r["Birthday"] = "${mo.toInt()}/${d.toInt()}/$y"
            }
            for (p: Typed in c.phones) {
                for (col in (OUTLOOK_SLOTS[p.label] ?: emptyList()) + "Other Phone") {
                    if (r.getValue(col).isEmpty()) { r[col] = p.value; break }
                }
            }
            listOf("E-mail Address", "E-mail 2 Address", "E-mail 3 Address").forEachIndexed { i, k -> c.emails.getOrNull(i)?.let { r[k] = it.value } }
            c.emails.firstOrNull()?.let { r["E-mail Display Name"] = "${displayName(c).ifEmpty { "(No name)" }} (${it.value})" }
            for (a in c.addresses) {
                val pre = if (a.label == "Work") "Business" else "Home"
                if (r.getValue("$pre Street").isEmpty()) {
                    r["$pre Street"] = a.street; r["$pre City"] = a.city; r["$pre State"] = a.region
                    r["$pre Postal Code"] = a.postcode; r["$pre Country/Region"] = a.country
                }
            }
            OUTLOOK_HEADERS.map { r.getValue(it) }
        }
        return Table(OUTLOOK_HEADERS, rows)
    }
}
