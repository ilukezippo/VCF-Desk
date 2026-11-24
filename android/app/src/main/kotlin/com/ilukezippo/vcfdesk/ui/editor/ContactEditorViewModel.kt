package com.ilukezippo.vcfdesk.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.text.Names
import com.ilukezippo.vcfdesk.data.ContactSource
import com.ilukezippo.vcfdesk.data.PhotoUnavailableException
import com.ilukezippo.vcfdesk.data.WriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface EditorStatus {
    data object Loading : EditorStatus
    data object Ready : EditorStatus
    /** The contact has a full photo that could not be read; editing is blocked so it can't be saved without it. */
    data object PhotoUnavailable : EditorStatus
    /** The contact no longer exists. */
    data object Gone : EditorStatus
    data class Error(val message: String) : EditorStatus
}

/** Edits one contact of one source. All state changes happen on the main thread. */
class ContactEditorViewModel(
    private val sourceProvider: () -> ContactSource,
    private val id: String,
    private val photoScaler: PhotoScaler,
    /** Where images are decoded and scaled (tests pass their own). */
    private val scaleDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _contact = MutableStateFlow<Contact?>(null)
    val contact: StateFlow<Contact?> = _contact.asStateFlow()
    private val _status = MutableStateFlow<EditorStatus>(EditorStatus.Loading)
    val status: StateFlow<EditorStatus> = _status.asStateFlow()
    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    private val _readOnly = MutableStateFlow(false)
    val readOnly: StateFlow<Boolean> = _readOnly.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    /** Why the last save failed (a stale etag shows here: the screen offers "Reload"). */
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()
    /** The last picked image could not be read. */
    private val _photoError = MutableStateFlow(false)
    val photoError: StateFlow<Boolean> = _photoError.asStateFlow()

    /** Group names the source knows, for the group picker. */
    private val _groupChoices = MutableStateFlow<List<String>>(emptyList())
    val groupChoices: StateFlow<List<String>> = _groupChoices.asStateFlow()

    private var loaded: Contact? = null
    private val undoStack = ArrayList<Contact>()
    private val redoStack = ArrayList<Contact>()
    private var lastKey: String? = null

    init {
        reload()
        viewModelScope.launch {
            try { _groupChoices.value = sourceProvider().groups() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        }
    }

    /** Loads the contact afresh, dropping any edits and the undo history. */
    fun reload() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _status.value = EditorStatus.Loading
        _saveError.value = null
        try {
            val c = sourceProvider().load(id)
            loaded = c
            undoStack.clear(); redoStack.clear(); lastKey = null
            _contact.value = c
            _readOnly.value = c.account?.readOnly == true
            _status.value = EditorStatus.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhotoUnavailableException) {
            fail(EditorStatus.PhotoUnavailable)
        } catch (e: NoSuchElementException) {
            fail(EditorStatus.Gone)
        } catch (e: Exception) {
            fail(EditorStatus.Error(e.message ?: e.javaClass.simpleName))
        }
        refreshFlags()
    }

    private fun fail(s: EditorStatus) {
        loaded = null
        _contact.value = null
        _status.value = s
    }

    private fun refreshFlags() {
        _isDirty.value = loaded != null && _contact.value != loaded
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private fun editable() = _status.value == EditorStatus.Ready && !_readOnly.value && !_saving.value

    /**
     * Applies [transform] to the contact. Edits with the same non-null [key] that follow each other
     * (typing in one field) share one undo step. When the name parts change and the display name was
     * the composed one, it is composed again (the same rule as the bulk edit).
     */
    fun edit(key: String?, transform: (Contact) -> Contact) {
        if (!editable()) return
        val cur = _contact.value ?: return
        var next = transform(cur)
        if (next.name != cur.name && next.name.display == cur.name.display &&
            cur.name.display == Names.compose(cur.name)
        ) {
            next = next.copy(name = next.name.copy(display = Names.compose(next.name)))
        }
        if (next == cur) return
        if (key == null || key != lastKey || undoStack.isEmpty()) undoStack.add(cur)
        lastKey = key
        redoStack.clear()
        _contact.value = next
        _saveError.value = null
        refreshFlags()
    }

    fun edit(transform: (Contact) -> Contact) = edit(null, transform)

    fun undo() {
        if (!editable() || undoStack.isEmpty()) return
        redoStack.add(_contact.value ?: return)
        _contact.value = undoStack.removeAt(undoStack.lastIndex)
        lastKey = null
        refreshFlags()
    }

    fun redo() {
        if (!editable() || redoStack.isEmpty()) return
        undoStack.add(_contact.value ?: return)
        _contact.value = redoStack.removeAt(redoStack.lastIndex)
        lastKey = null
        refreshFlags()
    }

    /** Scales the picked image to at most 720 px and sets it as the photo. */
    fun setPhoto(bytes: ByteArray) {
        if (!editable()) return
        viewModelScope.launch {
            val scaled = try {
                withContext(scaleDispatcher) { photoScaler.scale(bytes) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            if (scaled == null) { _photoError.value = true; return@launch }
            _photoError.value = false
            edit { it.copy(photo = scaled, thumbnail = scaled) }
        }
    }

    /** The picked image could not be read at all. */
    fun reportPhotoError() { _photoError.value = true }

    /** Stops this editor's work; call when the editor is closed. */
    fun dispose() { viewModelScope.coroutineContext.cancel() }

    fun clearPhotoError() { _photoError.value = false }

    /** Rows the user added and left empty are not written. */
    private fun pruned(c: Contact): Contact = c.copy(
        phones = c.phones.filter { it.value.isNotBlank() },
        emails = c.emails.filter { it.value.isNotBlank() },
        websites = c.websites.filter { it.value.isNotBlank() },
        events = c.events.filter { it.value.isNotBlank() },
        relations = c.relations.filter { it.value.isNotBlank() },
        ims = c.ims.filter { it.value.isNotBlank() },
        addresses = c.addresses.filter { a ->
            listOf(a.street, a.city, a.region, a.postcode, a.country, a.poBox, a.extended).any { it.isNotBlank() }
        },
    )

    /**
     * Writes the contact if it changed, then loads it again (its etag changed with the write).
     * Returns what the source reported; nothing is written for an unchanged, read-only or unloaded contact.
     */
    suspend fun save(): WriteResult {
        val none = WriteResult(emptyList(), emptyList(), emptyMap())
        if (!editable() || !_isDirty.value) return none
        val c = _contact.value ?: return none
        _saving.value = true
        _saveError.value = null
        try {
            val r = try {
                sourceProvider().update(listOf(pruned(c)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WriteResult(emptyList(), emptyList(), mapOf(c.id to (e.message ?: e.javaClass.simpleName)))
            }
            when {
                c.id in r.written -> { _saving.value = false; load() }
                else -> _saveError.value = r.failed[c.id] ?: "read-only"
            }
            return r
        } finally {
            _saving.value = false
        }
    }
}
