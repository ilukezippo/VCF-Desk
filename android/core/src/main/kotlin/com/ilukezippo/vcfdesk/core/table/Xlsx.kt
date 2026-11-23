package com.ilukezippo.vcfdesk.core.table

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

object Xlsx {
    // ---- reading -------------------------------------------------------------------------

    /** Every worksheet as a table (first row = headers), in workbook order. */
    fun sheets(bytes: ByteArray): Map<String, Table> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) files[e.name] = z.readBytes()
            }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { parse(it) }?.let { doc -> descendants(doc.documentElement, "si").map { siText(it) } } ?: emptyList()
        val wb = parse(files["xl/workbook.xml"] ?: return emptyMap())
        val rels = HashMap<String, String>()
        files["xl/_rels/workbook.xml.rels"]?.let { r ->
            descendants(parse(r).documentElement, "Relationship").forEach { rels[it.getAttribute("Id")] = it.getAttribute("Target") }
        }
        val out = LinkedHashMap<String, Table>()
        descendants(wb.documentElement, "sheet").forEachIndexed { i, sh ->
            val rid = (0 until sh.attributes.length).map { sh.attributes.item(it) }.firstOrNull { it.nodeName.endsWith(":id") }?.nodeValue
            val target = rels[rid] ?: "worksheets/sheet${i + 1}.xml"
            val path = normalise(if (target.startsWith("/")) target.trimStart('/') else "xl/$target")
            val data = files[path] ?: return@forEachIndexed
            val rows = ArrayList<List<String>>()
            for (row in descendants(parse(data).documentElement, "row")) {
                val cells = HashMap<Int, String>()
                var next = 0
                for (c in descendants(row, "c")) {
                    val ref = c.getAttribute("r")
                    val idx = if (ref.isNotEmpty()) colIndex(ref) else next
                    cells[idx] = cellValue(c, shared).trim()
                    next = idx + 1
                }
                rows.add(if (cells.isEmpty()) emptyList() else List(cells.keys.max() + 1) { cells[it] ?: "" })
            }
            while (rows.isNotEmpty() && rows.last().none { it.isNotEmpty() }) rows.removeAt(rows.size - 1)
            val name = sh.getAttribute("name").ifEmpty { "Sheet${i + 1}" }
            out[name] = if (rows.isEmpty()) Table(emptyList(), emptyList()) else Table(rows[0], rows.drop(1))
        }
        return out
    }

    private fun parse(b: ByteArray): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance()
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        f.setFeature("http://xml.org/sax/features/external-general-entities", false)
        return f.newDocumentBuilder().parse(ByteArrayInputStream(b))
    }

    private fun local(n: Node) = (n.nodeName).substringAfter(':')

    private fun descendants(root: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(n: Node) {
            var c = n.firstChild
            while (c != null) {
                if (c is Element) { if (local(c) == name) out.add(c); walk(c) }
                c = c.nextSibling
            }
        }
        walk(root)
        return out
    }

    private fun siText(si: Element): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            var c = n.firstChild
            while (c != null) {
                if (c is Element) {
                    val t = local(c)
                    if (t == "rPh") { c = c.nextSibling; continue }
                    if (t == "t") sb.append(c.textContent)
                    walk(c)
                }
                c = c.nextSibling
            }
        }
        walk(si)
        return sb.toString()
    }

    private fun cellValue(c: Element, shared: List<String>): String {
        val t = c.getAttribute("t")
        if (t == "inlineStr") return descendants(c, "is").firstOrNull()?.let { siText(it) } ?: ""
        val v = descendants(c, "v").firstOrNull() ?: return ""
        val s = v.textContent
        return when (t) {
            "s" -> s.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
            "b" -> if (s == "1") "TRUE" else "FALSE"
            "str", "e" -> s
            else -> {
                val d = s.trim().toDoubleOrNull()
                if (d != null && d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else s
            }
        }
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break }
        return n - 1
    }

    private fun normalise(p: String): String {
        val out = ArrayList<String>()
        for (part in p.replace('\\', '/').split('/')) when (part) {
            "", "." -> {}
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out.add(part)
        }
        return out.joinToString("/")
    }

    // ---- writing -------------------------------------------------------------------------

    private fun xml(s: String): String =
        s.replace(Regex("[\u0000-\u0008\u000b\u000c\u000e-\u001f]"), "")
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun col(i0: Int): String {
        var i = i0 + 1
        val sb = StringBuilder()
        while (i > 0) { val r = (i - 1) % 26; sb.insert(0, 'A' + r); i = (i - 1) / 26 }
        return sb.toString()
    }

    /** A plain workbook: bold header row, frozen first row, filter, column widths, optional right-to-left sheet. */
    fun write(t: Table, sheet: String = "Contacts", rtl: Boolean = false): ByteArray {
        val headers = t.headers
        val rows = t.rows
        val widths = headers.indices.map { i ->
            minOf(60, maxOf(10, headers[i].length + 2, rows.take(500).maxOfOrNull { r -> if (i < r.size) r[i].length + 2 else 0 } ?: 0))
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\"${if (rtl) " rightToLeft=\"1\"" else ""}><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        sb.append("<cols>")
        widths.forEachIndexed { i, w -> sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
        sb.append("</cols><sheetData>")
        (listOf(headers) + rows).forEachIndexed { ri0, r ->
            val ri = ri0 + 1
            sb.append("<row r=\"$ri\">")
            r.forEachIndexed { ci, v ->
                if (v.isNotEmpty()) {
                    sb.append("<c r=\"${col(ci)}$ri\" t=\"inlineStr\" s=\"${if (ri == 1) 1 else 2}\"><is><t xml:space=\"preserve\">${xml(v)}</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        if (headers.isNotEmpty()) sb.append("<autoFilter ref=\"A1:${col(headers.size - 1)}${rows.size + 1}\"/>")
        sb.append("</worksheet>")

        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF4F5BD5\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>" +
            "<xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"><alignment wrapText=\"0\"/></xf></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"
        val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        val relNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val files = linkedMapOf(
            "[Content_Types].xml" to head + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>",
            "_rels/.rels" to head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$relNs/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
            "xl/workbook.xml" to head + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"$relNs\">" +
                "<sheets><sheet name=\"${xml(sheet)}\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
                "<definedNames><definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"0\" hidden=\"1\">'${xml(sheet)}'!\$A\$1:\$${col(maxOf(0, headers.size - 1))}\$${rows.size + 1}</definedName></definedNames></workbook>",
            "xl/_rels/workbook.xml.rels" to head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$relNs/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"$relNs/styles\" Target=\"styles.xml\"/></Relationships>",
            "xl/worksheets/sheet1.xml" to sb.toString(),
            "xl/styles.xml" to styles,
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, d) in files) { z.putNextEntry(ZipEntry(n)); z.write(d.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        }
        return bos.toByteArray()
    }
}
