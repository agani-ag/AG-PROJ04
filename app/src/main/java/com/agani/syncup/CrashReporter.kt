package com.agani.syncup

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Saves the last uncaught crash to a file so it can be shown on next launch (debug aid). */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val header = "SyncUp v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n"
                File(context.filesDir, FILE).writeText(header + sw.toString())
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns the last crash text (and deletes it), or null if there was none. */
    fun consume(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        return text
    }
}
