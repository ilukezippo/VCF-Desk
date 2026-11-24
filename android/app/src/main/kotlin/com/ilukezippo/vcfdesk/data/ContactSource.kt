package com.ilukezippo.vcfdesk.data

import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import kotlinx.coroutines.flow.Flow

sealed interface SourceId {
    data object Device : SourceId
    data class Google(val email: String) : SourceId
}

/**
 * `load` throws this when the contact has a full-size photo that could not be read: handing out
 * the contact without it (or with only the thumbnail) would let a backup or a move lose the photo.
 */
class PhotoUnavailableException(id: String, cause: Throwable? = null) :
    java.io.IOException("the full photo of contact $id could not be read" + (cause?.message?.let { ": $it" } ?: ""), cause)

/** Outcome of a write; `failed` maps contact id to reason. */
data class WriteResult(
    val written: List<String>,
    val skippedReadOnly: List<String>,
    val failed: Map<String, String>,
)

/**
 * A place contacts live. `contacts()` returns thumbnails only (full photos via `load`);
 * ids are source-specific keys and mean nothing to other sources.
 */
interface ContactSource {
    val id: SourceId
    fun contacts(): Flow<List<Contact>>
    /** The full contact, with the full-size photo; throws [NoSuchElementException] if it is gone, [PhotoUnavailableException] if its photo can't be read. */
    suspend fun load(id: String): Contact
    suspend fun groups(): List<String>
    suspend fun update(cs: List<Contact>, onProgress: (Int) -> Unit = {}): WriteResult
    suspend fun create(cs: List<Contact>, target: AccountTag?, onProgress: (Int) -> Unit = {}): WriteResult
    suspend fun delete(ids: List<String>, onProgress: (Int) -> Unit = {}): WriteResult
    suspend fun refresh()
}
