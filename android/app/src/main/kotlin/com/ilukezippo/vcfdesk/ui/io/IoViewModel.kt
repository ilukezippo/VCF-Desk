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

    fun open(uri: Uri) {
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

    /** Back to the start (leaving the screen, or "import another"). */
    fun reset() {
        job?.cancel()
        counting?.cancel()
        vcards = emptyList(); sheets = emptyMap(); table = Table(emptyList(), emptyList())
        _mapping.value = emptyList()
        _state.value = ImportState.Idle
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
    data class Failed(val message: String) : ExportState
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
    /** Writes the bytes to the document the user picked. */
    private val writer: suspend (Uri, ByteArray) -> Unit,
    /** Where a file to share is made (the app's cache). */
    private val shareFile: (name: String) -> File,
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private val _share = MutableStateFlow<ShareFile?>(null)
    val pendingShare: StateFlow<ShareFile?> = _share.asStateFlow()

    private val _allCount = MutableStateFlow<Int?>(null)
    val allCount: StateFlow<Int?> = _allCount.asStateFlow()

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
        if (newSource) { _state.value = ExportState.Idle; _share.value = null; _allCount.value = null }
        viewModelScope.launch {
            _allCount.value = try { withContext(background) { source.contacts().first().size } } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        }
    }

    fun export(scope: ExportScope, f: ExportFormat, dest: Uri) = run(scope, f) { bytes, name ->
        withContext(background) { writer(dest, bytes) }
        name
    }

    /** Makes the file in the cache and posts it to [pendingShare]. */
    fun share(scope: ExportScope, f: ExportFormat) = run(scope, f) { bytes, name ->
        val file = withContext(background) { shareFile(name).also { it.parentFile?.mkdirs(); it.writeBytes(bytes) } }
        _share.value = ShareFile(file, f.mime())
        name
    }

    fun shareHandled() { _share.value = null }

    fun cancel() { if (_state.value is ExportState.Working) job?.cancel() }

    fun reset() { if (_state.value !is ExportState.Working) { _state.value = ExportState.Idle; _share.value = null } }

    private fun run(scope: ExportScope, f: ExportFormat, deliver: suspend (ByteArray, String) -> String) {
        val src = source ?: return
        if (_state.value is ExportState.Working) return
        _state.value = ExportState.Working(0, 0)
        job = viewModelScope.launch {
            try {
                val (people, skipped) = withContext(background) { gather(src, scope) }
                val omitted = if (f == ExportFormat.EXCEL) Exporters.excelPhotosOmitted(people) else 0
                val name = if (people.isEmpty()) null else {
                    val bytes = withContext(background) { Exporters.export(people, f) }
                    deliver(bytes, f.fileName())
                }
                _state.value = ExportState.Done(ExportReport(people.size, skipped, omitted, name))
            } catch (e: CancellationException) {
                _state.value = ExportState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = ExportState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun gather(src: ContactSource, scope: ExportScope): Pair<List<Contact>, List<Pair<String, String>>> {
        val listed: List<Contact> = when (scope) {
            ExportScope.ALL -> src.contacts().first()
            ExportScope.RESULTS -> results
            ExportScope.SELECTION -> results.filter { it.id in selected }.let { r ->
                // a selection may include contacts the search hides
                val have = r.mapTo(HashSet()) { it.id }
                r + selected.filter { it !in have }.map { Contact(id = it) }
            }
        }
        val out = ArrayList<Contact>()
        val skipped = ArrayList<Pair<String, String>>()
        listed.forEachIndexed { i, c ->
            _state.value = ExportState.Working(i, listed.size)
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
        _state.value = ExportState.Working(listed.size, listed.size)
        return out to skipped
    }

    private fun label(c: Contact) = displayName(c).ifBlank { c.id }
}
