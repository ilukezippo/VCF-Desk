package com.ilukezippo.vcfdesk.data

import com.ilukezippo.vcfdesk.core.bulk.Bulk
import com.ilukezippo.vcfdesk.core.bulk.BulkChange
import com.ilukezippo.vcfdesk.core.bulk.ContactDiff
import com.ilukezippo.vcfdesk.core.bulk.FieldDiff
import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Address
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Typed
import com.ilukezippo.vcfdesk.core.text.Names
import com.ilukezippo.vcfdesk.core.tidy.Duplicates
import com.ilukezippo.vcfdesk.data.backup.Backup
import com.ilukezippo.vcfdesk.data.backup.BackupEntry
import com.ilukezippo.vcfdesk.data.backup.BackupStore
import com.ilukezippo.vcfdesk.data.backup.MoveRecord
import com.ilukezippo.vcfdesk.data.google.PeopleMapper
import java.security.MessageDigest
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
 * write → report. Nothing is written unless its backup is complete on disk; a contact whose
 * full photo can't be read is not touched at all; nothing is deleted unless it was backed up
 * (and, for a move, its copy confirmed); [undo] reverts only what the bulk write changed.
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

    /** A duplicate and its keeper; [lacks] names what it has that the keeper doesn't (empty: safe to remove). */
    private class Dup(val contact: Contact, val keeper: Contact, val lacks: List<String>)

    suspend fun preview(src: ContactSource, ids: List<String>, op: BulkOp): List<ContactDiff> {
        val cs = loadAll(src, ids).contacts
        return when (op) {
            is BulkOp.Edit -> Bulk.preview(cs, op.changes)
            BulkOp.Delete -> cs.map { effect(it, "deleted") }
            is BulkOp.CopyTo -> {
                val where = op.account?.label?.ifBlank { null } ?: op.account?.name ?: placeName(op.target.id)
                cs.map { effect(it, (if (op.move) "moved to " else "copied to ") + where) }
            }
            BulkOp.RemoveExactDuplicates -> duplicates(cs).map { d ->
                if (d.lacks.isEmpty()) effect(d.contact, "removed (duplicate of ${nameOf(d.keeper)})")
                else effect(d.contact, "kept — has ${d.lacks.joinToString(", ")} the other copy lacks")
            }
        }
    }

    suspend fun run(src: ContactSource, ids: List<String>, op: BulkOp, onBackup: (Backup) -> Unit = {}, progress: (Int, Int) -> Unit): BulkReport {
        val loaded = loadAll(src, ids)
        val t = Tally()
        t.failed.putAll(loaded.failed)
        val cs = loaded.contacts
        when (op) {
            is BulkOp.Edit -> {
                val diffs = Bulk.preview(cs, op.changes)
                if (diffs.isEmpty()) return t.report(null)
                val backup = backupOrAbort(diffs.map { it.contact }, "edit", src.id, ids, t, diffs.map { it.after })
                    ?: return t.report(null)
                onBackup(backup)
                val total = diffs.size
                progress(0, total)
                val res = src.update(diffs.map { it.after }) { progress(it, total) }
                t.add(res)
                recordActual(src, backup, res.written)
                return t.report(backup)
            }
            BulkOp.Delete -> return deleteWithBackup(src, cs, ids, t, progress, onBackup)
            BulkOp.RemoveExactDuplicates -> {
                val dups = duplicates(cs)
                dups.filter { it.lacks.isNotEmpty() }.forEach {
                    t.failed[it.contact.id] = "kept: has ${it.lacks.joinToString(", ")} the other copy lacks"
                }
                return deleteWithBackup(src, dups.filter { it.lacks.isEmpty() }.map { it.contact }, ids, t, progress, onBackup)
            }
            is BulkOp.CopyTo -> return copy(src, cs, ids, op, t, progress, onBackup)
        }
    }

    /**
     * Reverts a backup in [src]. A contact that still exists gets back, field by field, only
     * what the bulk write changed: a field still as the write left it is restored; a field
     * already back as before is left alone; a field changed since is kept and reported as a
     * conflict. Untouched fields are never written, nor are contacts with nothing to restore.
     * Contacts that are gone are re-created from the backup in their original account.
     * For a move, the copies are then removed from [target] (only those whose original is back).
     * Whatever undo overwrites or removes is itself backed up first (action "undo").
     */
    suspend fun undo(src: ContactSource, backup: Backup, target: ContactSource? = null): BulkReport {
        require(backup.source == src.id) {
            "backup is from ${BackupStore.encode(backup.source)}, not ${BackupStore.encode(src.id)}"
        }
        val entries = store.entries(backup)
        val move = store.move(backup)?.takeIf { it.copies.isNotEmpty() }
        if (move != null) {
            require(target != null && target.id == move.target) {
                "undoing this move also needs ${BackupStore.encode(move.target)}, where the copies are"
            }
        }
        val t = Tally()
        val writes = ArrayList<Pair<Contact, Contact>>() // current to restored
        val missing = ArrayList<Contact>()
        val back = LinkedHashSet<String>() // originals that are in the source once undo is done
        for (e in entries.distinctBy { it.before.id }) {
            val id = e.before.id
            val cur = try {
                src.load(id)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: NoSuchElementException) {
                null
            } catch (ex: Exception) {
                // can't tell whether it exists: re-creating could duplicate it, so leave it
                t.failed[id] = reason(ex); continue
            }
            if (cur == null) { missing += e.before; continue }
            back += id
            if (e.after == null) continue // nothing was written to it (a delete or move that didn't happen)
            val (restored, conflicts) = restoreFields(cur, e)
            if (conflicts.isNotEmpty()) t.failed[id] = conflicts.joinToString(", ") + " changed since the edit; left as they are"
            if (restored != null) writes += cur to restored
        }
        var safety: Backup? = null
        if (writes.isNotEmpty()) {
            safety = backupOrAbort(writes.map { it.first }, "undo", src.id, writes.map { it.first.id }, t, writes.map { it.second })
                ?: return t.report(null)
            val res = src.update(writes.map { it.second })
            t.add(res)
            recordActual(src, safety, res.written)
        }
        for ((acct, group) in missing.groupBy { it.account }) {
            val inputs = group.map { it.copy(etag = null, thumbnail = null) }
            val res = src.create(inputs, acct)
            t.add(res)
            confirmedCreates(inputs.map { it.id }, res)?.let { back += it }
        }
        if (move != null && target != null) {
            val copiesBackup = removeCopies(target, move, back, t)
            if (safety == null) safety = copiesBackup
        }
        return t.report(safety)
    }

    // ---- undo helpers ----

    private fun sha(b: ByteArray?): String? =
        if (b == null || b.isEmpty()) null else MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The current contact with the edited fields put back, or null if nothing needs writing; plus conflicts. */
    private fun restoreFields(cur: Contact, e: BackupEntry): Pair<Contact?, List<String>> {
        val before = e.before
        val after = e.after!!
        var r = cur
        val conflicts = ArrayList<String>()
        fun <T> field(label: String, get: (Contact) -> T, set: Contact.(T) -> Contact) {
            if (get(before) == get(after)) return // the edit didn't touch it: nothing to restore, no conflict
            val now = get(cur)
            when (now) {
                get(before) -> {}
                get(after) -> r = r.set(get(before))
                else -> conflicts += label
            }
        }
        field("name", { it.name }) { copy(name = it) }
        field("phones", { it.phones }) { copy(phones = it) }
        field("emails", { it.emails }) { copy(emails = it) }
        field("addresses", { it.addresses }) { copy(addresses = it) }
        field("company", { it.org }) { copy(org = it) }
        field("websites", { it.websites }) { copy(websites = it) }
        field("dates", { it.events }) { copy(events = it) }
        field("relations", { it.relations }) { copy(relations = it) }
        field("IMs", { it.ims }) { copy(ims = it) }
        field("notes", { it.notes }) { copy(notes = it) }
        field("groups", { it.groups }) { copy(groups = it) }
        val p = sha(cur.photo)
        when {
            e.beforePhoto == e.afterPhoto || p == e.beforePhoto -> {}
            p == e.afterPhoto && before.photo != null -> r = r.copy(photo = before.photo, thumbnail = null)
            else -> conflicts += "photo"
        }
        return (if (r == cur) null else r) to conflicts
    }

    /** Backs up and deletes the copies of the originals in [back] that are still in [target]. */
    private suspend fun removeCopies(target: ContactSource, move: MoveRecord, back: Set<String>, t: Tally): Backup? {
        val present = ArrayList<Contact>()
        for (orig in back) {
            val copyId = move.copies[orig] ?: continue
            try {
                present += target.load(copyId)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: NoSuchElementException) {
                // already gone
            } catch (ex: Exception) {
                t.failed[copyId] = "copy kept in the target: ${reason(ex)}"
            }
        }
        if (present.isEmpty()) return null
        val b = backupOrAbort(present, "undo", target.id, present.map { it.id }, t) ?: return null
        val res = target.delete(present.map { it.id })
        t.skipped += res.skippedReadOnly.size
        res.failed.forEach { (id, why) -> t.failed[id] = "copy not removed from the target: $why" }
        return b
    }

    // ---- run helpers ----

    /** Re-reads what was written and records it as the backup's "after" (best effort). */
    private suspend fun recordActual(src: ContactSource, backup: Backup, written: List<String>) {
        val now = LinkedHashMap<String, Contact>()
        for (id in written) {
            try {
                now[id] = src.load(id)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                // keep the intended result recorded for this one
            }
        }
        if (now.isEmpty()) return
        try {
            store.setAfter(backup, now)
        } catch (ex: Exception) {
            // the intended results stay recorded; undo then reports any difference as a conflict
        }
    }

    private suspend fun deleteWithBackup(
        src: ContactSource, cs: List<Contact>, ids: List<String>, t: Tally, progress: (Int, Int) -> Unit,
        onBackup: (Backup) -> Unit = {},
    ): BulkReport {
        if (cs.isEmpty()) return t.report(null)
        val backup = backupOrAbort(cs, "delete", src.id, ids, t) ?: return t.report(null)
        onBackup(backup)
        val total = cs.size
        progress(0, total)
        // only what was loaded, and so backed up, is deleted
        t.add(src.delete(cs.map { it.id }) { progress(it, total) })
        return t.report(backup)
    }

    /**
     * The input ids [create] confirmably created, in order; null when its answer doesn't add up
     * (then nothing may be assumed copied).
     */
    private fun confirmedCreates(inputIds: List<String>, r: WriteResult): List<String>? {
        fun inputKey(k: String): String =
            if (k.startsWith("#")) k.drop(1).toIntOrNull()?.let { inputIds.getOrNull(it) } ?: k else k
        val notCreated = (r.failed.keys + r.skippedReadOnly).mapTo(HashSet(), ::inputKey)
        val confirmed = inputIds.filter { it !in notCreated }
        return if (confirmed.size == r.written.size) confirmed else null
    }

    private suspend fun copy(
        src: ContactSource, cs: List<Contact>, ids: List<String>, op: BulkOp.CopyTo, t: Tally, progress: (Int, Int) -> Unit,
        onBackup: (Backup) -> Unit = {},
    ): BulkReport {
        if (cs.isEmpty()) return t.report(null)
        val backup = if (op.move) backupOrAbort(cs, "move", src.id, ids, t) ?: return t.report(null) else null
        if (backup != null) onBackup(backup)
        val total = if (op.move) cs.size * 2 else cs.size
        progress(0, total)
        // ids stay on the copies only so the target can key its failures by them
        val inputIds = cs.map { it.id }
        val created = op.target.create(cs.map { it.copy(etag = null, account = op.account) }, op.account) { progress(it, total) }
        if (!op.move) {
            t.add(created)
            return t.report(null)
        }
        t.skipped += created.skippedReadOnly.size
        created.failed.forEach { (k, why) ->
            val key = if (k.startsWith("#")) k.drop(1).toIntOrNull()?.let { inputIds.getOrNull(it) } ?: k else k
            t.failed[key] = why
        }
        val confirmed = confirmedCreates(inputIds, created)
        if (confirmed == null) {
            // the target's answer doesn't account for every contact: don't delete anything
            inputIds.filter { it !in t.failed }.forEach { t.failed[it] = "copy not confirmed by the target; kept in the source" }
            return t.report(backup)
        }
        if (confirmed.isEmpty()) return t.report(backup)
        try {
            store.recordMove(backup!!, op.target.id, confirmed.zip(created.written).toMap())
        } catch (e: Exception) {
            // without the record, undo couldn't remove the copies: keep the originals instead
            confirmed.forEach { t.failed[it] = "copied, but the move could not be recorded; kept in the source" }
            return t.report(backup)
        }
        val deleted = src.delete(confirmed) { progress(cs.size + it, total) }
        t.changed += deleted.written.size
        t.skipped += deleted.skippedReadOnly.size
        deleted.failed.forEach { (id, why) -> t.failed[id] = "copied, but not removed from the source: $why" }
        progress(total, total)
        return t.report(backup)
    }

    /** Saves a backup, or records every id as failed and returns null so the caller writes nothing. */
    private fun backupOrAbort(
        cs: List<Contact>, action: String, source: SourceId, ids: List<String>, t: Tally, after: List<Contact?>? = null,
    ): Backup? =
        try {
            store.save(cs, action, source, after)
        } catch (e: OutOfMemoryError) {
            ids.distinct().forEach { t.failed.putIfAbsent(it, "backup could not be written, nothing was changed: $TOO_MANY_PHOTOS") }
            null
        } catch (e: Exception) {
            val why = "backup could not be written, nothing was changed: ${reason(e)}"
            ids.distinct().forEach { t.failed.putIfAbsent(it, why) }
            null
        }

    /** Loads each id fresh; a contact that is gone, or whose full photo can't be read, is reported and left out. */
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

    // ---- duplicates ----

    private fun duplicates(cs: List<Contact>): List<Dup> =
        Duplicates.groups(cs).flatMap { g ->
            val keeper = Duplicates.keeper(g)
            g.filter { it.id != keeper.id }.map { Dup(it, keeper, lacks(it, keeper)) }
        }

    private fun norm(s: String) = s.trim().lowercase()

    private fun digits(s: String) = buildString { for (ch in s) if (ch.isDigit()) append(Character.digit(ch, 10)) }

    private fun sameLabel(a: String, b: String) = a.isBlank() || norm(a) == norm(b)

    /** What [c] holds that [k] doesn't: a duplicate is only removable when this is empty. */
    private fun lacks(c: Contact, k: Contact): List<String> {
        val out = ArrayList<String>()
        val n = c.name; val m = k.name
        val nameParts = listOf(
            n.prefix to m.prefix, n.first to m.first, n.middle to m.middle, n.last to m.last, n.suffix to m.suffix,
            n.phoneticFirst to m.phoneticFirst, n.phoneticLast to m.phoneticLast, n.nickname to m.nickname,
        )
        if (nameParts.any { (a, b) -> a.isNotBlank() && norm(a) != norm(b) }) out += "name"
        fun typed(label: String, mine: List<Typed>, theirs: List<Typed>, same: (String, String) -> Boolean) {
            if (mine.any { e -> e.value.isNotBlank() && theirs.none { same(e.value, it.value) && sameLabel(e.label, it.label) } }) out += label
        }
        val phoneSame = { a: String, b: String -> val x = digits(a); if (x.isNotEmpty()) x == digits(b) else norm(a) == norm(b) }
        val textSame = { a: String, b: String -> norm(a) == norm(b) }
        typed("phone", c.phones, k.phones, phoneSame)
        typed("email", c.emails, k.emails, textSame)
        fun adr(a: Address) = listOf(a.street, a.city, a.region, a.postcode, a.country, a.poBox, a.extended).map(::norm)
        if (c.addresses.any { a -> adr(a).any { it.isNotEmpty() } && k.addresses.none { adr(it) == adr(a) && sameLabel(a.label, it.label) } }) out += "address"
        if (c.org.company.isNotBlank() && norm(c.org.company) != norm(k.org.company)) out += "company"
        if (c.org.department.isNotBlank() && norm(c.org.department) != norm(k.org.department)) out += "department"
        if (c.org.title.isNotBlank() && norm(c.org.title) != norm(k.org.title)) out += "job title"
        if (c.org.units.any { u -> u.isNotBlank() && k.org.units.none { norm(it) == norm(u) } }) out += "organization units"
        typed("website", c.websites, k.websites, textSame)
        typed("date", c.events, k.events, textSame)
        typed("relation", c.relations, k.relations, textSame)
        typed("IM", c.ims, k.ims, textSame)
        if (c.notes.isNotBlank() && !k.notes.contains(c.notes.trim())) out += "notes"
        if (!k.groups.containsAll(c.groups)) out += "groups"
        if (c.photo?.isNotEmpty() == true && k.photo?.isNotEmpty() != true) out += "photo"
        // source data the model doesn't know; the Google person JSON is compared key by key
        if (c.extras.any { it.kind != PeopleMapper.EXTRA_KIND && it !in k.extras }) out += "other data"
        if (PeopleMapper.hasUnmodelledDataMissingFrom(c, k)) out += "other Google data"
        return out
    }

    // ---- text ----

    private fun effect(c: Contact, what: String) = ContactDiff(c, c, listOf(FieldDiff("Contact", nameOf(c), what)))

    private fun nameOf(c: Contact): String =
        c.name.display.trim().ifEmpty { Names.compose(c.name) }.ifEmpty { c.org.company.trim() }
            .ifEmpty { c.phones.firstOrNull()?.value ?: c.emails.firstOrNull()?.value ?: "(no name)" }

    private fun placeName(s: SourceId) = when (s) {
        SourceId.Device -> "this phone"
        is SourceId.Google -> s.email
    }

    private fun reason(e: Exception) = e.message ?: e.javaClass.simpleName

    companion object {
        /** Shown when a bulk change or restore ran out of memory (full photos are held while backing up). */
        const val TOO_MANY_PHOTOS = "Too many contacts with photos at once — select fewer"
    }
}
