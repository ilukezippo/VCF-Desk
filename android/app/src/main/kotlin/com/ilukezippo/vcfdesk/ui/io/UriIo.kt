package com.ilukezippo.vcfdesk.ui.io

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads a picked file, refusing anything over [limit] bytes (checked up front when the provider says the size, and while reading). */
suspend fun ContentResolver.readCapped(uri: Uri, limit: Long = MAX_IMPORT_BYTES): ByteArray = withContext(Dispatchers.IO) {
    val declared = try {
        query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }
    if (declared != null && declared > limit) throw FileTooLargeException(limit)
    val input = openInputStream(uri) ?: throw IOException("the file could not be opened")
    input.use { s ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw FileTooLargeException(limit)
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }
}

suspend fun ContentResolver.openOutputForWrite(uri: Uri): java.io.OutputStream = withContext(Dispatchers.IO) {
    (openOutputStream(uri, "wt") ?: throw IOException("the file could not be opened for writing")).buffered()
}

/** ACTION_CREATE_DOCUMENT with a given MIME type and suggested name. */
class CreateDocumentFor : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.first)
            .putExtra(Intent.EXTRA_TITLE, input.second)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

const val FILE_PROVIDER_SUFFIX = ".files"

fun Context.shareUri(file: File): Uri = FileProvider.getUriForFile(this, packageName + FILE_PROVIDER_SUFFIX, file)

/** Opens the share sheet for [file]. */
fun Context.startShare(file: File, mime: String, title: String) {
    val uri = shareUri(file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

/** Deletes a document (best effort; the caller ignores failures). */
suspend fun ContentResolver.deleteDocument(uri: Uri) = withContext(Dispatchers.IO) {
    android.provider.DocumentsContract.deleteDocument(this@deleteDocument, uri)
}
