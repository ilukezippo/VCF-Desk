package com.ilukezippo.vcfdesk.core.vcard

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

object QuotedPrintable {
    /** Decodes quoted-printable text; soft line breaks are dropped. */
    fun decode(s: String, charset: Charset = Charsets.UTF_8): String {
        val out = ByteArrayOutputStream()
        var i = 0
        var run = 0
        fun flush(end: Int) {
            if (end > run) out.write(s.substring(run, end).toByteArray(Charsets.UTF_8))
        }
        while (i < s.length) {
            if (s[i] != '=') {
                i++
                continue
            }
            flush(i)
            when {
                i + 1 >= s.length -> i += 1                                   // trailing soft break
                s[i + 1] == '\n' -> i += 2
                s[i + 1] == '\r' && i + 2 < s.length && s[i + 2] == '\n' -> i += 3
                i + 2 < s.length && Character.digit(s[i + 1], 16) >= 0 && Character.digit(s[i + 2], 16) >= 0 -> {
                    out.write(Character.digit(s[i + 1], 16) * 16 + Character.digit(s[i + 2], 16))
                    i += 3
                }
                else -> {
                    out.write('='.code)
                    i += 1
                }
            }
            run = i
        }
        flush(s.length)
        return String(out.toByteArray(), charset)
    }

    /** Quoted-printable (UTF-8) with soft line breaks, the way Android writes vCard 2.1. */
    fun encode(text: String): String {
        val lines = ArrayList<String>()
        val cur = StringBuilder()
        val bytes = text.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
        fun plain(v: Int) = (v in 33..126 && v != 61) || v == 32
        fun tokenOf(v: Int) = if (plain(v)) v.toChar().toString() else "=%02X".format(v)
        for ((i, b) in bytes.withIndex()) {
            val v = b.toInt() and 0xFF
            var tok = tokenOf(v)
            if (v == 32) {
                // A space at a line start, before a soft break or at the end would be eaten by readers.
                val nextLen = if (i + 1 < bytes.size) (bytes[i + 1].toInt() and 0xFF).let { if (it == 32) 3 else tokenOf(it).length } else 0
                if (cur.isEmpty() || cur.length + 1 > 74 || i + 1 == bytes.size || cur.length + 1 + nextLen > 74) tok = "=20"
            }
            if (cur.length + tok.length > 74) {
                lines.add("$cur=")
                cur.setLength(0)
            }
            cur.append(tok)
        }
        lines.add(cur.toString())
        return lines.joinToString("\r\n")
    }
}
