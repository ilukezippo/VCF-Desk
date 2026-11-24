package com.ilukezippo.vcfdesk.ui.bulk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ilukezippo.vcfdesk.core.bulk.ContactDiff
import com.ilukezippo.vcfdesk.data.BulkOp
import com.ilukezippo.vcfdesk.data.BulkReport
import com.ilukezippo.vcfdesk.data.BulkRunner
import com.ilukezippo.vcfdesk.data.ContactSource
import com.ilukezippo.vcfdesk.data.SourceId
import com.ilukezippo.vcfdesk.data.backup.Backup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Stage {
    /** Choosing a change. */
    data object Compose : Stage
    /** What the change would do; [finalConfirm] is the second step a delete needs. */
    data class Preview(val op: BulkOp, val diffs: List<ContactDiff>, val finalConfirm: Boolean = false) : Stage
    data class Running(val done: Int, val total: Int) : Stage
    /** Stopped by the user; what was written before is kept, with its backup. */
    data class Cancelled(
        val done: Int,
        val total: Int,
        /** The backup taken before writing, if the run got that far; undo uses it. */
        val backup: Backup? = null,
        /** For a move: where copies may have been made (and are not removed by undo if unrecorded). */
        val movedTo: SourceId? = null,
        val undoing: Boolean = false,
        val undone: BulkReport? = null,
        val undoError: String? = null,
    ) : Stage
    /** [undone] is the result of undoing; [undoError] says why an undo could not run. */
    data class Done(
        val report: BulkReport,
        val undoing: Boolean = false,
        val undone: BulkReport? = null,
        val undoError: String? = null,
        /** Contact id to display name, for listing failures by name. */
        val names: Map<String, String> = emptyMap(),
        /** For a plain copy: where the copies went (no backup is taken; they can't be undone here). */
        val copiedTo: SourceId? = null,
    ) : Stage
    data class Error(val message: String) : Stage
}

/**
 * One bulk change over [ids] in [sourceId]: pick a change, preview it, confirm, watch it run, undo.
 * The work runs on [background]; cancelling cancels the running job (the runner and sources let
 * [CancellationException] through), and whatever was already written stays, with its backup.
 */
class BulkViewModel(
    private val runner: BulkRunner,
    val sourceId: SourceId,
    private val sourceProvider: (SourceId) -> ContactSource,
    val ids: List<String>,
    countryCode: Flow<String>,
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _stage = MutableStateFlow<Stage>(Stage.Compose)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    private val _busy = MutableStateFlow(false)
    /** True while a preview is being worked out. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _country = MutableStateFlow("+965")
    /** The default country code from Settings, for "Add country code". */
    val country: StateFlow<String> = _country.asStateFlow()

    private var job: Job? = null
    private var undoTarget: ContactSource? = null

    @Volatile private var done = 0
    @Volatile private var total = 0
    @Volatile private var lastBackup: Backup? = null
    private var movedTo: SourceId? = null
    private var copiedTo: SourceId? = null

    init {
        viewModelScope.launch { countryCode.collect { _country.value = it } }
    }

    private val source get() = sourceProvider(sourceId)

    fun preview(op: BulkOp) {
        if (_stage.value != Stage.Compose || _busy.value) return
        _busy.value = true
        job = viewModelScope.launch {
            try {
                val diffs = withContext(background) { runner.preview(source, ids, op) }
                _stage.value = Stage.Preview(op, diffs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _stage.value = Stage.Error(e.message ?: e.javaClass.simpleName)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Runs the previewed change; a delete asks once more first, and an empty preview cannot run. */
    fun confirm() {
        val s = _stage.value as? Stage.Preview ?: return
        if (s.diffs.isEmpty()) return
        if (s.op is BulkOp.Delete && !s.finalConfirm) {
            _stage.value = s.copy(finalConfirm = true)
            return
        }
        done = 0; total = ids.size; lastBackup = null
        movedTo = (s.op as? BulkOp.CopyTo)?.takeIf { it.move }?.target?.id
        copiedTo = (s.op as? BulkOp.CopyTo)?.takeIf { !it.move }?.target?.id
        _stage.value = Stage.Running(0, total)
        val names = s.diffs.associate { it.contact.id to displayName(it.contact) }
        undoTarget = (s.op as? BulkOp.CopyTo)?.takeIf { it.move }?.target
        job = viewModelScope.launch {
            try {
                val report = withContext(background) {
                    runner.run(source, ids, s.op, onBackup = { lastBackup = it }) { d, t ->
                        done = d; total = t
                        // a late tick after cancelling must not bring the progress state back
                        _stage.update { if (it is Stage.Running) Stage.Running(d, t) else it }
                    }
                }
                _stage.value = Stage.Done(report, names = names, copiedTo = copiedTo)
            } catch (e: CancellationException) {
                _stage.value = Stage.Cancelled(done, total, lastBackup, movedTo)
                throw e
            } catch (e: Exception) {
                _stage.value = Stage.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Stops a running change between batches. */
    fun cancel() {
        if (_stage.value is Stage.Running) job?.cancel()
    }

    fun undoLast() {
        when (val d = _stage.value) {
            is Stage.Done -> {
                val backup = d.report.backup ?: return
                if (d.undoing || d.undone != null) return
                _stage.value = d.copy(undoing = true, undoError = null)
                undo(backup, { _stage.value = d.copy(undoing = false, undone = it) }, { _stage.value = d.copy(undoing = false, undoError = it) })
            }
            is Stage.Cancelled -> {
                val backup = d.backup ?: return
                if (d.undoing || d.undone != null) return
                _stage.value = d.copy(undoing = true, undoError = null)
                undo(backup, { _stage.value = d.copy(undoing = false, undone = it) }, { _stage.value = d.copy(undoing = false, undoError = it) })
            }
            else -> {}
        }
    }

    private fun undo(backup: Backup, ok: (BulkReport) -> Unit, err: (String) -> Unit) {
        job = viewModelScope.launch {
            try {
                ok(withContext(background) { runner.undo(source, backup, undoTarget) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Back to choosing a change (from a preview, an error or a finished run). */
    fun close() {
        if (_stage.value is Stage.Running) return
        _stage.value = Stage.Compose
    }

    /** Whether the run is over, so the selection it was made from may go. */
    val finished: Boolean get() = _stage.value.let { it is Stage.Done || it is Stage.Cancelled }

    fun dispose() { job?.cancel() }

    override fun onCleared() { dispose() }
}

/** How a contact is named in lists: its display name, else what it has. */
fun displayName(c: com.ilukezippo.vcfdesk.core.model.Contact): String =
    c.name.display.trim().ifEmpty { com.ilukezippo.vcfdesk.core.text.Names.compose(c.name) }
        .ifEmpty { c.org.company.trim() }
        .ifEmpty { c.phones.firstOrNull()?.value ?: c.emails.firstOrNull()?.value ?: "" }
