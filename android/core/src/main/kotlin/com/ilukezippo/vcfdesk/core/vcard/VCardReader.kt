package com.ilukezippo.vcfdesk.core.vcard

import com.ilukezippo.vcfdesk.core.model.Address
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Extra
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Org
import com.ilukezippo.vcfdesk.core.model.Typed
import java.nio.charset.Charset
import java.util.Base64

enum class VCardVersion { V21, V30, V40 }

/** Low-level vCard syntax shared by reader and writer (port of vcf_core.py helpers). */
internal object VCardSyntax {
    fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                val nx = s[i + 1]
                out.append(if (nx == 'n' || nx == 'N') '\n' else nx)
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\r\n", "\n").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")

    fun splitUnescaped(s: String, sep: Char = ';'): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                cur.append(s, i, i + 2)
                i += 2
                continue
            }
            if (ch == sep) {
                parts.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(ch)
            }
            i++
        }
        parts.add(cur.toString())
        return parts
    }

    /** Splits "NAME;params:value" at the first colon outside double quotes. */
    fun splitLine(line: String): Pair<String, String> {
        var inQuote = false
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') inQuote = !inQuote
            else if (ch == ':' && !inQuote) return line.substring(0, i) to line.substring(i + 1)
        }
        return line to ""
    }

    fun splitParams(s: String): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var inQuote = false
        for (ch in s) {
            if (ch == '"') {
                inQuote = !inQuote
                cur.append(ch)
            } else if (ch == ';' && !inQuote) {
                parts.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(ch)
            }
        }
        parts.add(cur.toString())
        return parts
    }

    /** Unfolds continuation lines and quoted-printable soft breaks. */
    fun logicalLines(text: String): List<String> {
        val raw = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val out = ArrayList<String>()
        var pendingQp = false
        for (line in raw) {
            val indented = line.startsWith(" ") || line.startsWith("\t")
            if (out.isNotEmpty() && pendingQp) {
                out[out.size - 1] = out.last().dropLast(1) + line
            } else if (indented && out.isNotEmpty()) {
                out[out.size - 1] = out.last() + line.substring(1)
            } else {
                out.add(line)
            }
            val cur = out.lastOrNull() ?: ""
            pendingQp = cur.endsWith("=") && splitLine(cur).first.uppercase().contains("QUOTED-PRINTABLE")
        }
        return out
    }

    /** Folds to 75 UTF-8 bytes per physical line; continuation lines start with one space. */
    fun fold(line: String): String {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var curLen = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val n = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (curLen + n > 75) {
                out.add(cur.toString())
                cur.setLength(0)
                cur.append(' ')
                curLen = 1
            }
            cur.appendCodePoint(cp)
            curLen += n
            i += Character.charCount(cp)
        }
        out.add(cur.toString())
        return out.joinToString("\r\n")
    }
}

/** Standard labels and their vCard TYPE sets (from vcf_core.py). */
internal object VCardLabels {
    val STANDARD = mapOf(
        "TEL" to listOf("Mobile", "Home", "Work", "Home fax", "Work fax", "Pager", "Other"),
        "EMAIL" to listOf("Home", "Work", "Other"),
        "ADR" to listOf("Home", "Work", "Other"),
    )
    val TYPES = mapOf(
        "TEL" to mapOf(
            "Mobile" to listOf("CELL"), "Home" to listOf("HOME", "VOICE"), "Work" to listOf("WORK", "VOICE"),
            "Home fax" to listOf("HOME", "FAX"), "Work fax" to listOf("WORK", "FAX"),
            "Pager" to listOf("PAGER"), "Other" to listOf("VOICE"),
        ),
        "EMAIL" to mapOf(
            "Home" to listOf("INTERNET", "HOME"), "Work" to listOf("INTERNET", "WORK"),
            "Other" to listOf("INTERNET"),
        ),
        "ADR" to mapOf("Home" to listOf("HOME"), "Work" to listOf("WORK"), "Other" to emptyList()),
    )
    val GENERIC_TYPES = mapOf("Home" to listOf("HOME"), "Work" to listOf("WORK"), "Other" to listOf("OTHER"))
    val AB = mapOf(
        "MOBILE" to "Mobile", "IPHONE" to "Mobile", "HOME" to "Home", "WORK" to "Work",
        "HOMEFAX" to "Home fax", "WORKFAX" to "Work fax", "PAGER" to "Pager", "OTHER" to "Other",
        "MAIN" to "Mobile",
    )
    val IM = mapOf(
        "X-AIM" to "AIM", "X-ICQ" to "ICQ", "X-JABBER" to "Jabber", "X-MSN" to "Windows Live",
        "X-YAHOO" to "Yahoo", "X-SKYPE-USERNAME" to "Skype", "X-SKYPE" to "Skype",
        "X-GOOGLE-TALK" to "Google Talk", "X-QQ" to "QQ",
    )
    val IM_WRITE = mapOf(
        "AIM" to "X-AIM", "ICQ" to "X-ICQ", "Jabber" to "X-JABBER", "Windows Live" to "X-MSN",
        "Yahoo" to "X-YAHOO", "Skype" to "X-SKYPE-USERNAME", "Google Talk" to "X-GOOGLE-TALK", "QQ" to "X-QQ",
    )
}

