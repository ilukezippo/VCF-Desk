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

    /** Where the user was going when a dirty editor stopped them; plain data, so it survives rotation. */
    sealed interface PendingTarget {
        data class OpenContact(val id: String) : PendingTarget
        /** A selection started (already happened). */
        data object Selection : PendingTarget
        /** The source changed (already happened); [back] is the editor's source. */
        data class SourceSwitch(val back: SourceId) : PendingTarget
        data object Leave : PendingTarget
    }

    /** Set while the user is being asked whether to discard; [confirmDiscard] and [keepEditing] resolve it. */
    private val _pending = MutableStateFlow<PendingTarget?>(null)
    val pendingSwitch: StateFlow<PendingTarget?> = _pending.asStateFlow()

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
     * Asks to move away from the open editor towards [target]. A clean (or no) editor is closed and true is returned:
     * go ahead. A dirty one stays, [target] becomes pending and false is returned; the caller shows the dialog and
     * resolves it with [confirmDiscard] / [keepEditing], which return the target to act on.
     */
    fun requestSwitch(target: PendingTarget): Boolean {
        if (!isDirty) { close(); return true }
        if (_pending.value == null) _pending.value = target
        return false
    }

    /** Drops the editor and its edits; returns where the user was going. */
    fun confirmDiscard(): PendingTarget? {
        val t = _pending.value
        close()
        return t
    }

    /** Keeps the editor; returns where the user was going, so a move that already happened can be undone. */
    fun keepEditing(): PendingTarget? {
        val t = _pending.value
        _pending.value = null
        return t
    }
}

/** Survives rotation and folding; closes the editor with the activity. */
class EditorHolderViewModel(val holder: EditorHolder) : ViewModel() {
    override fun onCleared() = holder.close()
}
