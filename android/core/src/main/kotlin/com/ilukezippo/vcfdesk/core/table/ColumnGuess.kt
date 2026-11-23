package com.ilukezippo.vcfdesk.core.table

enum class Field {
    SKIP, FULL_NAME, PREFIX, FIRST, MIDDLE, LAST, SUFFIX, NICKNAME, MOBILE, PHONE_HOME, PHONE_WORK,
    EMAIL, COMPANY, DEPARTMENT, TITLE, STREET, CITY, COUNTRY, BIRTHDAY, NOTES, GROUPS, WEBSITE, PHOTO,

    // additions: the desktop importer also maps these, and region/postcode would otherwise be lost
    PHONE_OTHER, EMAIL_WORK, REGION, POSTCODE,
}

object ColumnGuess {
    // (field, keywords): checked in order, first match wins. Same table as the desktop's guess_field.
    private val table: List<Pair<Field, List<String>>> = listOf(
        Field.SKIP to listOf(" - label", " - type", "e-mail display", "email display", " - formatted", "po box", "file as", "phonetic"),
        Field.PHOTO to listOf("photo", "picture", "image", "pic", "صورة", "الصورة"),
        Field.EMAIL_WORK to listOf("work email", "office email", "business email", "بريد العمل"),
        Field.EMAIL to listOf("email", "e-mail", "mail", "البريد", "الايميل", "الإيميل"),
        Field.PHONE_HOME to listOf("home phone", "هاتف المنزل", "المنزل"),
        Field.PHONE_WORK to listOf("work phone", "office phone", "business phone", "هاتف العمل", "المكتب", "تلفون العمل"),
        Field.MOBILE to listOf(
            "mobile", "cell", "phone", "tel", "whatsapp", "الهاتف", "هاتف", "الجوال", "جوال", "النقال", "نقال", "موبايل", "تلفون", "رقم التواصل",
        ),
        Field.FIRST to listOf("first name", "first", "given", "الاسم الأول", "الاسم الاول"),
        Field.MIDDLE to listOf("middle", "اسم الأب", "اسم الاب", "الأب", "الاب"),
        Field.LAST to listOf("last name", "last", "surname", "family", "العائلة", "اللقب", "القبيلة"),
        Field.PREFIX to listOf("prefix", "salutation", "اللقب العلمي"),
        Field.SUFFIX to listOf("suffix"),
        Field.NICKNAME to listOf("nickname"),
        Field.FULL_NAME to listOf("display", "الاسم المختصر"),
        Field.DEPARTMENT to listOf("department", "dept", "division", "section", "unit", "القسم", "الإدارة", "الادارة", "الوحدة", "الفرع"),
        Field.TITLE to listOf("job", "title", "position", "rank", "role", "الوظيفة", "المسمى", "الرتبة", "المنصب", "الدرجة"),
        Field.COMPANY to listOf("company", "organization", "organisation", "employer", "الشركة", "الجهة", "المؤسسة"),
        Field.BIRTHDAY to listOf("birth", "dob", "الميلاد"),
        Field.POSTCODE to listOf("postal", "zip", "postcode", "الرمز البريدي"),
        // country before region so "Country/Region" (Outlook) is a country (the desktop lists it the other way round)
        Field.COUNTRY to listOf("country", "nation", "الدولة", "البلد"),
        Field.REGION to listOf("region", "state", "province", "المحافظة"),
        Field.CITY to listOf("city", "town", "area", "المدينة", "المنطقة"),
        Field.STREET to listOf("address", "street", "العنوان", "الشارع"),
        Field.WEBSITE to listOf("website", "web page", "url", "web", "الموقع"),
        Field.GROUPS to listOf("group", "category", "categories", "labels", "المجموعة", "الفئة"),
        Field.NOTES to listOf("note", "remark", "comment", "ملاحظ"),
        Field.FULL_NAME to listOf("name", "full name", "الاسم", "اسم", "الإسم"),
    )

    fun guess(header: String): Field {
        val h = header.trim().lowercase()
        if (h.isEmpty()) return Field.SKIP
        for ((field, words) in table) if (words.any { it in h }) return field
        return Field.SKIP
    }
}
