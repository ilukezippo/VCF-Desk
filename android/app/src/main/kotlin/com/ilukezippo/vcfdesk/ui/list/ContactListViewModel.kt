package com.ilukezippo.vcfdesk.ui.list

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.data.ContactSource
import com.ilukezippo.vcfdesk.data.SourceId
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListState(
    val source: SourceId = SourceId.Device,
    val sources: List<SourceId> = listOf(SourceId.Device),
    val query: String = "",
    val accountFilter: String? = null,
    val groupFilter: String? = null,
    val sortAsc: Boolean = true,
    val items: List<Contact> = emptyList(),
    val selected: Set<String> = emptySet(),
    val loading: Boolean = true,
    /** The current Google account is also a synced account on this phone. */
    val alsoOnPhone: Boolean = false,
    /** Account labels present in the current source (for the filter menu). */
    val accounts: List<String> = emptyList(),
    /** Group names present in the current source (for the filter menu). */
    val groups: List<String> = emptyList(),
    /** Why the current source could not be read, if it could not. */
    val error: String? = null,
)

/** Tells whether a Google email is also a `com.google` account on the phone with contact sync on. */
fun interface AccountSyncChecker {
    fun isSyncedOnPhone(email: String): Boolean
}

class SystemAccountSyncChecker(context: Context) : AccountSyncChecker {
    private val app = context.applicationContext
    override fun isSyncedOnPhone(email: String): Boolean = try {
        ContentResolver.getMasterSyncAutomatically() &&
            AccountManager.get(app).getAccountsByType("com.google").any {
                it.name.equals(email, ignoreCase = true) &&
                    ContentResolver.getSyncAutomatically(it, ContactsContract.AUTHORITY)
            }
    } catch (e: SecurityException) {
        false
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ContactListViewModel(
    private val sourceFor: (SourceId) -> ContactSource,
    googleAccounts: Flow<List<String>>,
    private val saveGoogleAccount: suspend (String) -> Unit,
    private val syncChecker: AccountSyncChecker,
    private val collator: Comparator<in String> = Collator.getInstance(Locale.getDefault()),
    initialSource: SourceId = SourceId.Device,
    /** Where indexing, filtering, sorting and the sync check run (tests pass their own). */
    private val background: CoroutineDispatcher = Dispatchers.Default,
    /** Called each time the filter/sort stage runs (test hook). */
    private val onFilter: () -> Unit = {},
) : ViewModel() {

    /** Everything that changes which contacts are shown; selection is deliberately not in here. */
    private data class Controls(
        val source: SourceId,
        val query: String = "",
        val accountFilter: String? = null,
        val groupFilter: String? = null,
        val sortAsc: Boolean = true,
    )

    private class Loaded(val source: SourceId, val contacts: List<Contact>, val loading: Boolean, val error: String? = null)

    /** A load with the search text of each contact folded once. */
    private class Indexed(val loaded: Loaded, val texts: List<Searching.Prepared>) {
        val accounts = loaded.contacts.mapNotNull { it.account?.let(::accountFilterName)?.takeIf(String::isNotEmpty) }.distinct()
        val groups = loaded.contacts.flatMap { it.groups }.distinct()
    }

    private class View(
        val source: SourceId, val query: String, val accountFilter: String?, val groupFilter: String?, val sortAsc: Boolean,
        val items: List<Contact>, val accounts: List<String>, val groups: List<String>, val loading: Boolean, val error: String?,
    )

    private val controls = MutableStateFlow(Controls(initialSource))
    /** A selection belongs to the source whose list it was made in; it is ignored for any other source. */
    private class Selection(val source: SourceId, val ids: Set<String>)
    private val selected = MutableStateFlow<Selection?>(null)

    private fun idsFor(sel: Selection?, source: SourceId): Set<String> = if (sel != null && sel.source == source) sel.ids else emptySet()
    private val loaded = MutableStateFlow(Loaded(initialSource, emptyList(), loading = true))
    private val _state = MutableStateFlow(ListState(source = initialSource, sources = listOf(SourceId.Device)))
    val state: StateFlow<ListState> = _state

    init {
        // Read the current source; a new source cancels the previous read.
        viewModelScope.launch {
            var job: kotlinx.coroutines.Job? = null
            var current: SourceId? = null
            controls.collect { c ->
                if (c.source == current) return@collect
                current = c.source
                job?.cancel()
                loaded.value = Loaded(c.source, emptyList(), loading = true)
                job = launch {
                    try {
                        sourceFor(c.source).contacts().collect { list ->
                            loaded.value = Loaded(c.source, list, loading = false)
                            val ids = list.mapTo(HashSet()) { it.id }
                            selected.update { sel ->
                                when {
                                    sel == null -> null
                                    sel.source != c.source -> null
                                    sel.ids.all { it in ids } -> sel
                                    else -> Selection(c.source, sel.ids.filterTo(LinkedHashSet()) { it in ids })
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        loaded.value = Loaded(c.source, emptyList(), loading = false, error = e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }

        // Heavy part, off the main thread: fold search text once per load, then filter + sort.
        val indexed = loaded.mapLatest { l -> Indexed(l, l.contacts.map(Searching::prepareContact)) }.flowOn(background)
        val view = combine(controls, indexed) { c, ix -> filterAndSort(c, ix) }.flowOn(background)

        // Source list and the "also on phone" check: only when the source or the account list changes.
        val currentSource = controls.map { it.source }.distinctUntilChanged()
        // Google contacts are reached through the Google accounts synced on the phone, so the
        // phone is the only source offered (no separate Google sign-in).
        val sources = combine(currentSource, googleAccounts) { _, _ -> listOf<SourceId>(SourceId.Device) }
        val alsoOnPhone = combine(currentSource, googleAccounts) { s, _ ->
            (s as? SourceId.Google)?.let { syncChecker.isSyncedOnPhone(it.email) } ?: false
        }.flowOn(background)

        viewModelScope.launch {
            combine(view, selected, sources, alsoOnPhone) { v, sel, src, also ->
                ListState(
                    source = v.source, sources = src, query = v.query, accountFilter = v.accountFilter,
                    groupFilter = v.groupFilter, sortAsc = v.sortAsc, items = v.items, selected = idsFor(sel, v.source),
                    loading = v.loading, alsoOnPhone = also, accounts = v.accounts, groups = v.groups, error = v.error,
                )
            }.collect { _state.value = it }
        }
    }

    private fun filterAndSort(c: Controls, ix: Indexed): View {
        onFilter()
        val l = ix.loaded
        val current = l.source == c.source
        val q = Searching.prepare(c.query)
        val matching = ArrayList<Contact>()
        if (current) {
            for (i in l.contacts.indices) {
                val it = l.contacts[i]
                if ((c.accountFilter == null || it.account?.let(::accountFilterName) == c.accountFilter) &&
                    (c.groupFilter == null || c.groupFilter in it.groups) &&
                    Searching.matches(ix.texts[i], q)
                ) matching += it
            }
        }
        return View(
            source = c.source, query = c.query, accountFilter = c.accountFilter, groupFilter = c.groupFilter,
            sortAsc = c.sortAsc, items = sorted(matching, c.sortAsc),
            accounts = if (current) ix.accounts.sortedWith(collator) else emptyList(),
            groups = if (current) ix.groups.sortedWith(collator) else emptyList(),
            loading = l.loading || !current,
            error = l.error.takeIf { current },
        )
    }

    /** By display name, collated; empty names last in either direction; ties keep the source order. */
    private fun sorted(list: List<Contact>, asc: Boolean): List<Contact> =
        list.sortedWith { a, b ->
            val x = a.name.display.trim()
            val y = b.name.display.trim()
            when {
                x.isEmpty() && y.isEmpty() -> 0
                x.isEmpty() -> 1
                y.isEmpty() -> -1
                else -> collator.compare(x, y).let { if (asc) it else -it }
            }
        }

    fun setQuery(q: String) = controls.update { it.copy(query = q) }
    fun setAccountFilter(a: String?) = controls.update { it.copy(accountFilter = a) }
    fun setGroupFilter(g: String?) = controls.update { it.copy(groupFilter = g) }
    fun toggleSort() = controls.update { it.copy(sortAsc = !it.sortAsc) }

    /** The source the user is looking at (the one whose rows are on screen). */
    private fun viewSource() = _state.value.source

    fun toggleSelect(id: String) {
        val src = viewSource()
        selected.update { cur ->
            val ids = idsFor(cur, src)
            Selection(src, if (id in ids) ids - id else ids + id)
        }
    }

    /** Emails of the Google accounts whose contacts are on the phone (from what's loaded). */
    fun phoneGoogleAccounts(): List<String> =
        loaded.value.contacts.mapNotNull { c -> c.account?.takeIf { it.type == "com.google" }?.name?.takeIf(String::isNotBlank) }
            .distinct().sorted()

    fun selectAll() {
        val l = loaded.value
        if (l.source != controls.value.source) return
        selected.value = Selection(l.source, l.contacts.mapTo(LinkedHashSet()) { it.id })
    }

    fun selectResults() {
        val st = _state.value
        selected.value = Selection(st.source, st.items.mapTo(LinkedHashSet()) { it.id })
    }

    fun clearSelection() { selected.value = null }

    /** The accounts the selected contacts are in, from everything loaded (not just what the filter shows). */
    fun selectionAccounts(): Set<com.ilukezippo.vcfdesk.core.model.AccountTag?> {
        val l = loaded.value
        val ids = idsFor(selected.value, l.source)
        return l.contacts.filter { it.id in ids }.mapTo(HashSet()) { it.account }
    }

    fun switchSource(s: SourceId) {
        if (controls.value.source == s) return
        selected.value = null
        controls.update { it.copy(source = s, accountFilter = null, groupFilter = null) }
    }

    /** Remembers the (already authorised) Google account and shows it. */
    fun addGoogleAccount(email: String) {
        val e = email.trim().lowercase()
        if (e.isEmpty()) return
        viewModelScope.launch {
            saveGoogleAccount(e)
            switchSource(SourceId.Google(e))
        }
    }
}

/** Case-insensitive search over every text field; phones also match by digits only. */
internal object Searching {
    class Query(val text: String, val digits: String)

    fun prepare(raw: String): Query {
        val text = fold(raw.trim())
        // Digit matching only when the query looks like (part of) a phone number.
        val phoneLike = text.isNotEmpty() && text.all { it.isDigit() || it in "+-() ." }
        return Query(text, if (phoneLike) digitsOf(text) else "")
    }

    /** A contact's searchable text, folded once per load. */
    class Prepared(val phoneDigits: List<String>, val fields: List<String>)

    fun prepareContact(c: Contact) = Prepared(c.phones.map { digitsOf(it.value) }, fields(c).map(::fold).toList())

    fun matches(p: Prepared, q: Query): Boolean {
        if (q.text.isEmpty()) return true
        if (q.digits.isNotEmpty() && p.phoneDigits.any { it.contains(q.digits) }) return true
        return p.fields.any { it.contains(q.text) }
    }

    /** Lower-case, with every Unicode decimal digit turned into its ASCII digit. */
    private fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            val d = if (ch.isDigit()) Character.digit(ch, 10) else -1
            sb.append(if (d >= 0) ('0' + d) else ch)
        }
        return sb.toString()
    }

    private fun digitsOf(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val d = if (ch.isDigit()) Character.digit(ch, 10) else -1
            if (d >= 0) sb.append('0' + d)
        }
        return sb.toString()
    }

    private fun fields(c: Contact): Sequence<String> = sequence {
        val n = c.name
        yieldAll(listOf(n.display, n.prefix, n.first, n.middle, n.last, n.suffix, n.phoneticFirst, n.phoneticLast, n.nickname))
        for (t in c.phones + c.emails + c.websites + c.events + c.relations + c.ims) { yield(t.value); yield(t.label) }
        for (a in c.addresses) yieldAll(listOf(a.label, a.street, a.city, a.region, a.postcode, a.country, a.poBox, a.extended))
        yieldAll(listOf(c.org.company, c.org.department, c.org.title) + c.org.units)
        yield(c.notes)
        yieldAll(c.groups)
    }.filter { it.isNotEmpty() }
}

/**
 * How an account is named in the account filter: "label · name", so two Google accounts on the
 * phone can be told apart; just the label when the account has no name of its own.
 */
fun accountFilterName(a: com.ilukezippo.vcfdesk.core.model.AccountTag): String {
    val name = a.name.trim()
    // Only a real user name (an email) tells two accounts apart; app accounts like WhatsApp or the
    // phone's own "vnd.sec.contact.phone" name just repeat the type, so show the label alone.
    return if ('@' !in name || name.equals(a.label.trim(), ignoreCase = true)) a.label else "${a.label} · $name"
}
