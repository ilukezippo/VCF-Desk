package com.ilukezippo.vcfdesk.ui.bulk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilukezippo.vcfdesk.R
import com.ilukezippo.vcfdesk.core.bulk.ContactDiff
import com.ilukezippo.vcfdesk.data.BulkOp
import com.ilukezippo.vcfdesk.data.BulkReport

/** One summary line of an edit preview: [label] becomes [after] (null: it differs per contact) on [count] contacts. */
data class SummaryLine(val label: String, val after: String?, val count: Int)

/** "Company → X on 214 contacts": one line per changed field, counting contacts (not changes). */
fun summarize(diffs: List<ContactDiff>): List<SummaryLine> {
    val byLabel = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
    diffs.forEachIndexed { i, d -> d.changes.forEach { byLabel.getOrPut(it.label) { ArrayList() } += i to it.after } }
    return byLabel.map { (label, hits) ->
        val afters = hits.map { it.second }.distinct()
        SummaryLine(label, afters.singleOrNull(), hits.map { it.first }.distinct().size)
    }
}

/** For "remove exact duplicates": the contacts that would go (the others are kept for data the keeper lacks). */
fun removable(diffs: List<ContactDiff>): Int = diffs.count { d -> d.changes.none { it.after.startsWith("kept") } }