internal class Prop(
    val name: String,
    val group: String,
    val params: List<Pair<String, String>>,
    val value: String,
    val raw: String,
) {
    fun types(): List<String> = params.filter { it.first == "TYPE" }
        .flatMap { it.second.split(",") }.map { it.trim().trim('"').uppercase() }.filter { it.isNotEmpty() }

    fun param(key: String): String? = params.firstOrNull { it.first == key }?.second?.trim('"')
}

object VCardReader {
    private val ENCODING_WORDS = setOf("QUOTED-PRINTABLE", "BASE64", "8BIT", "7BIT", "B")
    private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")
    private val DATA_URI = Regex("data:image/[\\w.+-]+;base64,(.*)", RegexOption.DOT_MATCHES_ALL)

    fun read(text: String): List<Contact> {
        val out = ArrayList<Contact>()
        var cur: ArrayList<Prop>? = null
        for (line in VCardSyntax.logicalLines(text.removePrefix("﻿"))) {
            if (line.isBlank()) continue
            val (head, value) = VCardSyntax.splitLine(line)
            val key = head.trim().uppercase()
            if (key == "BEGIN" && value.trim().uppercase() == "VCARD") {
                cur = ArrayList()
                continue
            }
            if (key == "END" && value.trim().uppercase() == "VCARD") {
                cur?.let { out.add(build(it)) }
                cur = null
                continue
            }
            if (cur == null || key == "VERSION") continue
            cur.add(parseProp(line))
        }
        return out
    }

    private fun parseProp(line: String): Prop {
        val (namePart, rawValue) = VCardSyntax.splitLine(line)
        val bits = VCardSyntax.splitParams(namePart)
        var name = bits[0].trim()
        var group = ""
        val dot = name.lastIndexOf('.')
        if (dot >= 0) {
            group = name.substring(0, dot)
            name = name.substring(dot + 1)
        }
        var params = ArrayList<Pair<String, String>>()
        for (b0 in bits.drop(1)) {
            val b = b0.trim()
            if (b.isEmpty()) continue
            val eq = b.indexOf('=')
            when {
                eq >= 0 -> params.add(b.substring(0, eq).trim().uppercase() to b.substring(eq + 1).trim())
                b.uppercase() in ENCODING_WORDS -> params.add("ENCODING" to b.uppercase())
                else -> params.add("TYPE" to b)
            }
        }
        var value = rawValue
        val enc = params.firstOrNull { it.first == "ENCODING" }?.second?.uppercase() ?: ""
        if (enc == "QUOTED-PRINTABLE") {
            val cs = params.firstOrNull { it.first == "CHARSET" }?.second?.trim('"').orEmpty().ifEmpty { "utf-8" }
            val charset = try { Charset.forName(cs) } catch (e: Exception) { Charsets.UTF_8 }
            value = QuotedPrintable.decode(rawValue, charset)
            params = ArrayList(params.filter { it.first != "ENCODING" && it.first != "CHARSET" })
        }
        return Prop(name.uppercase(), group, params, value, line)
    }

    private fun text(p: Prop) = VCardSyntax.unescape(p.value).replace("\r\n", "\n")

    private fun cleanLabel(s: String) = s.replace("_\$!<", "").replace(">!\$_", "").trim()

