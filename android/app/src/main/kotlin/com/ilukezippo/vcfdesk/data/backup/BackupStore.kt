package com.ilukezippo.vcfdesk.data.backup

import com.ilukezippo.vcfdesk.core.model.AccountTag
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
 * Backups of contacts taken before a bulk write: a vCard 3.0 file plus a sidecar `.json` with
 * the time, action, source and, per vCard entry (by index), the original id and account —
 * a vCard can't carry those, and undo needs them.
 *
 * Both files are written to `.tmp` first, flushed to disk, read back and checked, then renamed;
 * the `.json` is renamed last, so a backup only exists (is listed) once it is complete.
 */
class BackupStore(private val dir: File, private val clock: Clock = Clock.systemDefaultZone()) {

    @Serializable
    private data class Entry(
        val index: Int,
        val id: String,
        val accountType: String? = null,
        val accountName: String? = null,
        val accountLabel: String? = null,
        val accountReadOnly: Boolean = false,
    )

    @Serializable
    private data class Meta(
        val time: String,
        val action: String,
        val source: String,
        val count: Int,
        val vcf: String,
        val entries: List<Entry>,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Writes the backup completely or throws [IOException]; nothing partial is left listed. */
    fun save(cs: List<Contact>, action: String, source: SourceId): Backup {
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

        val text = VCardWriter.write(cs, VCardVersion.V30)
        val m = Meta(
            time = now.toString(),
            action = action,
            source = encode(source),
            count = cs.size,
            vcf = vcf.name,
            entries = cs.mapIndexed { i, c ->
                Entry(i, c.id, c.account?.type, c.account?.name, c.account?.label, c.account?.readOnly ?: false)
            },
        )
        try {
            writeSynced(vcfTmp, text)
            writeSynced(metaTmp, json.encodeToString(Meta.serializer(), m))
            // the backup must read back as exactly the contacts given, or it is no backup
            val back = VCardReader.read(vcfTmp.readText(Charsets.UTF_8))
            if (back.size != cs.size) throw IOException("backup check failed: wrote ${cs.size}, read back ${back.size}")
            if (!vcfTmp.renameTo(vcf)) throw IOException("can't write ${vcf.name}")
            if (!metaTmp.renameTo(meta)) { vcf.delete(); throw IOException("can't write ${meta.name}") }
        } catch (e: Exception) {
            vcfTmp.delete(); metaTmp.delete()
            throw if (e is IOException) e else IOException("backup failed: ${e.message}", e)
        }
        return Backup(vcf, now, action, source, cs.size)
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

    /** The backed-up contacts, with their original ids and accounts restored (etag is not kept). */
    fun load(b: Backup): List<Contact> {
        val m = json.decodeFromString(Meta.serializer(), metaFile(b).readText(Charsets.UTF_8))
        val cs = VCardReader.read(b.file.readText(Charsets.UTF_8))
        if (cs.size != m.entries.size) throw IOException("backup ${b.file.name} is damaged: ${cs.size} contacts, ${m.entries.size} entries")
        val byIndex = m.entries.associateBy { it.index }
        return cs.mapIndexed { i, c ->
            val e = byIndex[i] ?: throw IOException("backup ${b.file.name} is damaged: no entry $i")
            val acct = if (e.accountType == null && e.accountName == null) null
            else AccountTag(e.accountType.orEmpty(), e.accountName.orEmpty(), e.accountLabel ?: e.accountName.orEmpty(), e.accountReadOnly)
            c.copy(id = e.id, account = acct, etag = null)
        }
    }

    private fun metaFile(b: Backup) = File(b.file.parentFile, b.file.name.removeSuffix(".vcf") + ".json")

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
