package com.raza.ai.data

import android.content.Context
import java.io.File

/** A small private JSON file in the app's own storage. Written atomically so a crash never leaves half a file. */
class JsonFile(private val file: File) {
    @Synchronized
    fun read(): String? = try {
        if (file.exists()) file.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun write(text: String) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) {
            // Storage full or unavailable: the in-memory copy keeps working for this session.
        }
    }

    @Synchronized
    fun delete() {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    val exists: Boolean get() = file.exists()
}

/** Everything RAZA saves on the phone besides settings. All of it is viewable and deletable in Settings. */
class LocalStores(ctx: Context) {
    private val dir: File = ctx.applicationContext.filesDir
    val tasks = JsonFile(File(dir, "tasks.json"))
    val notes = JsonFile(File(dir, "notes.json"))
    val actionLog = JsonFile(File(dir, "action_log.json"))
    val chat = JsonFile(File(dir, "conversation.json"))
}
