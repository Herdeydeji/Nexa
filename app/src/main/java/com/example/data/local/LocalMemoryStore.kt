package com.example.data.local

import com.example.domain.model.MemoryEntry
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class LocalMemoryStore(
    private val prefs: NexaPreferencesDataStore
) {
    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val listAdapter = moshi.adapter<List<MemoryEntry>>(
        Types.newParameterizedType(List::class.java, MemoryEntry::class.java)
    )

    val memoryEntries: Flow<List<MemoryEntry>> = prefs.memoryEntriesJson.map { json ->
        decodeEntries(json)
    }

    suspend fun getSnapshot(): List<MemoryEntry> = decodeEntries(prefs.readMemoryEntriesJson())

    suspend fun addMemory(
        text: String,
        category: String = "Preference"
    ): MemoryEntry {
        val cleanText = text.trim()
        val cleanCategory = category.trim().ifBlank { "Preference" }
        val existing = getSnapshot()
        val now = System.currentTimeMillis()
        val duplicate = existing.firstOrNull { it.text.equals(cleanText, ignoreCase = true) }
        val entry = if (duplicate != null) {
            duplicate.copy(category = cleanCategory, updatedAtMillis = now)
        } else {
            MemoryEntry(
                id = "mem_${UUID.randomUUID()}",
                text = cleanText,
                category = cleanCategory,
                createdAtMillis = now,
                updatedAtMillis = now
            )
        }
        val updated = if (duplicate != null) {
            existing.map { if (it.id == duplicate.id) entry else it }
        } else {
            listOf(entry) + existing
        }
        prefs.writeMemoryEntriesJson(listAdapter.toJson(updated))
        return entry
    }

    suspend fun updateMemory(
        id: String,
        text: String,
        category: String = "Preference"
    ) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return
        val cleanCategory = category.trim().ifBlank { "Preference" }
        val now = System.currentTimeMillis()
        val updated = getSnapshot().map { entry ->
            if (entry.id == id) {
                entry.copy(text = cleanText, category = cleanCategory, updatedAtMillis = now)
            } else {
                entry
            }
        }
        prefs.writeMemoryEntriesJson(listAdapter.toJson(updated))
    }

    suspend fun removeMemory(id: String) {
        val updated = getSnapshot().filterNot { it.id == id }
        prefs.writeMemoryEntriesJson(listAdapter.toJson(updated))
    }

    suspend fun clearAll() {
        prefs.clearMemoryEntriesJson()
    }

    private fun decodeEntries(json: String?): List<MemoryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { listAdapter.fromJson(json) }.getOrNull() ?: emptyList()
    }
}
