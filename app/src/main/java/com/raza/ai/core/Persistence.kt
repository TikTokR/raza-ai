package com.raza.ai.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * JSON (de)serialisation for the on-phone stores. Parsing is tolerant on purpose:
 * a damaged file yields an empty list instead of crashing the app, and bad rows are skipped.
 */
object Persistence {

    private fun <T> parse(raw: String?, row: (JSONObject) -> T?): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    row(arr.getJSONObject(i))
                } catch (e: JSONException) {
                    null
                }
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun tasksToJson(list: List<TaskItem>): String = JSONArray().also { a ->
        list.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("done", it.done).put("createdAt", it.createdAt)) }
    }.toString()

    fun tasksFromJson(raw: String?): List<TaskItem> = parse(raw) { o ->
        val title = TaskRules.cleanTitle(o.getString("title")) ?: return@parse null
        TaskItem(o.getLong("id"), title, o.optBoolean("done", false), o.optLong("createdAt", 0L))
    }

    fun notesToJson(list: List<MemoryNote>): String = JSONArray().also { a ->
        list.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("createdAt", it.createdAt)) }
    }.toString()

    fun notesFromJson(raw: String?): List<MemoryNote> = parse(raw) { o ->
        val text = o.getString("text")
        if (NoteRules.validate(text) != null) return@parse null // never resurrect a secret-looking note
        MemoryNote(o.getLong("id"), text.trim(), o.optLong("createdAt", 0L))
    }

    fun logToJson(list: List<ActionLogEntry>): String = JSONArray().also { a ->
        list.forEach { a.put(JSONObject().put("time", it.time).put("summary", it.summary).put("ok", it.ok).put("message", it.message)) }
    }.toString()

    fun logFromJson(raw: String?): List<ActionLogEntry> = parse(raw) { o ->
        ActionLogEntry(o.getLong("time"), o.getString("summary"), o.getBoolean("ok"), o.optString("message", ""))
    }

    fun chatToJson(list: List<ChatRecord>): String = JSONArray().also { a ->
        list.forEach { a.put(JSONObject().put("role", it.role).put("text", it.text).put("time", it.time)) }
    }.toString()

    fun chatFromJson(raw: String?): List<ChatRecord> = parse(raw) { o ->
        val role = o.getString("role")
        if (role != "user" && role != "assistant") return@parse null
        ChatRecord(role, o.getString("text"), o.optLong("time", 0L))
    }

    /** Keeps only the newest [max] entries. */
    fun <T> newest(list: List<T>, max: Int): List<T> = if (list.size <= max) list else list.subList(list.size - max, list.size)
}