/** The preview (and a delete's second confirmation) for the stage the view model is in. */
@Composable
fun BulkPreviewDialog(p: Stage.Preview, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val n = p.diffs.size
    if (p.finalConfirm) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(pluralStringResource(R.plurals.bulk_delete_title, n, n)) },
            text = { Text(stringResource(R.string.bulk_delete_body)) },
            confirmButton = {
                TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(pluralStringResource(R.plurals.bulk_delete_button, n, n))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bulk_cancel)) } },
        )
        return
    }
    val applicable = if (p.op is BulkOp.RemoveExactDuplicates) removable(p.diffs) else n
    val isDelete = p.op is BulkOp.Delete
    var open by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bulk_preview_title)) },
        text = {
            if (n == 0) {
                Text(stringResource(R.string.bulk_nothing))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryText(p, applicable)
                    Text(stringResource(R.string.bulk_names_header), style = MaterialTheme.typography.labelLarge)
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(n) { i ->
                            val d = p.diffs[i]
                            Column(Modifier.fillMaxWidth().clickable { open = if (open == i) null else i }.padding(vertical = 6.dp)) {
                                Text(
                                    displayName(d.contact).ifEmpty { stringResource(R.string.bulk_empty_value) },
                                    style = MaterialTheme.typography.bodyLarge.merge(TextStyle(textDirection = TextDirection.Content)),
                                )
                                if (open == i) {
                                    d.changes.forEach { c ->
                                        Column(Modifier.padding(top = 4.dp)) {
                                            Text(c.label, style = MaterialTheme.typography.labelMedium)
                                            if (p.op is BulkOp.Edit) {
                                                BeforeAfter(stringResource(R.string.bulk_before), c.before)
                                                BeforeAfter(stringResource(R.string.bulk_after), c.after)
                                            } else {
                                                Text(c.after, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (applicable > 0) {
                val danger = if (isDelete) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.textButtonColors()
                TextButton(onClick = onConfirm, colors = danger) {
                    Text(if (isDelete) stringResource(R.string.bulk_continue) else pluralStringResource(R.plurals.bulk_apply, applicable, applicable))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(if (n == 0) R.string.bulk_close else R.string.bulk_cancel)) }
        },
    )
}

@Composable
private fun BeforeAfter(label: String, value: String) {
    Text(
        "$label: " + value.ifEmpty { stringResource(R.string.bulk_empty_value) },
        style = MaterialTheme.typography.bodyMedium.merge(TextStyle(textDirection = TextDirection.Content)),
    )
}

@Composable
private fun SummaryText(p: Stage.Preview, applicable: Int) {
    val n = p.diffs.size
    when (val op = p.op) {
        is BulkOp.Edit -> summarize(p.diffs).forEach { l ->
            val text = if (l.after != null) {
                pluralStringResource(R.plurals.bulk_sum_to, l.count, l.label, l.after.ifEmpty { stringResource(R.string.bulk_empty_value) }, l.count)
            } else {
                pluralStringResource(R.plurals.bulk_sum_changes, l.count, l.label, l.count)
            }
            Text(text, style = MaterialTheme.typography.bodyLarge.merge(TextStyle(textDirection = TextDirection.Content)))
        }
        BulkOp.Delete -> Text(pluralStringResource(R.plurals.bulk_sum_delete, n, n), style = MaterialTheme.typography.bodyLarge)
        is BulkOp.CopyTo -> Text(
            pluralStringResource(if (op.move) R.plurals.bulk_sum_move else R.plurals.bulk_sum_copy, n, n),
            style = MaterialTheme.typography.bodyLarge,
        )
        BulkOp.RemoveExactDuplicates -> {
            Text(pluralStringResource(R.plurals.bulk_sum_dupes, applicable, applicable), style = MaterialTheme.typography.bodyLarge)
            if (n > applicable) Text(stringResource(R.string.bulk_sum_dupes_kept, n - applicable), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Every dialog the bulk flow needs once the sheet has been left behind: the preview, progress, the
 * result with undo, and errors. They live outside the detail pane because a run changes the list
 * (and so the selection) underneath it.
 */
@Composable
fun BulkStageDialogs(vm: BulkViewModel, onFinished: () -> Unit) {
    val stage by vm.stage.collectAsStateWithLifecycle()
    when (val s = stage) {
        Stage.Compose -> {}
        is Stage.Preview -> BulkPreviewDialog(s, onConfirm = vm::confirm, onDismiss = vm::close)
        is Stage.Running -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.bulk_running, s.done, s.total)) },
            text = { LinearProgressIndicator(progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = vm::cancel) { Text(stringResource(R.string.bulk_stop)) } },
        )
        is Stage.Cancelled -> CancelledDialog(s, onUndo = vm::undoLast, onClose = { vm.close(); onFinished() })
        is Stage.Done -> DoneDialog(s, onUndo = vm::undoLast, onClose = { vm.close(); onFinished() })
        is Stage.Error -> AlertDialog(
            onDismissRequest = vm::close,
            title = { Text(stringResource(R.string.bulk_error_title)) },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = vm::close) { Text(stringResource(R.string.bulk_close)) } },
        )
    }
}

@Composable
private fun DoneDialog(s: Stage.Done, onUndo: () -> Unit, onClose: () -> Unit) {
    val r: BulkReport = s.report
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.bulk_done_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pluralStringResource(R.plurals.bulk_changed, r.changed, r.changed), style = MaterialTheme.typography.bodyLarge)
                if (r.skippedReadOnly > 0) Text(pluralStringResource(R.plurals.bulk_skipped, r.skippedReadOnly, r.skippedReadOnly))
                if (r.changed == 0) Text(stringResource(R.string.bulk_no_backup))
                else if (r.backup == null && s.copiedTo != null) {
                    Text(stringResource(R.string.bulk_copy_note, placeName(s.copiedTo)))
                }
                Failures(r.failed, s.names)
                s.undone?.let { u ->
                    HorizontalDivider()
                    Text(stringResource(R.string.bulk_undone, u.changed), style = MaterialTheme.typography.bodyLarge)
                    Failures(u.failed, s.names)
                }
                s.undoError?.let { Text(stringResource(R.string.bulk_undo_failed, it), color = MaterialTheme.colorScheme.error) }
                if (s.undoing) {
                    Text(stringResource(R.string.bulk_undoing))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose, enabled = !s.undoing) { Text(stringResource(R.string.bulk_close)) } },
        dismissButton = {
            TextButton(onClick = onUndo, enabled = r.backup != null && !s.undoing && s.undone == null) {
                Text(stringResource(R.string.bulk_undo))
            }
        },
    )
}

@Composable
private fun Failures(failed: Map<String, String>, names: Map<String, String>) {
    if (failed.isEmpty()) return
    Text(pluralStringResource(R.plurals.bulk_failed_header, failed.size, failed.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
    LazyColumn(Modifier.heightIn(max = 200.dp)) {
        items(failed.entries.toList()) { (id, why) ->
            Text(
                (names[id]?.ifEmpty { null } ?: id) + ": " + why,
                style = MaterialTheme.typography.bodySmall.merge(TextStyle(textDirection = TextDirection.Content)),
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun placeName(id: com.ilukezippo.vcfdesk.data.SourceId): String = when (id) {
    com.ilukezippo.vcfdesk.data.SourceId.Device -> stringResource(R.string.source_device)
    is com.ilukezippo.vcfdesk.data.SourceId.Google -> id.email
}

@Composable
private fun CancelledDialog(s: Stage.Cancelled, onUndo: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.bulk_stopped_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.bulk_stopped, s.done, s.total))
                Text(stringResource(if (s.backup != null) R.string.bulk_stopped_backup else R.string.bulk_stopped_nothing))
                s.movedTo?.let { Text(stringResource(R.string.bulk_stopped_copies, placeName(it))) }
                s.undone?.let { u ->
                    HorizontalDivider()
                    Text(stringResource(R.string.bulk_undone, u.changed), style = MaterialTheme.typography.bodyLarge)
                    Failures(u.failed, emptyMap())
                }
                s.undoError?.let { Text(stringResource(R.string.bulk_undo_failed, it), color = MaterialTheme.colorScheme.error) }
                if (s.undoing) {
                    Text(stringResource(R.string.bulk_undoing))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose, enabled = !s.undoing) { Text(stringResource(R.string.bulk_close)) } },
        dismissButton = {
            TextButton(onClick = onUndo, enabled = s.backup != null && !s.undoing && s.undone == null) {
                Text(stringResource(R.string.bulk_undo))
            }
        },
    )
}
