package com.ilukezippo.vcfdesk.core.text

import java.time.DateTimeException
import java.time.LocalDate

object Dates {
    private val serial = Regex("\\d{4,5}(\\.0+)?")
    private val dmy = Regex("(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4})")
    private val ymd = Regex("(\\d{4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2}).*")
    private val dm = Regex("(\\d{1,2})[/.](\\d{1,2})")

    /** yyyy-MM-dd (day first for d/m/y, Excel serials converted), or --MM-dd when there is no year. */
    fun normalise(v0: String): String {
        val v = foldDigits(v0).trim()
        if (serial.matches(v)) {
            val n = v.substringBefore('.').toLong()
            if (n in 2..79999) return LocalDate.of(1899, 12, 30).plusDays(n).toString()
        }
        try {
            dmy.matchEntire(v)?.let { m -> val (d, mo, y) = m.destructured; return LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).toString() }
            ymd.matchEntire(v)?.let { m -> val (y, mo, d) = m.destructured; return LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).toString() }
            dm.matchEntire(v)?.let { m ->
                val (d, mo) = m.destructured
                LocalDate.of(2000, mo.toInt(), d.toInt())  // validates
                return "--%02d-%02d".format(mo.toInt(), d.toInt())
            }
        } catch (_: DateTimeException) {
        }
        return v
    }
}
