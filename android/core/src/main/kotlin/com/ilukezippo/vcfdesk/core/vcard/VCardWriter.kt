package com.ilukezippo.vcfdesk.core.vcard

import com.ilukezippo.vcfdesk.core.model.Contact
import java.util.Base64

object VCardWriter {
    fun write(contacts: List<Contact>, version: VCardVersion): String =
        contacts.joinToString("") { writeOne(it, version) }

    private class Ctx(val ver: VCardVersion, extras: List<String>) {
        val lines = ArrayList<String>()
        private val used = extras.mapNotNull { GROUP.find(it)?.groupValues?.get(1)?.lowercase() }.toSet()
        private var n = 0

        fun newGroup(): String {
            do { n++ } while ("item$n" in used)
            return "item$n"
        }

        private fun esc(s: String, structured: Boolean): String =
            if (ver == VCardVersion.V21) {
                val t = s.replace("\r\n", "\n").replace("\\", "\\\\")
                if (structured) t.replace(";", "\\;") else t
            } else {
                VCardSyntax.escape(s)
            }

        fun prop(
            name: String,
            parts: List<String>,
            types: List<String> = emptyList(),
            params: List<String> = emptyList(),
            group: String = "",
            preEscaped: Boolean = false,
        ) {
            val full = (if (group.isNotEmpty()) "$group." else "") + name
            val t = types.map { if (ver == VCardVersion.V40) it.lowercase() else it.uppercase() }.distinct()
            val value = parts.joinToString(";") { if (preEscaped) it else esc(it, parts.size > 1) }
            if (ver == VCardVersion.V21) {
                val head = full + t.joinToString("") { ";$it" } + params.joinToString("") { ";$it" }
                lines.add(
                    if (value.any { it.code > 126 || it == '\n' }) {
                        "$head;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:" + QuotedPrintable.encode(value)
                    } else {
                        "$head:$value"
                    },
                )
            } else {
                val ps = (if (t.isEmpty()) emptyList() else listOf("TYPE=" + t.joinToString(","))) + params
                lines.add(VCardSyntax.fold(full + ps.joinToString("") { ";$it" } + ":" + value))
            }
        }

        /** A property whose label is either a standard TYPE set or a custom itemN.X-ABLabel. */
        fun labelled(
            name: String,
            parts: List<String>,
            label: String,
            standardTypes: Map<String, List<String>>? = null,
            pref: Boolean = false,
            meta: Map<String, String> = emptyMap(),
        ) {
            // RFC 6350 has no TYPE=pref: vCard 4.0 carries the preference as PREF=1
            val v40 = ver == VCardVersion.V40
            val ps = meta.map { "${it.key}=${it.value}" } + (if (pref && v40) listOf("PREF=1") else emptyList())
            val prefType = if (pref && !v40) listOf("PREF") else emptyList()
            val std = standardTypes?.get(label)
            if (std != null) {
                prop(name, parts, types = (if (ver == VCardVersion.V40) std - "INTERNET" else std) + prefType, params = ps)
            } else if (label.isBlank()) {
                prop(name, parts, types = prefType, params = ps)
            } else {
                val g = newGroup()
                prop(name, parts, types = prefType, params = ps, group = g)
                prop("X-ABLabel", listOf(label), group = g)
            }
        }

        fun raw(line: String) {
            // Quoted-printable lines are never folded: a fold after "=" would read back as a soft break.
            val qp = VCardSyntax.splitLine(line).first.uppercase().contains("QUOTED-PRINTABLE")
            lines.add(if (ver == VCardVersion.V21 || qp) line else VCardSyntax.fold(line))
        }
    }

    private val GROUP = Regex("^([A-Za-z0-9-]+)\\.")

    private fun mime(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "PNG"
        bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "GIF"
        else -> "JPEG"
    }

