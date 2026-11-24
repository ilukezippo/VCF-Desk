package com.ilukezippo.vcfdesk.ui.editor

import androidx.lifecycle.ViewModel
import com.ilukezippo.vcfdesk.data.SourceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps exactly one editor: the one for the contact that is open. Opening a contact always starts a
 * fresh editor (so it loads fresh data); closing or discarding drops it. Leaving a dirty editor for
 * something else goes through [requestSwitch], which asks the user first.
 */
class EditorHolder(private val create: (SourceId, String) -> ContactEditorViewModel) {
    class Open(val source: SourceId, val id: String, val editor: ContactEditorViewModel)

    private val _current = MutableStateFlow<Open?>(null)
    val current: StateFlow<Open?> = _current.asStateFlow()

    /** Set while the user is being asked whether to discard; [confirmDiscard] and [keepEditing] resolve it. */
    class Pending(val proceed: () -> Unit, val onKeep: () -> Unit)
    private val _pending = MutableStateFlow<Pending?>(null)
    val pendingSwitch: StateFlow<Pending?> = _pending.asStateFlow()

    val isDirty: Boolean get() = _current.value?.editor?.isDirty?.value == true

    /** The editor for [id]; a different contact's editor is dropped, the same contact's is kept (it may hold edits). */
    fun open(source: SourceId, id: String): ContactEditorViewModel {
        val cur = _current.value
        if (cur != null && cur.source == source && cur.id == id) return cur.editor
        cur?.editor?.dispose()
        val o = Open(source, id, create(source, id))
        _current.value = o
        return o.editor
    }

    /** Drops the editor (and its edits). */
    fun close() {
        _current.value?.editor?.dispose()
        _current.value = null
        _pending.value = null
    }

    /**
     * Runs [proceed] to move away from the open editor. Clean (or no) editors are closed and [proceed] runs at once;
     * a dirty one makes the switch wait for [confirmDiscard] / [keepEditing] ([onKeep] undoes a move that already happened).
     */
    fun requestSwitch(onKeep: () -> Unit = {}, proceed: () -> Unit) {
        if (!isDirty) { close(); proceed(); return }
        if (_pending.value == null) _pending.value = Pending(proceed, onKeep)
    }

    fun confirmDiscard() {
        val p = _pending.value
        close()
        p?.proceed?.invoke()
    }

    fun keepEditing() {
        val p = _pending.value
        _pending.value = null
        p?.onKeep?.invoke()
    }
}

/** Survives rotation and folding; closes the editor with the activity. */
class EditorHolderViewModel(val holder: EditorHolder) : ViewModel() {
    override fun onCleared() = holder.close()
}
