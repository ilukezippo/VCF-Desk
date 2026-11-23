package com.ilukezippo.vcfdesk.data.device

/**
 * Readable labels for ContactsContract TYPE / PROTOCOL ints. Where they overlap with the :core
 * vCard reader the same words are used ("Mobile", "Work fax", "birthday", ...) so a VCF import
 * written to the device keeps its labels.
 */
internal class LabelCodec(
    private val typeCol: String,
    private val labelCol: String,
    private val table: List<Pair<Int, String>>,
    private val customType: Int,
    /** TYPE written for a blank label; null writes no type at all. */
    private val blankType: Int?,
) {
    val columns = listOf(typeCol, labelCol)

    fun read(v: Map<String, String?>): String {
        val t = v[typeCol]?.trim()?.toIntOrNull()
        if (t != null && t != customType) table.firstOrNull { it.first == t }?.let { return it.second }
        return v[labelCol].orEmpty()
    }

    fun write(label: String): Map<String, String?> {
        val l = label.trim()
        if (l.isEmpty()) return mapOf(typeCol to blankType?.toString(), labelCol to null)
        val std = table.firstOrNull { it.second.equals(l, ignoreCase = true) }
        return if (std != null) mapOf(typeCol to std.first.toString(), labelCol to null)
        else mapOf(typeCol to customType.toString(), labelCol to label)
    }
}

internal object DeviceLabels {
    private const val TYPE = "data2"
    private const val LABEL = "data3"

    val PHONE = LabelCodec(
        TYPE, LABEL,
        listOf(
            1 to "Home", 2 to "Mobile", 3 to "Work", 4 to "Work fax", 5 to "Home fax", 6 to "Pager",
            7 to "Other", 8 to "Callback", 9 to "Car", 10 to "Company main", 11 to "ISDN", 12 to "Main",
            13 to "Other fax", 14 to "Radio", 15 to "Telex", 16 to "TTY TDD", 17 to "Work mobile",
            18 to "Work pager", 19 to "Assistant", 20 to "MMS",
        ),
        customType = 0, blankType = 7,
    )

    val EMAIL = LabelCodec(
        TYPE, LABEL, listOf(1 to "Home", 2 to "Work", 3 to "Other", 4 to "Mobile"),
        customType = 0, blankType = 3,
    )

    val POSTAL = LabelCodec(TYPE, LABEL, listOf(1 to "Home", 2 to "Work", 3 to "Other"), customType = 0, blankType = 3)

    val WEBSITE = LabelCodec(
        TYPE, LABEL,
        listOf(1 to "Homepage", 2 to "Blog", 3 to "Profile", 4 to "Home", 5 to "Work", 6 to "FTP", 7 to "Other"),
        customType = 0, blankType = 7,
    )

    // lower-case like the vCard reader's BDAY/ANNIVERSARY/X-ABDATE labels
    val EVENT = LabelCodec(
        TYPE, LABEL, listOf(1 to "anniversary", 2 to "other", 3 to "birthday"),
        customType = 0, blankType = 2,
    )

    val RELATION = LabelCodec(
        TYPE, LABEL,
        listOf(
            1 to "Assistant", 2 to "Brother", 3 to "Child", 4 to "Domestic partner", 5 to "Father", 6 to "Friend",
            7 to "Manager", 8 to "Mother", 9 to "Parent", 10 to "Partner", 11 to "Referred by", 12 to "Relative",
            13 to "Sister", 14 to "Spouse",
        ),
        customType = 0, blankType = null,
    )

    /** IM label = service: PROTOCOL (data5) / CUSTOM_PROTOCOL (data6), names as the vCard reader uses. */
    val IM_PROTOCOL = LabelCodec(
        "data5", "data6",
        listOf(
            0 to "AIM", 1 to "Windows Live", 2 to "Yahoo", 3 to "Skype", 4 to "QQ", 5 to "Google Talk",
            6 to "ICQ", 7 to "Jabber", 8 to "NetMeeting",
        ),
        customType = -1, blankType = null,
    )
}
