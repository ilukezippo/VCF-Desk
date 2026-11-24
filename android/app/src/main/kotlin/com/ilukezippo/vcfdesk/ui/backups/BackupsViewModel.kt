package com.ilukezippo.vcfdesk.ui.backups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ilukezippo.vcfdesk.data.BulkReport
import com.ilukezippo.vcfdesk.data.BulkRunner
import com.ilukezippo.vcfdesk.data.ContactSource
import com.ilukezippo.vcfdesk.data.SourceId
import com.ilukezippo.vcfdesk.data.backup.Backup
import com.ilukezippo.vcfdesk.data.backup.BackupStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why a restore could not run (the screen words it). */
sealed interface RestoreError {
    /** A Google account the backup (or its move target) belongs to is no longer signed in. */
    data class AccountMissing(val email: String) : RestoreError
    data class Failed(val message: String) : RestoreError
}

sealed interface RestoreState {
    data object Idle : RestoreState
    data class Running(val backup: Backup) : RestoreState
    data class Done(val backup: Backup, val report: BulkReport) : RestoreState
    data class Failed(val backup: Backup, val error: RestoreError) : RestoreState
}

/** The backups taken before bulk writes, newest first; restore one with [BulkRunner.undo]. */
class BackupsViewModel(
    private val store: BackupStore,
    private val runner: BulkRunner,
    private val sourceFor: (SourceId) -> ContactSource,
    private val googleAccounts: Flow<List<String>>,
    private val background: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _items = MutableStateFlow<List<Backup>>(emptyList())
    val items: StateFlow<List<Backup>> = _items.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _restore = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restore: StateFlow<RestoreState> = _restore.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _items.value = withContext(background) { store.list() }
            _loaded.value = true
        }
    }

    fun dismissResult() {
        if (_restore.value !is RestoreState.Running) _restore.value = RestoreState.Idle
    }

    fun restore(b: Backup) {
        if (_restore.value is RestoreState.Running) return
        _restore.value = RestoreState.Running(b)
        viewModelScope.launch {
            try {
                val accounts = googleAccounts.first()
                fun missing(id: SourceId) = (id as? SourceId.Google)?.takeIf { g -> accounts.none { it.equals(g.email, true) } }
                val outcome = withContext(background) {
                    missing(b.source)?.let { return@withContext RestoreState.Failed(b, RestoreError.AccountMissing(it.email)) }
                    val move = store.move(b)
                    move?.target?.let(::missing)?.let { return@withContext RestoreState.Failed(b, RestoreError.AccountMissing(it.email)) }
                    RestoreState.Done(b, runner.undo(sourceFor(b.source), b, move?.let { sourceFor(it.target) }))
                }
                _restore.value = outcome
                // an undo makes its own safety backup
                _items.value = withContext(background) { store.list() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _restore.value = RestoreState.Failed(b, RestoreError.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }
}
