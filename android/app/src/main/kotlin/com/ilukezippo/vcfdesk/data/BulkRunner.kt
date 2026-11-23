package com.ilukezippo.vcfdesk.data

import com.ilukezippo.vcfdesk.core.bulk.Bulk
import com.ilukezippo.vcfdesk.core.bulk.BulkChange
import com.ilukezippo.vcfdesk.core.bulk.ContactDiff
import com.ilukezippo.vcfdesk.core.bulk.FieldDiff
import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.text.Names
import com.ilukezippo.vcfdesk.core.tidy.Duplicates
import com.ilukezippo.vcfdesk.data.backup.Backup
import com.ilukezippo.vcfdesk.data.backup.BackupStore
import kotlin.coroutines.cancellation.CancellationException

sealed interface BulkOp {
    data class Edit(val changes: List<BulkChange>) : BulkOp
    data object Delete : BulkOp
    data class CopyTo(val target: ContactSource, val account: AccountTag?, val move: Boolean) : BulkOp
    data object RemoveExactDuplicates : BulkOp
}

data class BulkReport(val changed: Int, val skippedReadOnly: Int, val failed: Map<String, String>, val backup: Backup?)

/**
 * Every bulk write goes through here: load fresh (full photos, current etags) → back up →
 * write → report. Nothing is written unless its backup is complete on disk, nothing is deleted
 * unless it was backed up (and, for a move, its copy confirmed), and [undo] puts a backup back.
 */
class BulkRunner(private val store: BackupStore) {

    private class Loaded(val contacts: List<Contact>, val failed: Map<String, String>)

    private class Tally {
        var changed = 0
        var skipped = 0
        val failed = LinkedHashMap<String, String>()
        fun add(r: WriteResult, countWritten: Boolean = true) {
            if (countWritten) changed += r.written.size
            skipped += r.skippedReadOnly.size
            failed.putAll(r.failed)
        }
        fun report(backup: Backup?) = BulkReport(changed, skipped, failed, backup)
    }

    suspend fun preview(src: ContactSource, ids: List<String>, op: BulkOp): List<ContactDiff> {
        val cs = loadAll(src, ids).contacts
        return when (op) {
            is BulkOp.Edit -> Bulk.preview(cs, op.changes)
            BulkOp.Delete -> cs.map { effect(it, "deleted") }
            is BulkOp.CopyTo -> {
                val where = op.account?.label?.ifBlank { null } ?: op.account?.name ?: placeName(op.target.id)
                cs.map { effect(it, (if (op.move) "moved to " else "copied to ") + where) }
            }
            BulkOp.RemoveExactDuplicates -> duplicatesToRemove(cs).map { (c, keeper) ->
                effect(c, "removed (duplicate of ${nameOf(keeper)})")
            }
        }
    }

    suspend fun run(src: ContactSource, ids: List<String>, op: BulkOp, progress: (Int, Int) -> Unit): BulkReport {
        val loaded = loadAll(src, ids)
        val t = Tally()
        t.failed.putAll(loaded.failed)
        val cs = loaded.contacts
        when (op) {
            is BulkOp.Edit -> {
                val diffs = Bulk.preview(cs, op.changes)
                if (diffs.isEmpty()) return t.report(null)
                val backup = backupOrAbort(diffs.map { it.contact }, "edit", src.id, ids, t) ?: return t.report(null)
                val total = diffs.size
                progress(0, total)
                t.add(src.update(diffs.map { it.after }) { progress(it, total) })
                return t.report(backup)
            }
            BulkOp.Delete -> return deleteWithBackup(src, cs, ids, t, progress)
            BulkOp.RemoveExactDuplicates -> {
                val gone = duplicatesToRemove(cs).map { it.first }
                return deleteWithBackup(src, gone, ids, t, progress)
            }
            is BulkOp.CopyTo -> return copy(src, cs, ids, op, t, progress)
        }
    }

