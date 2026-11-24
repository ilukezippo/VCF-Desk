package com.ilukezippo.vcfdesk.data.backup

import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.vcard.VCardReader
import com.ilukezippo.vcfdesk.core.vcard.VCardVersion
import com.ilukezippo.vcfdesk.core.vcard.VCardWriter
import com.ilukezippo.vcfdesk.data.SourceId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter

data class Backup(val file: File, val time: Instant, val action: String, val source: SourceId, val count: Int)

/**
 * One backed-up contact. [before] is the contact as it was (lossless, full photo); [after] is
 * what the bulk write made of it (no photo bytes), when the backup is of an edit. Photos are
 * compared by SHA-256: [beforePhoto] / [afterPhoto], null for "no photo".
 */
data class BackupEntry(val before: Contact, val after: Contact?, val beforePhoto: String?, val afterPhoto: String?)

/** For a move: where the copies went, original id to the copy's id. */
data class MoveRecord(val target: SourceId, val copies: Map<String, String>)

/**
 * Backups of contacts taken before a bulk write: a vCard 3.0 file (the human-readable copy,
 * and the home of the photos) plus a sidecar `.json` holding a lossless snapshot of every
 * contact (and, for edits, of the result), matched to the vCard by index.
 *
 * Both files are written to `.tmp` first, flushed to disk, read back and checked (count and
 * photo checksums), then renamed; the `.json` is renamed last, so a backup only exists (is
 * listed) once it is complete. Later sidecar updates are atomic renames too.
 */
class BackupStore(private val dir: File, private val clock: Clock = Clock.systemDefaultZone()) {

    @Serializable
    private data class Entry(val index: Int, val before: ContactSnapshot, val after: ContactSnapshot? = null)

    @Serializable
    private data class Meta(
        val version: Int = 2,
        val time: String,
        val action: String,
        val source: String,
        val count: Int,
        val vcf: String,
        val entries: List<Entry>,
        val target: String? = null,
        val copies: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    /** Writes the backup completely or throws [IOException]; nothing partial is left listed. */
    fun save(cs: List<Contact>, action: String, source: SourceId): Backup = save(cs, action, source, null)

    /** As [save]; [after] (same order as [cs]) is what each contact is about to become. */
    fun save(cs: List<Contact>, action: String, source: SourceId, after: List<Contact?>?): Backup {
        require(after == null || after.size == cs.size) { "after must match the contacts" }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create backup folder $dir")
        val now = clock.instant()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(clock.zone).format(now)
        val safeAction = action.replace(Regex("[^A-Za-z0-9_-]"), "_")
        var base = "$stamp-$safeAction"
        var n = 2
        while (File(dir, "$base.vcf").exists() || File(dir, "$base.json").exists()) base = "$stamp-$safeAction-${n++}"
        val vcf = File(dir, "$base.vcf")
        val meta = File(dir, "$base.json")
        val vcfTmp = File(dir, "$base.vcf.tmp")
        val metaTmp = File(dir, "$base.json.tmp")

        val m = Meta(
            time = now.toString(),
            action = action,
            source = encode(source),
            count = cs.size,
            vcf = vcf.name,
            entries = cs.mapIndexed { i, c -> Entry(i, ContactSnapshot.of(c), after?.get(i)?.let(ContactSnapshot::of)) },
        )
        try {
            writeSynced(vcfTmp, VCardWriter.write(cs, VCardVersion.V30))
            writeSynced(metaTmp, json.encodeToString(Meta.serializer(), m))
            // the backup must read back as exactly the contacts given, photos included, or it is no backup
            val back = VCardReader.read(vcfTmp.readText(Charsets.UTF_8))
            if (back.size != cs.size) throw IOException("backup check failed: wrote ${cs.size}, read back ${back.size}")
            back.forEachIndexed { i, c ->
                if (ContactSnapshot.sha(c.photo) != m.entries[i].before.photoSha) throw IOException("backup check failed: photo of contact ${i + 1}")
            }
            json.decodeFromString(Meta.serializer(), metaTmp.readText(Charsets.UTF_8))
            if (!vcfTmp.renameTo(vcf)) throw IOException("can't write ${vcf.name}")
            if (!metaTmp.renameTo(meta)) { vcf.delete(); throw IOException("can't write ${meta.name}") }
        } catch (e: Exception) {
            vcfTmp.delete(); metaTmp.delete()
            throw if (e is IOException) e else IOException("backup failed: ${e.message}", e)
        }
        return Backup(vcf, now, action, source, cs.size)
    }

    /** Replaces the recorded results by what the source actually holds now (by original id). */
    fun setAfter(b: Backup, after: Map<String, Contact>) {
        val m = readMeta(b)
        writeMeta(b, m.copy(entries = m.entries.map { e -> after[e.before.id]?.let { e.copy(after = ContactSnapshot.of(it)) } ?: e }))
    }

    /** Records where a move put its copies, so undo can remove them. */
    fun recordMove(b: Backup, target: SourceId, copies: Map<String, String>) {
        val m = readMeta(b)
        writeMeta(b, m.copy(target = encode(target), copies = m.copies + copies))
    }

    fun move(b: Backup): MoveRecord? {
        val m = readMeta(b)
        return m.target?.let { MoveRecord(decode(it), m.copies) }
    }

    /** Complete backups, newest first. */
    fun list(): List<Backup> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList() ?: emptyList())
            .mapNotNull { f ->
                try {
                    val m = json.decodeFromString(Meta.serializer(), f.readText(Charsets.UTF_8))
                    val vcf = File(dir, m.vcf)
                    if (!vcf.isFile) null
                    else Backup(vcf, Instant.parse(m.time), m.action, decode(m.source), m.count)
                } catch (e: Exception) {
                    null
                }
            }
            .sortedWith(compareByDescending<Backup> { it.time }.thenByDescending { it.file.name })

