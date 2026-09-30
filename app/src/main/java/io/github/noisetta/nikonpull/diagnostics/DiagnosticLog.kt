package io.github.noisetta.nikonpull.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import io.github.noisetta.nikonpull.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Local, bounded protocol log. Logging failures must never interrupt a camera transfer. */
object DiagnosticLog {
    private const val TAG = "ZfTransfer"
    private const val MAX_FILE_BYTES = 1024 * 1024L
    private const val ARCHIVES = 2
    private val lock = Any()
    private val runId = UUID.randomUUID().toString().take(8)
    private var directory: File? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            if (directory != null) return
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            directory = File(base, "diagnostics").also { it.mkdirs() }
        }
        info(
            "APP",
            "start version=${BuildConfig.VERSION_NAME} api=${Build.VERSION.SDK_INT} " +
                "device=${Build.MANUFACTURER}/${Build.MODEL} pid=${Process.myPid()}",
        )
    }

    fun info(component: String, message: String) = write("INFO", component, message)

    fun warn(component: String, message: String, error: Throwable? = null) =
        write("WARN", component, message + errorDescription(error))

    fun error(component: String, message: String, error: Throwable? = null) =
        write("ERROR", component, message + errorDescription(error))

    /** Produces one shareable snapshot containing the current file and both archives. */
    fun createExport(): File? = synchronized(lock) {
        val dir = directory ?: return@synchronized null
        runCatching {
            val export = File(dir, "zf-transfer-export.txt")
            export.outputStream().buffered().writer(Charsets.UTF_8).use { output ->
                output.appendLine("Zf AP Transfer diagnostic log")
                output.appendLine("Created: ${timestamp()}")
                output.appendLine("Contains local network addresses, camera model, PTP codes, object handles, sizes, and errors; some errors may include filenames.")
                output.appendLine("No Wi-Fi passwords or image contents are recorded.")
                for (index in ARCHIVES downTo 0) {
                    val source = logFile(dir, index)
                    if (!source.isFile) continue
                    output.appendLine()
                    output.appendLine("===== ${source.name} =====")
                    source.reader(Charsets.UTF_8).use { input -> input.copyTo(output) }
                }
            }
            export
        }.onFailure { Log.e(TAG, "Could not export diagnostic log", it) }.getOrNull()
    }

    private fun write(level: String, component: String, message: String) {
        val safeMessage = message.replace('\r', ' ').replace('\n', ' ')
        val line = "${timestamp()} run=$runId $level $component $safeMessage"
        runCatching {
            when (level) {
                "ERROR" -> Log.e(TAG, line)
                "WARN" -> Log.w(TAG, line)
                else -> Log.i(TAG, line)
            }
        }
        synchronized(lock) {
            val dir = directory ?: return
            runCatching {
                val current = logFile(dir, 0)
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                if (current.length() + bytes.size > MAX_FILE_BYTES) rotate(dir)
                current.appendBytes(bytes)
            }.onFailure { Log.e(TAG, "Could not persist diagnostic log", it) }
        }
    }

    private fun rotate(dir: File) {
        logFile(dir, ARCHIVES).delete()
        for (index in ARCHIVES - 1 downTo 0) {
            val source = logFile(dir, index)
            if (source.isFile) {
                val target = logFile(dir, index + 1)
                if (!source.renameTo(target)) {
                    source.copyTo(target, overwrite = true)
                    source.delete()
                }
            }
        }
    }

    private fun logFile(dir: File, index: Int): File =
        File(dir, if (index == 0) "zf-transfer.log" else "zf-transfer.log.$index")

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())

    private fun errorDescription(error: Throwable?): String {
        if (error == null) return ""
        val cause = generateSequence(error) { it.cause }.last()
        val frames = cause.stackTrace.take(6).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        return " exception=${cause::class.java.simpleName} detail=${cause.message.orEmpty()} at=$frames"
    }
}