    private fun normDate(s: String): String {
        val v = s.trim()
        return when {
            Regex("^\\d{8}$").matches(v) -> "${v.substring(0, 4)}-${v.substring(4, 6)}-${v.substring(6)}"
            Regex("^--\\d{4}$").matches(v) -> "--${v.substring(2, 4)}-${v.substring(4)}"
            else -> v
        }
    }

    private fun pref(p: Prop) = "PREF" in p.types() || p.param("PREF") != null

    /** Non-TYPE parameters kept verbatim (LANGUAGE, ADR LABEL=, X-APPLE-OMIT-YEAR, VALUE, ...). */
    private fun meta(p: Prop): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for ((k, v) in p.params) {
            if (k in setOf("TYPE", "ENCODING", "CHARSET", "PREF")) continue
            if (k == "VALUE" && p.name == "TEL") continue
            m[k] = v
        }
        return m
    }

    /** Label from TYPE for properties without a standard label set (URL, IMPP, RELATED). */
    private fun genericLabel(p: Prop): String {
        val t = p.types().filter { it != "PREF" }
        return when (val f = t.firstOrNull()) {
            null -> ""
            "HOME" -> "Home"
            "WORK" -> "Work"
            "OTHER" -> "Other"
            else -> f.lowercase()
        }
    }

    private fun standardLabel(p: Prop): String {
        val t = p.types().toSet()
        if (p.name == "TEL") {
            if ("FAX" in t) return if ("WORK" in t) "Work fax" else "Home fax"
            if ("CELL" in t || "MOBILE" in t || "IPHONE" in t) return "Mobile"
            if ("PAGER" in t) return "Pager"
        }
        if ("WORK" in t) return "Work"
        if ("HOME" in t) return "Home"
        if (p.name == "TEL" && (t - "PREF").isEmpty()) return "Mobile"
        return "Other"
    }

    private fun build(props: List<Prop>): Contact {
        var name = Name()
        var org = Org()
        var notes = ""
        var photo: ByteArray? = null
        val phones = ArrayList<Typed>()
        val emails = ArrayList<Typed>()
        val addresses = ArrayList<Address>()
        val websites = ArrayList<Typed>()
        val events = ArrayList<Typed>()
        val relations = ArrayList<Typed>()
        val ims = ArrayList<Typed>()
        val groups = LinkedHashSet<String>()
        val extras = ArrayList<Pair<Int, Extra>>()
        val seen = HashSet<String>()
        val labels = HashMap<String, Prop>()
        for (p in props) if (p.name == "X-ABLABEL" && p.group.isNotEmpty()) labels.putIfAbsent(p.group.lowercase(), p)
        val consumed = HashSet<String>()

        fun abLabel(p: Prop): String? {
            if (p.group.isEmpty()) return null
            val l = labels[p.group.lowercase()] ?: return null
            consumed.add(p.group.lowercase())
            return cleanLabel(text(l)).ifEmpty { null }
        }

        fun typedLabel(p: Prop): String {
            val ab = abLabel(p)
            if (ab != null) {
                val canon = VCardLabels.AB[ab.replace(Regex("[^A-Za-z]"), "").uppercase()]
                val std = VCardLabels.STANDARD[p.name]
                return if (canon != null && std != null && canon in std) canon else ab
            }
            return standardLabel(p)
        }

        props.forEachIndexed { idx, p ->
            fun extra() { extras.add(idx to Extra("vcard", p.raw)) }
            // single-valued fields: the first one is modelled, any further one is kept verbatim
            fun firstOf(key: String) = seen.add(key)
            when (p.name) {
                "FN" -> if (firstOf("FN")) name = name.copy(display = text(p)) else extra()
                "N" -> if (firstOf("N")) {
                    val n = VCardSyntax.splitUnescaped(p.value).map { VCardSyntax.unescape(it) } + List(5) { "" }
                    name = name.copy(last = n[0], first = n[1], middle = n[2], prefix = n[3], suffix = n[4])
                } else extra()
                "NICKNAME" -> if (firstOf("NICKNAME")) name = name.copy(nickname = text(p)) else extra()
                "X-PHONETIC-FIRST-NAME" ->
                    if (firstOf(p.name)) name = name.copy(phoneticFirst = text(p)) else extra()
                "X-PHONETIC-LAST-NAME" ->
                    if (firstOf(p.name)) name = name.copy(phoneticLast = text(p)) else extra()
                "ORG" -> if (firstOf("ORG")) {
                    val o = VCardSyntax.splitUnescaped(p.value).map { VCardSyntax.unescape(it) }
                    org = org.copy(
                        company = o[0], department = o.getOrElse(1) { "" },
                        units = o.drop(2).dropLastWhile { it.isEmpty() },
                    )
                } else extra()
                "TITLE" -> if (firstOf("TITLE")) org = org.copy(title = text(p)) else extra()
                "NOTE" -> if (firstOf("NOTE")) notes = text(p) else extra()
                "TEL" -> {
                    val v = text(p).trim().let { if (it.lowercase().startsWith("tel:")) it.substring(4) else it }
                    phones.add(Typed(typedLabel(p), v, pref(p), meta(p)))
                }
                "EMAIL" -> emails.add(Typed(typedLabel(p), text(p), pref(p), meta(p)))
                "ADR" -> {
                    val a = VCardSyntax.splitUnescaped(p.value).map { VCardSyntax.unescape(it) } + List(7) { "" }
                    addresses.add(
                        Address(typedLabel(p), a[2], a[3], a[4], a[5], a[6], a[0], a[1], pref(p), meta(p)),
                    )
                }
                "URL" -> websites.add(Typed(abLabel(p) ?: genericLabel(p), text(p), pref(p), meta(p)))
                "BDAY" -> events.add(Typed("birthday", normDate(text(p)), false, meta(p)))
                "ANNIVERSARY", "X-ANNIVERSARY" -> events.add(Typed("anniversary", normDate(text(p)), false, meta(p)))
                "X-ABDATE" -> {
                    val l = abLabel(p)?.let { if (it.equals("birthday", true) || it.equals("anniversary", true)) it.lowercase() else it }
                    events.add(Typed(l ?: "other", normDate(text(p)), false, meta(p)))
                }
                "X-ABRELATEDNAMES" -> relations.add(Typed(abLabel(p) ?: genericLabel(p), text(p), pref(p), meta(p)))
                "RELATED" -> relations.add(Typed(abLabel(p) ?: genericLabel(p), text(p), pref(p), meta(p)))
                "IMPP" -> ims.add(Typed(abLabel(p) ?: p.param("X-SERVICE-TYPE") ?: genericLabel(p), text(p), pref(p), meta(p)))
                "CATEGORIES" -> VCardSyntax.splitUnescaped(p.value, ',')
                    .map { VCardSyntax.unescape(it).trim() }.filter { it.isNotEmpty() }.forEach { groups.add(it) }
                "PHOTO" -> {
                    val bytes = if (photo == null) decodePhoto(p) else null
                    if (bytes != null) photo = bytes else extra()
                }
                "X-ABLABEL" -> if (p.group.isEmpty()) extra()
                else -> {
                    val im = VCardLabels.IM[p.name]
                    if (im != null) ims.add(Typed(im, text(p), pref(p), meta(p))) else extra()
                }
            }
        }
        // labels not attached to a modelled property stay verbatim
        props.forEachIndexed { idx, p ->
            if (p.name == "X-ABLABEL" && p.group.isNotEmpty() && p.group.lowercase() !in consumed) {
                extras.add(idx to Extra("vcard", p.raw))
            }
        }
        return Contact(
            name = name, phones = phones, emails = emails, addresses = addresses, org = org,
            websites = websites, events = events, relations = relations, ims = ims, notes = notes,
            groups = groups, photo = photo, extras = extras.sortedBy { it.first }.map { it.second },
        )
    }

    private fun decodePhoto(p: Prop): ByteArray? {
        val v = p.value.trim()
        val b64: String = if (v.startsWith("data:")) {
            DATA_URI.matchEntire(v)?.groupValues?.get(1) ?: return null
        } else {
            val enc = (p.param("ENCODING") ?: "").uppercase()
            val isData = enc == "B" || enc == "BASE64" || (!URI_SCHEME.containsMatchIn(v) && v.length > 64)
            if (!isData) return null
            v
        }
        return try {
            Base64.getMimeDecoder().decode(b64.replace(Regex("\\s+"), "")).takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