    /** The backed-up contacts as they were, with original ids and accounts (etag not kept). */
    fun load(b: Backup): List<Contact> = entries(b).map { it.before.copy(etag = null) }

    fun entries(b: Backup): List<BackupEntry> {
        val m = readMeta(b)
        val cards = VCardReader.read(b.file.readText(Charsets.UTF_8))
        if (cards.size != m.entries.size) throw IOException("backup ${b.file.name} is damaged: ${cards.size} contacts, ${m.entries.size} entries")
        return m.entries.sortedBy { it.index }.mapIndexed { i, e ->
            if (e.index != i) throw IOException("backup ${b.file.name} is damaged: no entry $i")
            val photo = cards[i].photo?.takeIf { it.isNotEmpty() }
            if (ContactSnapshot.sha(photo) != e.before.photoSha) throw IOException("backup ${b.file.name} is damaged: photo of entry $i")
            BackupEntry(e.before.toContact(photo), e.after?.toContact(null), e.before.photoSha, e.after?.photoSha)
        }
    }

    private fun metaFile(b: Backup) = File(b.file.parentFile, b.file.name.removeSuffix(".vcf") + ".json")

    private fun readMeta(b: Backup): Meta = json.decodeFromString(Meta.serializer(), metaFile(b).readText(Charsets.UTF_8))

    private fun writeMeta(b: Backup, m: Meta) {
        val f = metaFile(b)
        val tmp = File(f.parentFile, f.name + ".tmp")
        try {
            writeSynced(tmp, json.encodeToString(Meta.serializer(), m))
            if (!tmp.renameTo(f)) throw IOException("can't update ${f.name}")
        } finally {
            tmp.delete()
        }
    }

    private fun writeSynced(f: File, text: String) {
        FileOutputStream(f).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
    }

    companion object {
        fun encode(s: SourceId): String = when (s) {
            SourceId.Device -> "device"
            is SourceId.Google -> "google:${s.email}"
        }

        fun decode(s: String): SourceId = when {
            s == "device" -> SourceId.Device
            s.startsWith("google:") -> SourceId.Google(s.removePrefix("google:"))
            else -> throw IllegalArgumentException("unknown source $s")
        }
    }
}
