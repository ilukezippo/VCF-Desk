package com.ilukezippo.vcfdesk.core.table

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A header row plus data rows; rows may be ragged. */
data class Table(val headers: List<String>, val rows: List<List<String>>)

object Csv {
    private val delimiters = charArrayOf(',', ';', '\t', '|')

    fun read(bytes: ByteArray): Table {
        val text = decode(bytes)
        val delim = detectDelimiter(text)
        val rows = parse(text, delim).map { r -> r.map { it.trim() } }.toMutableList()
        while (rows.isNotEmpty() && rows.last().none { it.isNotEmpty() }) rows.removeAt(rows.size - 1)
        if (rows.isEmpty()) return Table(emptyList(), emptyList())
        return Table(rows[0], rows.drop(1))
    }

    /** UTF-8 with BOM (Excel opens it as Unicode), CRLF line ends, cells quoted when they need it. */
    fun write(t: Table): ByteArray {
        val sb = StringBuilder("﻿")
        fun row(r: List<String>) {
            sb.append(r.joinToString(",") { quote(it) }).append("\r\n")
        }
        row(t.headers)
        t.rows.forEach { row(it) }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun quote(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun decode(b: ByteArray): String {
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (b.size >= 3 && u(0) == 0xEF && u(1) == 0xBB && u(2) == 0xBF) return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && u(0) == 0xFF && u(1) == 0xFE) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && u(0) == 0xFE && u(1) == 0xFF) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
        } catch (_: CharacterCodingException) {
            String(b, charset("windows-1256"))
        }
    }

    /** The delimiter that occurs most often (outside quotes) in the first record; comma when none occurs. */
    private fun detectDelimiter(text: String): Char {
        val counts = IntArray(delimiters.size)
        var inQ = false
        for (ch in text.take(4096)) {
            if (ch == '"') inQ = !inQ
            else if (!inQ) {
                if (ch == '\n' || ch == '\r') break
                val i = delimiters.indexOf(ch)
                if (i >= 0) counts[i]++
            }
        }
        val best = counts.indices.maxByOrNull { counts[it] }!!
        return if (counts[best] == 0) ',' else delimiters[best]
    }

    private fun parse(text: String, d: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQ = false
        var pending = false // something was read for the current record
        var i = 0
        fun endCell() { row.add(cell.toString()); cell.setLength(0) }
        fun endRow() { endCell(); rows.add(row); row = ArrayList(); pending = false }
        while (i < text.length) {
            val ch = text[i]
            if (inQ) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else inQ = false
                } else cell.append(ch)
            } else when {
                ch == '"' && cell.isEmpty() -> { inQ = true; pending = true }
                ch == d -> { endCell(); pending = true }
                ch == '\r' || ch == '\n' -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    if (pending || cell.isNotEmpty()) endRow() else rows.add(emptyList())
                }
                else -> { cell.append(ch); pending = true }
            }
            i++
        }
        if (pending || cell.isNotEmpty()) endRow()
        return rows
    }
}
