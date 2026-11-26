package com.ilukezippo.vcfdesk.data.device

import com.ilukezippo.vcfdesk.core.model.Extra
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a phone contact holds beyond the model, kept so that undoing a delete or a move can put
 * it back when the contact is re-created in the same account:
 * - `Extra("device-row", json)`: one data row the model doesn't cover (mimetype, data1..data15,
 *   is_primary/is_super_primary, and a data15 blob of at most [MAX_BLOB] bytes);
 * - `Extra("device-raw", json)`: the raw contact's account plus starred, custom ringtone and
 *   send-to-voicemail.
 * Both are informational everywhere else: never written by an update, never exported.
 */
object DeviceSnapshot {
    const val ROW = "device-row"
    const val RAW = "device-raw"
    const val MAX_BLOB = 512 * 1024

    /** The raw contact's own settings, as read. */
    data class RawSettings(val type: String?, val name: String?, val starred: String?, val ringtone: String?, val voicemail: String?)

    private fun prim(v: String?) = if (v == null) JsonNull else JsonPrimitive(v)
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    fun rowExtra(r: DataRow): Extra {
        val values = JsonObject(r.values.filterValues { it != null }.toSortedMap().mapValues { prim(it.value) })
        val m = linkedMapOf("mimetype" to JsonPrimitive(r.mimetype), "values" to values)
        r.blob?.takeIf { it.size <= MAX_BLOB }?.let { m["blob"] = JsonPrimitive(Base64.getEncoder().encodeToString(it)) }
        return Extra(ROW, JsonObject(m).toString())
    }

    fun rawExtra(s: RawSettings): Extra = Extra(
        RAW,
        JsonObject(
            linkedMapOf(
                "account_type" to prim(s.type), "account_name" to prim(s.name), "starred" to prim(s.starred),
                "custom_ringtone" to prim(s.ringtone), "send_to_voicemail" to prim(s.voicemail),
            ),
        ).toString(),
    )

    private fun parse(e: Extra): JsonObject? = runCatching { Json.parseToJsonElement(e.raw) as? JsonObject }.getOrNull()

    fun raw(extras: List<Extra>): RawSettings? = extras.firstOrNull { it.kind == RAW }?.let(::parse)?.let {
        RawSettings(it.str("account_type"), it.str("account_name"), it.str("starred"), it.str("custom_ringtone"), it.str("send_to_voicemail"))
    }

    fun rows(extras: List<Extra>): List<DataRow> = extras.filter { it.kind == ROW }.mapNotNull(::parse).mapNotNull { o ->
        val mime = o.str("mimetype") ?: return@mapNotNull null
        val values = (o["values"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.contentOrNull }
        val blob = o.str("blob")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        DataRow(null, mime, values, blob)
    }

    /** Source-specific snapshots, dropped when a contact goes to another place (copy or move). */
    fun isSnapshot(e: Extra) = e.kind == ROW || e.kind == RAW
}
