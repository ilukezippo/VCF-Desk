package com.ilukezippo.vcfdesk.core.text

object Phones {
    private val sep = Regex("[\\s\\-().]")

    fun normalise(v: String, cc: String): String = convert(v, cc, 8)

    fun addCountryCode(v: String, cc: String = "+965", localLength: Int = 8): String = convert(v, cc, localLength)

    private fun convert(raw: String, cc: String, localLength: Int): String {
        var v = raw.trim()
        if (v.endsWith(".0") && v.length > 2 && v.dropLast(2).all { it in '0'..'9' }) v = v.dropLast(2)
        val d = v.replace(sep, "")
        val allDigits = d.isNotEmpty() && d.all { it in '0'..'9' }
        if (cc.isNotEmpty() && allDigits && d.length == localLength) return cc + d
        if (allDigits && d.startsWith("00") && d.length >= 10) return "+" + d.substring(2)
        val ccd = cc.trimStart('+')
        if (cc.isNotEmpty() && allDigits && d.length in (localLength + 3)..(localLength + 4) && d.startsWith(ccd)) return "+$d"
        return v
    }
}
