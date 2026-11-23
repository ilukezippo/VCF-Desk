package com.ilukezippo.vcfdesk.data

import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.core.model.Contact
import kotlinx.coroutines.flow.Flow

sealed interface SourceId {
    data object Device : SourceId
    data class Google(val email: String) : SourceId
}

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
    suspend fun load(id: String): Contact
    suspend fun groups(): List<String>
    suspend fun update(cs: List<Contact>, onProgress: (Int) -> Unit = {}): WriteResult
    suspend fun create(cs: List<Contact>, target: AccountTag?, onProgress: (Int) -> Unit = {}): WriteResult
    suspend fun delete(ids: List<String>, onProgress: (Int) -> Unit = {}): WriteResult
    suspend fun refresh()
}
