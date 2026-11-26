package com.ilukezippo.vcfdesk.ui.io

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ilukezippo.vcfdesk.core.export.ExportFormat
import com.ilukezippo.vcfdesk.core.export.Exporters
import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.table.ColumnGuess
import com.ilukezippo.vcfdesk.core.table.Csv
import com.ilukezippo.vcfdesk.core.table.Field
import com.ilukezippo.vcfdesk.core.table.RowImport
import com.ilukezippo.vcfdesk.core.table.Table
import com.ilukezippo.vcfdesk.core.table.Xlsx
import com.ilukezippo.vcfdesk.core.vcard.VCardReader
import com.ilukezippo.vcfdesk.data.ContactSource
import com.ilukezippo.vcfdesk.data.PhotoUnavailableException
import com.ilukezippo.vcfdesk.ui.bulk.displayName
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The biggest file Import will read. */
const val MAX_IMPORT_BYTES = 50L * 1024 * 1024

/** [readBytes] throws this when a picked file is over [MAX_IMPORT_BYTES]. */
class FileTooLargeException(val limitBytes: Long) : java.io.IOException("file is larger than $limitBytes bytes")

/** Why a picked file could not be opened. The screen words each one. */
sealed interface ImportError {
    data class TooLarge(val limitMb: Int) : ImportError
    /** Read fine, but nothing in it is a contact (or it is empty). */
    data object NoContacts : ImportError
    /** The old binary .xls format. */
    data object OldExcel : ImportError
    /** Corrupt, encrypted, or refused as unsafe; [detail] is the parser's reason. */
    data class Unreadable(val detail: String?) : ImportError
    data class WriteFailed(val detail: String?) : ImportError
}

enum class ImportKind { VCF, CSV, XLSX }

/** What an import did; [failed] is row/contact label to reason. */
data class ImportReport(val total: Int, val written: Int, val skippedReadOnly: Int, val failed: List<Pair<String, String>>)

sealed interface ImportState {
    data object Idle : ImportState
    data object Loading : ImportState
    data class Failed(val error: ImportError) : ImportState
    /** A file is open. [count] is how many contacts it would make (null while being worked out). */
    data class Ready(
        val kind: ImportKind,
        val count: Int?,
        val sheets: List<String> = emptyList(),
        val sheet: String = "",
        val headers: List<String> = emptyList(),
        /** One example value per column, from the first row that has any. */
        val samples: List<String> = emptyList(),
        val oneMiddle: Boolean = false,
        val countryCode: String = "",
    ) : ImportState {
        val countryCodeValid get() = countryCode.isBlank() || CountryCode.valid(countryCode)
        val canImport get() = (count ?: 0) > 0 && countryCodeValid
    }
    data class Importing(val done: Int, val total: Int) : ImportState
    data class Done(val report: ImportReport) : ImportState
}

object CountryCode {
    private val re = Regex("^\\+\\d{1,4}$")
    fun valid(s: String) = re.matches(s.trim())
}

/**
 * Import: a picked file → a preview of what it would become → contacts created in a target.
 * Tables get a column mapping (guessed, editable); nothing is written until [importInto].
 * New contacts need no backup (nothing existing is changed); failures are reported per row.
 */
