package com.ilukezippo.vcfdesk.ui.bulk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ilukezippo.vcfdesk.core.bulk.ContactDiff
import com.ilukezippo.vcfdesk.data.BulkOp
import com.ilukezippo.vcfdesk.data.BulkReport
import com.ilukezippo.vcfdesk.data.BulkRunner
import com.ilukezippo.vcfdesk.data.ContactSource
import com.ilukezippo.vcfdesk.data.SourceId
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
    data class Cancelled(val done: Int, val total: Int) : Stage
    /** [undone] is the result of undoing; [undoError] says why an undo could not run. */
    data class Done(
        val report: BulkReport,
        val undoing: Boolean = false,
        val undone: BulkReport? = null,
        val undoError: String? = null,
        /** Contact id to display name, for listing failures by name. */
        val names: Map<String, String> = emptyMap(),
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
        done = 0; total = ids.size
        _stage.value = Stage.Running(0, total)
        val names = s.diffs.associate { it.contact.id to displayName(it.contact) }
        undoTarget = (s.op as? BulkOp.CopyTo)?.takeIf { it.move }?.target
        job = viewModelScope.launch {
            try {
                val report = withContext(background) {
                    runner.run(source, ids, s.op) { d, t ->
                        done = d; total = t
                        // a late tick after cancelling must not bring the progress state back
                        _stage.update { if (it is Stage.Running) Stage.Running(d, t) else it }
                    }
                }
                _stage.value = Stage.Done(report, names = names)
            } catch (e: CancellationException) {
                _stage.value = Stage.Cancelled(done, total)
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
        val d = _stage.value as? Stage.Done ?: return
        val backup = d.report.backup ?: return
        if (d.undoing || d.undone != null) return
        _stage.value = d.copy(undoing = true, undoError = null)
        job = viewModelScope.launch {
            try {
                val r = withContext(background) { runner.undo(source, backup, undoTarget) }
                _stage.value = d.copy(undoing = false, undone = r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _stage.value = d.copy(undoing = false, undoError = e.message ?: e.javaClass.simpleName)
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
