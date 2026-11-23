package com.ilukezippo.vcfdesk.core.text

import com.ilukezippo.vcfdesk.core.model.Name

object Names {
    private val joiners = setOf("عبد", "ابو", "أبو", "بو", "بن", "ابن", "abd", "abdul", "abu", "bin", "al", "el")

    fun compose(n: Name): String =
        listOf(n.prefix, n.first, n.middle, n.last, n.suffix).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /** Splits into words, joining "عبد الله", "بن سعد", "Al Sabah" etc. into one part. */
    internal fun words(s: String): List<String> {
        val w = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < w.size) {
            if (w[i].lowercase() in joiners && i + 1 < w.size) { out.add(w[i] + " " + w[i + 1]); i += 2 }
            else { out.add(w[i]); i++ }
        }
        return out
    }

    fun split(full: String, oneMiddle: Boolean = false): Name {
        val m = words(full)
        return when {
            m.isEmpty() -> Name()
            m.size == 1 -> Name(first = m[0])
            m.size == 2 -> Name(first = m[0], last = m[1])
            else -> Name(first = m[0], middle = if (oneMiddle) m[1] else m.subList(1, m.size - 1).joinToString(" "), last = m.last())
        }
    }

    fun firstMiddleOnly(middle: String): String = words(middle).firstOrNull() ?: ""

    /** True when more than half of the first 200 texts contain Arabic letters. */
    fun isMostlyArabic(texts: List<String>): Boolean {
        val s = texts.take(200)
        return s.count { t -> t.any { it in '؀'..'ۿ' } } > s.size / 2.0
    }
}
