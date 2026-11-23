package com.ilukezippo.vcfdesk.core.model

/** Where a contact lives: a device account or a Google account. */
data class AccountTag(val type: String, val name: String, val label: String, val readOnly: Boolean)

data class Name(
    val prefix: String = "",
    val first: String = "",
    val middle: String = "",
    val last: String = "",
    val suffix: String = "",
    val phoneticFirst: String = "",
    val phoneticLast: String = "",
    val nickname: String = "",
    val display: String = "",
)

/** A labelled value (phone, email, website, event, relation, IM). */
data class Typed(val label: String, val value: String)

data class Address(
    val label: String = "",
    val street: String = "",
    val city: String = "",
    val region: String = "",
    val postcode: String = "",
    val country: String = "",
)

data class Org(val company: String = "", val department: String = "", val title: String = "")

/**
 * Data a source holds that the model does not cover, kept verbatim.
 * kind "vcard": raw (unfolded) property line; "google": raw JSON of an unknown key;
 * "device": mimetype, informational only, never written.
 */
data class Extra(val kind: String, val raw: String)

data class Contact(
    val id: String = "",
    val account: AccountTag? = null,
    val etag: String? = null,
    val name: Name = Name(),
    val phones: List<Typed> = emptyList(),
    val emails: List<Typed> = emptyList(),
    val addresses: List<Address> = emptyList(),
    val org: Org = Org(),
    val websites: List<Typed> = emptyList(),
    val events: List<Typed> = emptyList(),
    val relations: List<Typed> = emptyList(),
    val ims: List<Typed> = emptyList(),
    val notes: String = "",
    val groups: Set<String> = emptySet(),
    val photo: ByteArray? = null,
    val thumbnail: ByteArray? = null,
    val extras: List<Extra> = emptyList(),
) {
    // ByteArray fields need content comparison (a data-class default would compare identity).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Contact) return false
        return id == other.id && account == other.account && etag == other.etag &&
            name == other.name && phones == other.phones && emails == other.emails &&
            addresses == other.addresses && org == other.org && websites == other.websites &&
            events == other.events && relations == other.relations && ims == other.ims &&
            notes == other.notes && groups == other.groups &&
            photo.contentEquals(other.photo) && thumbnail.contentEquals(other.thumbnail) &&
            extras == other.extras
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + (account?.hashCode() ?: 0)
        h = 31 * h + (etag?.hashCode() ?: 0)
        h = 31 * h + name.hashCode()
        h = 31 * h + phones.hashCode()
        h = 31 * h + emails.hashCode()
        h = 31 * h + addresses.hashCode()
        h = 31 * h + org.hashCode()
        h = 31 * h + websites.hashCode()
        h = 31 * h + events.hashCode()
        h = 31 * h + relations.hashCode()
        h = 31 * h + ims.hashCode()
        h = 31 * h + notes.hashCode()
        h = 31 * h + groups.hashCode()
        h = 31 * h + (photo?.contentHashCode() ?: 0)
        h = 31 * h + (thumbnail?.contentHashCode() ?: 0)
        h = 31 * h + extras.hashCode()
        return h
    }
}
