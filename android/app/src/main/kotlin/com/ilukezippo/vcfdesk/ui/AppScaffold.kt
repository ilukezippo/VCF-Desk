package com.ilukezippo.vcfdesk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilukezippo.vcfdesk.R
import com.ilukezippo.vcfdesk.ui.list.ContactListScreen
import com.ilukezippo.vcfdesk.ui.list.ContactListViewModel
import com.ilukezippo.vcfdesk.ui.list.ListState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Top-bar overflow entries and the bulk hook; later tasks replace the no-ops. */
class AppActions(
    val onImport: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onBackups: () -> Unit = {},
    val onSettings: () -> Unit = {},
    /** Called with the selected ids when the user taps "Edit N contacts" (Task 11 opens the bulk sheet). */
    val onEditSelected: (Set<String>) -> Unit = {},
)

/** What the detail pane should show. */
sealed interface DetailContent {
    data object None : DetailContent
    /** One opened contact (Task 10's editor). */
    data class Contact(val id: String) : DetailContent
    /** A multi-selection (Task 11's bulk sheet). */
    data class Selection(val ids: Set<String>) : DetailContent
}

/**
 * The main screen: list pane + detail pane (side by side when unfolded, one at a time on a phone).
 * [detail] is the slot later tasks fill; [onClose] clears the opened contact / returns to the list.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    vm: ContactListViewModel,
    actions: AppActions,
    /** Runs the Google account chooser + consent; returns the email, or null if the user backed out. Throws on failure. */
    pickGoogleAccount: suspend () -> String?,
    detail: @Composable (DetailContent, onClose: () -> Unit) -> Unit = { content, _ -> DefaultDetail(content, actions) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val failedText = stringResource(R.string.google_sign_in_failed, "%s")
    val unavailable = stringResource(R.string.google_sign_in_unavailable)

    val openId = navigator.currentDestination?.contentKey?.takeIf { it.isNotEmpty() }
    val content: DetailContent = when {
        state.selected.isNotEmpty() -> DetailContent.Selection(state.selected)
        openId != null && state.items.any { it.id == openId } -> DetailContent.Contact(openId)
        else -> DetailContent.None
    }

    // A new source or an emptied selection takes the phone layout back to the list.
    LaunchedEffect(state.source) { navigator.navigateTo(ListDetailPaneScaffoldRole.List) }
    LaunchedEffect(state.selected.isEmpty()) {
        if (state.selected.isEmpty() && navigator.currentDestination?.contentKey == BULK) {
            navigator.navigateTo(ListDetailPaneScaffoldRole.List)
        }
    }

    val addGoogle: () -> Unit = {
        scope.launch {
            try {
                val email = pickGoogleAccount()
                if (email != null) vm.addGoogleAccount(email)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(failedText.replace("%s", e.message ?: unavailable))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavigableListDetailPaneScaffold(
            navigator = navigator,
            listPane = {
                AnimatedPane {
                    ContactListScreen(
                        state = state,
                        vm = vm,
                        actions = actions,
                        openId = openId,
                        onOpen = { id -> scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) } },
                        onEditSelected = {
                            actions.onEditSelected(state.selected)
                            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, BULK) }
                        },
                        onAddGoogle = addGoogle,
                    )
                }
            },
            detailPane = {
                AnimatedPane {
                    detail(content) { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.List) } }
                }
            },
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

private const val BULK = ""

/** Placeholder detail pane until the editor (Task 10) and bulk sheet (Task 11) are plugged in. */
@Composable
fun DefaultDetail(content: DetailContent, actions: AppActions) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        when (content) {
            is DetailContent.Selection -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.selected_count, content.ids.size), style = MaterialTheme.typography.titleLarge)
                Button(onClick = { actions.onEditSelected(content.ids) }) {
                    Text(stringResource(R.string.select_edit, content.ids.size))
                }
            }
            else -> Text(
                stringResource(R.string.detail_placeholder),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
