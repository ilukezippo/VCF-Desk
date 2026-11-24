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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
) : ViewModel() {

    private data class Controls(
        val source: SourceId,
        val query: String = "",
        val accountFilter: String? = null,
        val groupFilter: String? = null,
        val sortAsc: Boolean = true,
        val selected: Set<String> = emptySet(),
    )

    private class Loaded(val source: SourceId, val contacts: List<Contact>, val loading: Boolean, val error: String? = null)

    private val controls = MutableStateFlow(Controls(initialSource))
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
                            controls.update { it.copy(selected = it.selected.filterTo(HashSet()) { id -> id in ids }) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        loaded.value = Loaded(c.source, emptyList(), loading = false, error = e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(controls, loaded, googleAccounts) { c, l, accounts -> build(c, l, accounts) }
                .collect { _state.value = it }
        }
    }

    private fun build(c: Controls, l: Loaded, accounts: List<String>): ListState {
        val all = if (l.source == c.source) l.contacts else emptyList()
        val q = Searching.prepare(c.query)
        val filtered = all.filter {
            (c.accountFilter == null || it.account?.label == c.accountFilter) &&
                (c.groupFilter == null || c.groupFilter in it.groups) &&
                Searching.matches(it, q)
        }
        val sources = (listOf<SourceId>(SourceId.Device) + accounts.map { SourceId.Google(it) } + c.source).distinct()
        return ListState(
            source = c.source,
            sources = sources,
            query = c.query,
            accountFilter = c.accountFilter,
            groupFilter = c.groupFilter,
            sortAsc = c.sortAsc,
            items = sorted(filtered, c.sortAsc),
            selected = c.selected,
            loading = l.loading || l.source != c.source,
            alsoOnPhone = (c.source as? SourceId.Google)?.let { syncChecker.isSyncedOnPhone(it.email) } ?: false,
            accounts = all.mapNotNull { it.account?.label?.takeIf(String::isNotEmpty) }.distinct().sortedWith(collator),
            groups = all.flatMap { it.groups }.distinct().sortedWith(collator),
            error = l.error.takeIf { l.source == c.source },
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

    fun toggleSelect(id: String) = controls.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun selectAll() {
        val l = loaded.value
        if (l.source != controls.value.source) return
        val ids = l.contacts.mapTo(LinkedHashSet()) { it.id }
        controls.update { it.copy(selected = ids) }
    }

    fun selectResults() {
        val ids = _state.value.items.mapTo(LinkedHashSet()) { it.id }
        controls.update { it.copy(selected = ids) }
    }

    fun clearSelection() = controls.update { it.copy(selected = emptySet()) }

    fun switchSource(s: SourceId) = controls.update {
        if (it.source == s) it
        else it.copy(source = s, accountFilter = null, groupFilter = null, selected = emptySet())
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

    fun matches(c: Contact, q: Query): Boolean {
        if (q.text.isEmpty()) return true
        if (q.digits.isNotEmpty() && c.phones.any { digitsOf(it.value).contains(q.digits) }) return true
        return fields(c).any { fold(it).contains(q.text) }
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
