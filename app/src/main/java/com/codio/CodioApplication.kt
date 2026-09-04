package com.codio

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches crashes that would otherwise only be visible through adb logcat and
 * writes them to a plain text file in app-private storage. MainActivity reads
 * this file on the next launch and shows it in a copyable dialog, so no PC or
 * adb is needed to see why the app closed.
 */
class CodioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(throwable)
            } catch (_: Throwable) {
                // The logger itself must never be the reason the crash handler fails.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(throwable: Throwable) {
        val dir = File(filesDir, "crash_logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp.txt")
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        file.writeText(writer.toString())
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(10)
            ?.forEach { it.delete() }
    }

    companion object {
        fun latestCrashLog(context: Context): String? {
            val dir = File(context.filesDir, "crash_logs")
            val latest = dir.listFiles()?.maxByOrNull { it.lastModified() } ?: return null
            return latest.readText().ifBlank { null }
        }

        fun clearCrashLogs(context: Context) {
            File(context.filesDir, "crash_logs").listFiles()?.forEach { it.delete() }
        }
    }
}
