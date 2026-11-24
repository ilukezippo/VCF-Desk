package com.ilukezippo.vcfdesk.data.backup

import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Address
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Extra
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Org
import com.ilukezippo.vcfdesk.core.model.Typed
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * A lossless copy of a [Contact] for the backup sidecar: every model field, labels, pref,
 * params, extras, id and account — except the photo bytes, which live in the backup's vCard
 * (matched by index and checked against [photoSha]).
 */
@Serializable
internal data class ContactSnapshot(
    val id: String,
    val account: SAccount? = null,
    val etag: String? = null,
    val name: SName = SName(),
    val phones: List<STyped> = emptyList(),
    val emails: List<STyped> = emptyList(),
    val addresses: List<SAddress> = emptyList(),
    val org: SOrg = SOrg(),
    val websites: List<STyped> = emptyList(),
    val events: List<STyped> = emptyList(),
    val relations: List<STyped> = emptyList(),
    val ims: List<STyped> = emptyList(),
    val notes: String = "",
    val groups: List<String> = emptyList(),
    val extras: List<SExtra> = emptyList(),
    /** SHA-256 (hex) of the full photo; null when there is none. */
    val photoSha: String? = null,
) {
    fun toContact(photo: ByteArray?): Contact = Contact(
        id = id,
        account = account?.let { AccountTag(it.type, it.name, it.label, it.readOnly) },
        etag = etag,
        name = Name(name.prefix, name.first, name.middle, name.last, name.suffix, name.phoneticFirst, name.phoneticLast, name.nickname, name.display),
        phones = phones.map { it.toTyped() },
        emails = emails.map { it.toTyped() },
        addresses = addresses.map {
            Address(it.label, it.street, it.city, it.region, it.postcode, it.country, it.poBox, it.extended, it.pref, it.params)
        },
        org = Org(org.company, org.department, org.title, org.units),
        websites = websites.map { it.toTyped() },
        events = events.map { it.toTyped() },
        relations = relations.map { it.toTyped() },
        ims = ims.map { it.toTyped() },
        notes = notes,
        groups = LinkedHashSet(groups),
        photo = photo,
        extras = extras.map { Extra(it.kind, it.raw) },
    )

    companion object {
        fun of(c: Contact) = ContactSnapshot(
            id = c.id,
            account = c.account?.let { SAccount(it.type, it.name, it.label, it.readOnly) },
            etag = c.etag,
            name = c.name.let { SName(it.prefix, it.first, it.middle, it.last, it.suffix, it.phoneticFirst, it.phoneticLast, it.nickname, it.display) },
            phones = c.phones.map(::typed),
            emails = c.emails.map(::typed),
            addresses = c.addresses.map {
                SAddress(it.label, it.street, it.city, it.region, it.postcode, it.country, it.poBox, it.extended, it.pref, it.params)
            },
            org = SOrg(c.org.company, c.org.department, c.org.title, c.org.units),
            websites = c.websites.map(::typed),
            events = c.events.map(::typed),
            relations = c.relations.map(::typed),
            ims = c.ims.map(::typed),
            notes = c.notes,
            groups = c.groups.toList(),
            extras = c.extras.map { SExtra(it.kind, it.raw) },
            photoSha = sha(c.photo),
        )

        private fun typed(t: Typed) = STyped(t.label, t.value, t.pref, t.params)

        fun sha(bytes: ByteArray?): String? =
            if (bytes == null || bytes.isEmpty()) null
            else MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

@Serializable internal data class SAccount(val type: String, val name: String, val label: String, val readOnly: Boolean)

@Serializable
internal data class SName(
    val prefix: String = "", val first: String = "", val middle: String = "", val last: String = "", val suffix: String = "",
    val phoneticFirst: String = "", val phoneticLast: String = "", val nickname: String = "", val display: String = "",
)

@Serializable
internal data class STyped(val label: String, val value: String, val pref: Boolean = false, val params: Map<String, String> = emptyMap()) {
    fun toTyped() = Typed(label, value, pref, params)
}

@Serializable
internal data class SAddress(
    val label: String = "", val street: String = "", val city: String = "", val region: String = "", val postcode: String = "",
    val country: String = "", val poBox: String = "", val extended: String = "", val pref: Boolean = false,
    val params: Map<String, String> = emptyMap(),
)

@Serializable
internal data class SOrg(val company: String = "", val department: String = "", val title: String = "", val units: List<String> = emptyList())

@Serializable internal data class SExtra(val kind: String, val raw: String)