    /**
     * Restores a backup into [src]: contacts that still exist are updated back to the backed-up
     * values (with their current etag and source data, so the write is accepted and nothing the
     * model doesn't know is dropped); contacts that are gone are re-created in their original
     * account, with new ids. The current state of the contacts being overwritten is itself
     * backed up first (action "undo").
     */
    suspend fun undo(src: ContactSource, backup: Backup): BulkReport {
        require(backup.source == src.id) {
            "backup is from ${BackupStore.encode(backup.source)}, not ${BackupStore.encode(src.id)}"
        }
        val saved = store.load(backup)
        val t = Tally()
        val restore = ArrayList<Pair<Contact, Contact>>() // current to restored
        val missing = ArrayList<Contact>()
        for (b in saved.distinctBy { it.id }) {
            val cur = try {
                src.load(b.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoSuchElementException) {
                null
            } catch (e: Exception) {
                // can't tell whether it exists: re-creating could duplicate it, so leave it
                t.failed[b.id] = reason(e); continue
            }
            if (cur == null) { missing += b; continue }
            val restored = b.copy(
                id = cur.id, account = cur.account, etag = cur.etag, extras = cur.extras,
                // bulk edits never remove photos: a photo added since the backup is kept
                photo = b.photo ?: cur.photo,
                thumbnail = if (b.photo != null) b.photo else cur.thumbnail,
            )
            if (restored != cur) restore += cur to restored
        }
        var safety: Backup? = null
        if (restore.isNotEmpty()) {
            safety = backupOrAbort(restore.map { it.first }, "undo", src.id, saved.map { it.id }, t)
                ?: return t.report(null)
            t.add(src.update(restore.map { it.second }))
        }
        for ((acct, group) in missing.groupBy { it.account }) {
            t.add(src.create(group.map { it.copy(etag = null) }, acct))
        }
        return t.report(safety)
    }

    private suspend fun deleteWithBackup(
        src: ContactSource, cs: List<Contact>, ids: List<String>, t: Tally, progress: (Int, Int) -> Unit,
    ): BulkReport {
        if (cs.isEmpty()) return t.report(null)
        val backup = backupOrAbort(cs, "delete", src.id, ids, t) ?: return t.report(null)
        val total = cs.size
        progress(0, total)
        // only what was loaded, and so backed up, is deleted
        t.add(src.delete(cs.map { it.id }) { progress(it, total) })
        return t.report(backup)
    }

    private suspend fun copy(
        src: ContactSource, cs: List<Contact>, ids: List<String>, op: BulkOp.CopyTo, t: Tally, progress: (Int, Int) -> Unit,
    ): BulkReport {
        if (cs.isEmpty()) return t.report(null)
        val backup = if (op.move) backupOrAbort(cs, "move", src.id, ids, t) ?: return t.report(null) else null
        val total = if (op.move) cs.size * 2 else cs.size
        progress(0, total)
        // ids stay on the copies only so the target can key its failures by them
        val created = op.target.create(cs.map { it.copy(etag = null, account = op.account) }, op.account) { progress(it, total) }
        if (!op.move) {
            t.add(created)
            return t.report(null)
        }
        t.skipped += created.skippedReadOnly.size
        val inputIds = cs.map { it.id }
        fun inputKey(k: String): String =
            if (k.startsWith("#")) k.drop(1).toIntOrNull()?.let { inputIds.getOrNull(it) } ?: k else k
        val notCopied = LinkedHashMap<String, String>()
        created.failed.forEach { (k, why) -> notCopied[inputKey(k)] = why }
        created.skippedReadOnly.forEach { notCopied.putIfAbsent(inputKey(it), "") }
        val confirmed = inputIds.filter { it !in notCopied }
        t.failed.putAll(created.failed.mapKeys { inputKey(it.key) })
        if (created.written.size != confirmed.size) {
            // the target's answer doesn't account for every contact: don't delete anything
            confirmed.forEach { t.failed[it] = "copy not confirmed by the target; kept in the source" }
            return t.report(backup)
        }
        if (confirmed.isEmpty()) return t.report(backup)
        val deleted = src.delete(confirmed) { progress(cs.size + it, total) }
        t.changed += deleted.written.size
        t.skipped += deleted.skippedReadOnly.size
        deleted.failed.forEach { (id, why) -> t.failed[id] = "copied, but not removed from the source: $why" }
        progress(total, total)
        return t.report(backup)
    }

    /** Saves a backup, or records every id as failed and returns null so the caller writes nothing. */
    private fun backupOrAbort(cs: List<Contact>, action: String, source: SourceId, ids: List<String>, t: Tally): Backup? =
        try {
            store.save(cs, action, source)
        } catch (e: Exception) {
            val why = "backup could not be written, nothing was changed: ${reason(e)}"
            ids.distinct().forEach { t.failed.putIfAbsent(it, why) }
            t.changed = 0
            null
        }

    private suspend fun loadAll(src: ContactSource, ids: List<String>): Loaded {
        val out = ArrayList<Contact>()
        val failed = LinkedHashMap<String, String>()
        for (id in ids.distinct()) {
            try {
                out += src.load(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoSuchElementException) {
                failed[id] = "not found"
            } catch (e: Exception) {
                failed[id] = reason(e)
            }
        }
        return Loaded(out, failed)
    }

    private fun duplicatesToRemove(cs: List<Contact>): List<Pair<Contact, Contact>> =
        Duplicates.groups(cs).flatMap { g ->
            val keeper = Duplicates.keeper(g)
            g.filter { it.id != keeper.id }.map { it to keeper }
        }

    private fun effect(c: Contact, what: String) = ContactDiff(c, c, listOf(FieldDiff("Contact", nameOf(c), what)))

    private fun nameOf(c: Contact): String =
        c.name.display.trim().ifEmpty { Names.compose(c.name) }.ifEmpty { c.org.company.trim() }
            .ifEmpty { c.phones.firstOrNull()?.value ?: c.emails.firstOrNull()?.value ?: "(no name)" }

    private fun placeName(s: SourceId) = when (s) {
        SourceId.Device -> "this phone"
        is SourceId.Google -> s.email
    }

    private fun reason(e: Exception) = e.message ?: e.javaClass.simpleName
}
