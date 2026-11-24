package com.ilukezippo.vcfdesk.ui.list

import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import com.ilukezippo.vcfdesk.core.model.Name
import com.ilukezippo.vcfdesk.core.model.Org
import com.ilukezippo.vcfdesk.core.model.Typed
import com.ilukezippo.vcfdesk.data.ContactSource
import com.ilukezippo.vcfdesk.data.FakeSource
import com.ilukezippo.vcfdesk.data.SourceId
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactListViewModelTest {
    private val phoneTag = AccountTag("com.google", "name1@example.com", "Google", readOnly = false)
    private val simTag = AccountTag("com.android.local", "", "Phone", readOnly = false)

    private val device = FakeSource(SourceId.Device)
    private val google = FakeSource(SourceId.Google("name1@example.com"))
    private val googleAccounts = MutableStateFlow<List<String>>(emptyList())
    private val saved = ArrayList<String>()
    private var synced = false

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private var filterRuns = 0
    private fun vm(collator: Comparator<in String> = Collator.getInstance(Locale.ENGLISH)) = ContactListViewModel(
        sourceFor = { id: SourceId -> (if (id == SourceId.Device) device else google) as ContactSource },
        googleAccounts = googleAccounts,
        saveGoogleAccount = { saved += it; googleAccounts.value = googleAccounts.value + it },
        syncChecker = { synced },
        collator = collator,
        background = Dispatchers.Unconfined,
        onFilter = { filterRuns++ },
    )

    private fun c(
        name: String,
        phone: String? = null,
        account: AccountTag = simTag,
        email: String? = null,
        org: String = "",
        notes: String = "",
        groups: Set<String> = emptySet(),
    ) = Contact(
        account = account,
        name = Name(display = name),
        phones = listOfNotNull(phone?.let { Typed("Mobile", it) }),
        emails = listOfNotNull(email?.let { Typed("Home", it) }),
        org = Org(company = org),
        notes = notes,
        groups = groups,
    )

    @Test fun searchMatchesPhoneDigits() = runTest {
        device.seed(c("Name 1", "+965 5555 5555"))
        device.seed(c("Name 2", "99999999"))
        val vm = vm()
        vm.setQuery("5555")
        assertEquals(listOf("Name 1"), vm.state.value.items.map { it.name.display })
        // Arabic-Indic digits on the query match ASCII digits in the data
        vm.setQuery("٩٩٩٩")
        assertEquals(listOf("Name 2"), vm.state.value.items.map { it.name.display })
        // punctuation in the query is ignored for phones
        vm.setQuery("+965 55")
        assertEquals(listOf("Name 1"), vm.state.value.items.map { it.name.display })
    }

    @Test fun searchMatchesOtherTextFieldsCaseInsensitively() = runTest {
        device.seed(c("Name 1", email = "Person.One@Example.com"))
        device.seed(c("Name 2", org = "Acme Corp"))
        device.seed(c("Name 3", notes = "met at the Fair"))
        val vm = vm()
        vm.setQuery("person.one@EXAMPLE")
        assertEquals(listOf("Name 1"), vm.state.value.items.map { it.name.display })
        vm.setQuery("acme")
        assertEquals(listOf("Name 2"), vm.state.value.items.map { it.name.display })
        vm.setQuery("FAIR")
        assertEquals(listOf("Name 3"), vm.state.value.items.map { it.name.display })
        vm.setQuery("")
        assertEquals(3, vm.state.value.items.size)
    }

    @Test fun filterByAccountLabel() = runTest {
        device.seed(c("Name 1", account = phoneTag))
        device.seed(c("Name 2", account = simTag))
        device.seed(c("Name 3", account = phoneTag))
        val vm = vm()
        assertEquals(listOf("Google", "Phone"), vm.state.value.accounts)
        vm.setAccountFilter("Google")
        assertEquals(listOf("Name 1", "Name 3"), vm.state.value.items.map { it.name.display })
        vm.setAccountFilter(null)
        assertEquals(3, vm.state.value.items.size)
    }

    @Test fun filterByGroup() = runTest {
        device.seed(c("Name 1", groups = setOf("Family")))
        device.seed(c("Name 2", groups = setOf("Work")))
        val vm = vm()
        assertEquals(listOf("Family", "Work"), vm.state.value.groups)
        vm.setGroupFilter("Work")
        assertEquals(listOf("Name 2"), vm.state.value.items.map { it.name.display })
    }

    @Test fun selectResultsSelectsOnlyFiltered() = runTest {
        val a = device.seed(c("Name 1", "55555555"))
        device.seed(c("Name 2", "99999999"))
        val vm = vm()
        vm.setQuery("5555")
        vm.selectResults()
        assertEquals(setOf(a.id), vm.state.value.selected)
        vm.clearSelection()
        assertTrue(vm.state.value.selected.isEmpty())
        vm.selectAll()
        assertEquals(2, vm.state.value.selected.size)
        vm.toggleSelect(a.id)
        assertFalse(a.id in vm.state.value.selected)
    }

    @Test fun sortArabicAndEnglishCollated() = runTest {
        val first = device.seed(c("Bob"))
        val second = device.seed(c("Bob")) // same name: stays in source order
        device.seed(c("باسم"))
        device.seed(c("adam"))
        device.seed(c("أحمد"))
        device.seed(c(""))
        device.seed(c("Zed"))
        val vm = vm(Collator.getInstance(Locale.forLanguageTag("ar")))
        val asc = vm.state.value.items.map { it.name.display }
        // Latin names sort among themselves case-insensitively, Arabic among themselves, empty last
        assertEquals(listOf("adam", "Bob", "Bob", "Zed"), asc.filter { it.firstOrNull()?.code?.let { c -> c < 0x600 } == true })
        assertEquals(listOf("أحمد", "باسم"), asc.filter { it.firstOrNull()?.code?.let { c -> c >= 0x600 } == true })
        assertEquals("", asc.last())
        val ids = vm.state.value.items.filter { it.name.display == "Bob" }.map { it.id }
        assertEquals(listOf(first.id, second.id), ids)

        vm.toggleSort()
        assertFalse(vm.state.value.sortAsc)
        val desc = vm.state.value.items
        assertEquals("", desc.last().name.display) // empty stays last when descending
        val descNames = desc.map { it.name.display }.filter { it.isNotEmpty() }
        assertEquals(asc.filter { it.isNotEmpty() }.reversed(), descNames)
        assertEquals(listOf(first.id, second.id), desc.filter { it.name.display == "Bob" }.map { it.id }) // stable on ties
    }

    @Test fun alsoOnPhoneBannerWhenSynced() = runTest {
        val vm = vm()
        assertFalse(vm.state.value.alsoOnPhone) // device source never shows the banner
        synced = true
        vm.switchSource(SourceId.Google("name1@example.com"))
        assertTrue(vm.state.value.alsoOnPhone)
        synced = false
        vm.switchSource(SourceId.Device)
        vm.switchSource(SourceId.Google("name1@example.com"))
        assertFalse(vm.state.value.alsoOnPhone)
    }

    @Test fun switchSourceLoadsOtherContactsAndResetsFilters() = runTest {
        val a = device.seed(c("Name 1", account = phoneTag))
        google.seed(c("Name 9", account = phoneTag))
        val vm = vm()
        vm.toggleSelect(a.id)
        vm.setAccountFilter("Google")
        vm.setQuery("name")
        vm.switchSource(SourceId.Google("name1@example.com"))
        val s = vm.state.value
        assertEquals(listOf("Name 9"), s.items.map { it.name.display })
        assertTrue(s.selected.isEmpty())
        assertEquals(null, s.accountFilter)
        assertEquals("name", s.query) // the search text stays
        assertFalse(s.loading)
    }

    @Test fun addGoogleAccountSavesAndSwitches() = runTest {
        val vm = vm()
        vm.addGoogleAccount("Name1@Example.com")
        assertEquals(listOf("name1@example.com"), saved)
        assertEquals(SourceId.Google("name1@example.com"), vm.state.value.source)
        assertEquals(listOf(SourceId.Device, SourceId.Google("name1@example.com")), vm.state.value.sources)
    }

    @Test fun selectionDropsContactsThatDisappear() = runTest {
        val a = device.seed(c("Name 1"))
        device.seed(c("Name 2"))
        val vm = vm()
        vm.selectAll()
        device.delete(listOf(a.id))
        assertEquals(1, vm.state.value.selected.size)
        assertFalse(a.id in vm.state.value.selected)
    }

    @Test fun togglingSelectionDoesNotRefilter() = runTest {
        val a = device.seed(c("Name 1"))
        device.seed(c("Name 2"))
        val vm = vm()
        val items = vm.state.value.items
        val runs = filterRuns
        vm.toggleSelect(a.id)
        vm.selectAll()
        vm.clearSelection()
        assertEquals(runs, filterRuns)
        assertTrue(items === vm.state.value.items)
        vm.setQuery("2") // a real filter change does re-run it
        assertTrue(filterRuns > runs)
    }
}