    private fun writeOne(c: Contact, ver: VCardVersion): String {
        val vcardExtras = c.extras.filter { it.kind == "vcard" }.map { it.raw }
        val x = Ctx(ver, vcardExtras)
        x.lines.add("BEGIN:VCARD")
        x.lines.add("VERSION:" + when (ver) { VCardVersion.V21 -> "2.1"; VCardVersion.V30 -> "3.0"; VCardVersion.V40 -> "4.0" })
        val n = c.name
        val composed = listOf(n.prefix, n.first, n.middle, n.last, n.suffix)
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        x.prop("FN", listOf(n.display.trim().ifEmpty { composed }.ifEmpty { c.org.company.trim() }))
        x.prop("N", listOf(n.last, n.first, n.middle, n.prefix, n.suffix))
        if (n.nickname.isNotBlank()) x.prop("NICKNAME", listOf(n.nickname))
        if (n.phoneticFirst.isNotBlank()) x.prop("X-PHONETIC-FIRST-NAME", listOf(n.phoneticFirst))
        if (n.phoneticLast.isNotBlank()) x.prop("X-PHONETIC-LAST-NAME", listOf(n.phoneticLast))
        if (c.org.company.isNotBlank() || c.org.department.isNotBlank() || c.org.units.isNotEmpty()) {
            x.prop("ORG", listOf(c.org.company, c.org.department) + c.org.units)
        }
        if (c.org.title.isNotBlank()) x.prop("TITLE", listOf(c.org.title))
        c.photo?.takeIf { it.isNotEmpty() }?.let { bytes ->
            val typ = mime(bytes)
            val data = Base64.getEncoder().encodeToString(bytes)
            when (ver) {
                VCardVersion.V40 -> x.raw("PHOTO:data:image/${typ.lowercase()};base64,$data")
                VCardVersion.V21 -> x.raw(
                    "PHOTO;ENCODING=BASE64;$typ:" + data.chunked(72).joinToString("\r\n  ") + "\r\n",
                )
                VCardVersion.V30 -> x.raw("PHOTO;ENCODING=b;TYPE=$typ:$data")
            }
        }
        for (p in c.phones.filter { it.value.isNotBlank() }) {
            x.labelled("TEL", listOf(p.value), p.label.ifBlank { "Mobile" }, VCardLabels.TYPES.getValue("TEL"), p.pref, p.params)
        }
        for (e in c.emails.filter { it.value.isNotBlank() }) {
            x.labelled("EMAIL", listOf(e.value), e.label.ifBlank { "Other" }, VCardLabels.TYPES.getValue("EMAIL"), e.pref, e.params)
        }
        for (a in c.addresses.filter { listOf(it.street, it.city, it.region, it.postcode, it.country, it.poBox, it.extended).any { s -> s.isNotBlank() } }) {
            x.labelled(
                "ADR", listOf(a.poBox, a.extended, a.street, a.city, a.region, a.postcode, a.country),
                a.label.ifBlank { "Other" }, VCardLabels.TYPES.getValue("ADR"), a.pref, a.params,
            )
        }
        for (w in c.websites.filter { it.value.isNotBlank() }) x.labelled("URL", listOf(w.value), w.label, VCardLabels.GENERIC_TYPES, w.pref, w.params)
        var bdayDone = false
        for (e in c.events.filter { it.value.isNotBlank() }) {
            when {
                e.label.equals("birthday", true) && !bdayDone -> { bdayDone = true; x.prop("BDAY", listOf(e.value), params = e.params.map { "${it.key}=${it.value}" }) }
                e.label.equals("anniversary", true) ->
                    x.prop(
                        if (ver == VCardVersion.V40) "ANNIVERSARY" else "X-ANNIVERSARY", listOf(e.value),
                        params = e.params.map { "${it.key}=${it.value}" },
                    )
                else -> x.labelled("X-ABDATE", listOf(e.value), e.label.ifBlank { "other" }, meta = e.params)
            }
        }
        for (i in c.ims.filter { it.value.isNotBlank() }) {
            val prop = VCardLabels.IM_WRITE[i.label]
            if (prop != null && i.params.isEmpty() && !i.pref) {
                x.prop(prop, listOf(i.value))
            } else {
                // a label that came from X-SERVICE-TYPE is already carried by that parameter
                val label = if (i.params["X-SERVICE-TYPE"]?.trim('"') == i.label) "" else i.label
                x.labelled("IMPP", listOf(i.value), label, VCardLabels.GENERIC_TYPES, i.pref, i.params)
            }
        }
        for (r in c.relations.filter { it.value.isNotBlank() }) x.labelled("X-ABRELATEDNAMES", listOf(r.value), r.label, VCardLabels.GENERIC_TYPES, r.pref, r.params)
        if (c.notes.isNotBlank()) x.prop("NOTE", listOf(c.notes))
        if (c.groups.isNotEmpty()) {
            x.prop("CATEGORIES", listOf(c.groups.joinToString(",") { VCardSyntax.escape(it) }), preEscaped = true)
        }
        for (raw in vcardExtras) x.raw(raw)
        x.lines.add("END:VCARD")
        return x.lines.joinToString("\r\n") + "\r\n"
    }
}