class ImportViewModel(
    /** Reads the picked file (off the main thread); throws [FileTooLargeException] over the limit. */
    private val readBytes: suspend (Uri) -> ByteArray,
    private val countryCode: Flow<String>,
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private val _mapping = MutableStateFlow<List<Field>>(emptyList())
    /** One [Field] per column of the open sheet. */
    val mapping: StateFlow<List<Field>> = _mapping.asStateFlow()

    private var vcards: List<Contact> = emptyList()
    private var sheets: Map<String, Table> = emptyMap()
    private var table: Table = Table(emptyList(), emptyList())
    private var job: Job? = null
    private var counting: Job? = null

    /** A file that arrived while an import was running; opened once the import's report is dismissed. */
    private var queued: Uri? = null

    fun open(uri: Uri) {
        if (isImporting || _state.value is ImportState.Done) { queued = uri; return }
        job?.cancel()
        counting?.cancel()
        _state.value = ImportState.Loading
        _mapping.value = emptyList()
        job = viewModelScope.launch {
            try {
                val cc = countryCode.first()
                val loaded = withContext(background) { parse(readBytes(uri), cc) }
                _state.value = loaded
                if (loaded is ImportState.Ready && loaded.kind != ImportKind.VCF) recount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileTooLargeException) {
                _state.value = ImportState.Failed(ImportError.TooLarge((e.limitBytes / (1024 * 1024)).toInt()))
            } catch (e: Exception) {
                _state.value = ImportState.Failed(ImportError.Unreadable(e.message))
            }
        }
    }

    private fun parse(bytes: ByteArray, cc: String): ImportState {
        fun u(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        if (bytes.isEmpty()) return ImportState.Failed(ImportError.NoContacts)
        if (u(0) == 0xD0 && u(1) == 0xCF && u(2) == 0x11 && u(3) == 0xE0) return ImportState.Failed(ImportError.OldExcel)
        if (u(0) == 'P'.code && u(1) == 'K'.code) {
            val all = try {
                Xlsx.sheets(bytes)
            } catch (e: Exception) {
                return ImportState.Failed(ImportError.Unreadable(e.message))
            }
            if (all.isEmpty()) return ImportState.Failed(ImportError.Unreadable(null))
            val usable = all.filter { it.value.headers.isNotEmpty() }
            if (usable.isEmpty()) return ImportState.Failed(ImportError.NoContacts)
            sheets = usable
            return openTable(ImportKind.XLSX, usable.keys.first(), usable.getValue(usable.keys.first()), cc)
        }
        val text = Csv.decode(bytes)
        if (text.contains("BEGIN:VCARD", ignoreCase = true)) {
            vcards = VCardReader.read(text)
            if (vcards.isEmpty()) return ImportState.Failed(ImportError.NoContacts)
            return ImportState.Ready(ImportKind.VCF, vcards.size)
        }
        val t = Csv.read(bytes)
        if (t.headers.isEmpty()) return ImportState.Failed(ImportError.NoContacts)
        sheets = emptyMap()
        return openTable(ImportKind.CSV, "", t, cc)
    }

    private fun openTable(kind: ImportKind, sheet: String, t: Table, cc: String): ImportState.Ready {
        table = t
        _mapping.value = t.headers.map { ColumnGuess.guess(it) }
        return ImportState.Ready(
            kind, null, sheets.keys.toList(), sheet, t.headers,
            samples = t.headers.indices.map { i -> t.rows.firstNotNullOfOrNull { it.getOrNull(i)?.takeIf(String::isNotBlank) }.orEmpty() },
            oneMiddle = false, countryCode = cc,
        )
    }

    fun selectSheet(name: String) {
        val s = _state.value as? ImportState.Ready ?: return
        val t = sheets[name] ?: return
        _state.value = openTable(s.kind, name, t, s.countryCode).copy(oneMiddle = s.oneMiddle)
        recount()
    }

    fun setMapping(i: Int, f: Field) {
        if (_state.value !is ImportState.Ready || i !in _mapping.value.indices) return
        _mapping.value = _mapping.value.toMutableList().also { it[i] = f }
        recount()
    }

    fun setOneMiddle(v: Boolean) {
        val s = _state.value as? ImportState.Ready ?: return
        _state.value = s.copy(oneMiddle = v)
        recount()
    }

    fun setCountryCode(v: String) {
        val s = _state.value as? ImportState.Ready ?: return
        _state.value = s.copy(countryCode = v)
        recount()
    }

    private fun effectiveCc(s: ImportState.Ready) = if (s.countryCodeValid) s.countryCode.trim() else ""

    private fun tableContacts(s: ImportState.Ready) = RowImport.toContacts(table, _mapping.value, effectiveCc(s), s.oneMiddle)

    private fun recount() {
        counting?.cancel()
        val s = _state.value as? ImportState.Ready ?: return
        if (s.kind == ImportKind.VCF) return
        counting = viewModelScope.launch {
            val n = withContext(background) { tableContacts(s).size }
            _state.value.let { cur -> if (cur is ImportState.Ready) _state.value = cur.copy(count = n) }
        }
    }

    /** Creates the contacts in [target] (in [account] for a device source; null for Google or no account). */
    fun importInto(target: ContactSource, account: AccountTag?) {
        val s = _state.value as? ImportState.Ready ?: return
        if (!s.canImport) return
        counting?.cancel()
        _state.value = ImportState.Importing(0, s.count ?: 0)
        job = viewModelScope.launch {
            try {
                val cs = withContext(background) {
                    if (s.kind == ImportKind.VCF) vcards.map { it.copy(id = "", etag = null, account = null) } else tableContacts(s)
                }
                _state.value = ImportState.Importing(0, cs.size)
                val r = withContext(background) {
                    target.create(cs, account) { d -> _state.value = ImportState.Importing(d, cs.size) }
                }
                val failed = r.failed.map { (k, why) ->
                    val label = k.removePrefix("#").toIntOrNull()?.let { i ->
                        cs.getOrNull(i)?.let(::displayName)?.takeIf { it.isNotBlank() } ?: "#${i + 1}"
                    } ?: k
                    label to why
                }
                _state.value = ImportState.Done(ImportReport(cs.size, r.written.size, r.skippedReadOnly.size, failed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ImportState.Failed(ImportError.WriteFailed(e.message))
            }
        }
    }

    /** Whether an import is writing contacts right now (it can't be cancelled or left). */
    val isImporting: Boolean get() = _state.value is ImportState.Importing

    /** Back to the start (leaving the screen, or "import another"). A running import is never cancelled; a file queued meanwhile opens now. */
    fun reset() {
        if (isImporting) return
        job?.cancel()
        counting?.cancel()
        vcards = emptyList(); sheets = emptyMap(); table = Table(emptyList(), emptyList())
        _mapping.value = emptyList()
        _state.value = ImportState.Idle
        queued?.let { queued = null; open(it) }
    }
}

// ---------------------------------------------------------------- export

enum class ExportScope { ALL, RESULTS, SELECTION }

/** A file made for sharing, waiting for the screen to hand it to the share sheet. */
data class ShareFile(val file: File, val mime: String)

/** What an export did. [skipped] is contact name to reason; they are left out, the rest is exported. */
data class ExportReport(
    val exported: Int,
    val skipped: List<Pair<String, String>>,
    /** For Excel: contacts whose photo was too big for a cell and was left blank. */
    val excelPhotosOmitted: Int,
    val fileName: String?,
)

sealed interface ExportState {
    data object Idle : ExportState
    data class Working(val done: Int, val total: Int) : ExportState
    data class Done(val report: ExportReport) : ExportState
    /** [partialFile]: bytes were already written to the chosen file, which was left there. [tooLargeFor]: the in-memory format ran out of memory. */
    data class Failed(val message: String, val partialFile: Boolean = false, val tooLargeFor: ExportFormat? = null) : ExportState
}

fun ExportFormat.mime(): String = when (this) {
    ExportFormat.ANDROID_VCF, ExportFormat.IPHONE_VCF, ExportFormat.VCARD4 -> "text/x-vcard"
    ExportFormat.GOOGLE_CSV, ExportFormat.OUTLOOK_CSV, ExportFormat.CSV -> "text/csv"
    ExportFormat.EXCEL -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    ExportFormat.SPLIT_VCF_ZIP -> "application/zip"
}

fun ExportFormat.fileName(): String = "contacts.$ext"

/**
 * Export: pick contacts (all of the source, the current search results, or the selection), load each
 * in full (photos), write the chosen format to a picked file or a share file. A contact that can't be
 * read in full is left out and reported; the rest is still exported.
 */
class ExportViewModel(
    /** Opens the document the user picked for writing. */
    private val openOutput: suspend (Uri) -> OutputStream,
    /** Removes the empty document the picker created, when nothing was exported (failures are ignored). */
    private val deleteOutput: suspend (Uri) -> Unit = {},
    /** Where a file to share is made (the app's cache). */
    private val shareFile: (name: String) -> File,
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private val _share = MutableStateFlow<ShareFile?>(null)
    val pendingShare: StateFlow<ShareFile?> = _share.asStateFlow()

    private val _allCount = MutableStateFlow<Int?>(null)
    /** How many contacts "All" exports (read-only copies counted only when included). */
    val allCount: StateFlow<Int?> = _allCount.asStateFlow()

    private val _includeReadOnly = MutableStateFlow(false)
    /** Whether "All" includes copies from read-only accounts (messaging apps such as WhatsApp); off by default. */
    val includeReadOnly: StateFlow<Boolean> = _includeReadOnly.asStateFlow()

    /** Contacts in the source, and how many of them are read-only copies; null until counted. */
    private var counts: Pair<Int, Int>? = null

    private fun publishCount() {
        _allCount.value = counts?.let { (all, ro) -> if (_includeReadOnly.value) all else all - ro }
    }

    fun setIncludeReadOnly(on: Boolean) {
        if (_state.value is ExportState.Working) return
        _includeReadOnly.value = on
        publishCount()
    }

    private var source: ContactSource? = null
    private var results: List<Contact> = emptyList()
    private var selected: Set<String> = emptySet()
    private var job: Job? = null

    val resultsCount get() = results.size
    val selectionCount get() = selected.size

    /** Called on entering the screen: what "results" and "selection" mean right now. */
    fun configure(source: ContactSource, results: List<Contact>, selected: Set<String>) {
        if (_state.value is ExportState.Working) return
        val newSource = this.source?.id != source.id
        this.source = source
        this.results = results
        this.selected = selected
        // the same source with fresh lists keeps the last report on screen
        if (newSource) { _state.value = ExportState.Idle; _share.value = null; counts = null; publishCount() }
        viewModelScope.launch {
            counts = try {
                withContext(background) { source.contacts().first().let { all -> all.size to all.count { it.account?.readOnly == true } } }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            publishCount()
        }
    }

    fun export(scope: ExportScope, f: ExportFormat, dest: Uri) = run(scope, f, dest)

    /** Makes the file in the cache and posts it to [pendingShare]. */
    fun share(scope: ExportScope, f: ExportFormat) = run(scope, f, null)

    fun shareHandled() { _share.value = null }

    fun cancel() { if (_state.value is ExportState.Working) job?.cancel() }

    fun reset() { if (_state.value !is ExportState.Working) { _state.value = ExportState.Idle; _share.value = null } }

    private fun run(scope: ExportScope, f: ExportFormat, dest: Uri?) {
        val src = source ?: return
        if (_state.value is ExportState.Working) return
        _state.value = ExportState.Working(0, 0)
        job = viewModelScope.launch {
            var wrote = false
            try {
                val listed = withContext(background) { listContacts(src, scope) }
                val skipped = ArrayList<Pair<String, String>>()
                var exported = 0
                var omitted = 0
                val file = if (dest == null) shareFile(f.fileName()) else null
                suspend fun open(): OutputStream =
                    if (dest != null) openOutput(dest) else file!!.also { it.parentFile?.mkdirs() }.outputStream().buffered()

                if (Exporters.isStreamable(f)) {
                    // chunk by chunk: load, write, drop; photos never pile up
                    withContext(background) {
                        var writer: Exporters.StreamWriter? = null
                        try {
                            var done = 0
                            for (chunk in listed.chunked(CHUNK)) {
                                val loaded = loadAll(src, chunk, skipped, done, listed.size)
                                done += chunk.size
                                if (loaded.isNotEmpty()) {
                                    val w = writer ?: open().let { o ->
                                        try { Exporters.StreamWriter(f, o) } catch (e: Throwable) { runCatching { o.close() }; throw e }
                                    }.also { writer = it }
                                    // once the output is open it may no longer be empty
                                    wrote = true
                                    w.add(loaded)
                                    exported += loaded.size
                                }
                            }
                            writer?.close()
                            writer = null
                        } finally {
                            writer?.let { runCatching { it.close() } }
                        }
                    }
                } else {
                    try {
                        withContext(background) {
                            val people = loadAll(src, listed, skipped, 0, listed.size)
                            exported = people.size
                            if (f == ExportFormat.EXCEL) omitted = Exporters.excelPhotosOmitted(people)
                            if (people.isNotEmpty()) {
                                val bytes = Exporters.export(people, f)
                                open().use { wrote = true; it.write(bytes) }
                            }
                        }
                    } catch (e: OutOfMemoryError) {
                        discard(dest, wrote)
                        _state.value = ExportState.Failed("out of memory", partialFile = dest != null && wrote, tooLargeFor = f)
                        return@launch
                    }
                }
                if (dest != null && exported == 0) discard(dest, false)
                if (file != null && exported > 0) _share.value = ShareFile(file, f.mime())
                _state.value = ExportState.Done(ExportReport(exported, skipped, omitted, if (exported > 0) f.fileName() else null))
            } catch (e: CancellationException) {
                discard(dest, wrote)
                _state.value = if (dest != null && wrote) ExportState.Failed("cancelled", partialFile = true) else ExportState.Idle
                throw e
            } catch (e: Exception) {
                discard(dest, wrote)
                _state.value = ExportState.Failed(e.message ?: e.javaClass.simpleName, partialFile = dest != null && wrote)
            }
        }
    }

    /** Deletes the empty document the picker made, unless bytes were already written to it. */
    private suspend fun discard(dest: Uri?, wrote: Boolean) {
        if (dest == null || wrote) return
        withContext(kotlinx.coroutines.NonCancellable) { try { deleteOutput(dest) } catch (e: Exception) {} }
    }

    private suspend fun listContacts(src: ContactSource, scope: ExportScope): List<Contact> {
        return when (scope) {
            // read-only accounts hold copies made by other apps (WhatsApp, Telegram…), not the user's contacts
            ExportScope.ALL -> src.contacts().first().filter { _includeReadOnly.value || it.account?.readOnly != true }
            ExportScope.RESULTS -> results
            ExportScope.SELECTION -> results.filter { it.id in selected }.let { r ->
                // a selection may include contacts the search hides
                val have = r.mapTo(HashSet()) { it.id }
                r + selected.filter { it !in have }.map { Contact(id = it) }
            }
        }
    }

    /** Loads each of [listed] in full; one that can't be read is left out and noted in [skipped]. */
    private suspend fun loadAll(
        src: ContactSource, listed: List<Contact>, skipped: MutableList<Pair<String, String>>, base: Int, total: Int,
    ): List<Contact> {
        val out = ArrayList<Contact>()
        listed.forEachIndexed { i, c ->
            _state.value = ExportState.Working(base + i, total)
            try {
                out += src.load(c.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhotoUnavailableException) {
                skipped += label(c) to "its photo could not be read"
            } catch (e: NoSuchElementException) {
                skipped += label(c) to "it no longer exists"
            } catch (e: Exception) {
                skipped += label(c) to (e.message ?: e.javaClass.simpleName)
            }
        }
        _state.value = ExportState.Working(base + listed.size, total)
        return out
    }

    private companion object { const val CHUNK = 50 }

    private fun label(c: Contact) = displayName(c).ifBlank { c.id }
}
