package io.dodo.obdmap.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Лог в logcat, в файл и в кольцевой буфер для экрана диагностики.
 * Файлы: Android/data/io.dodo.obdmap/files/logs/obd-YYYY-MM-DD.log, хранятся 7 дней.
 */
object Logger {

    private const val TAG = "ObdTripMap"
    private const val MEMORY_LINES = 400
    private const val KEEP_DAYS = 7

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Volatile
    private var logDir: File? = null

    fun init(context: Context) {
        if (logDir != null) return
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        dir.mkdirs()
        logDir = dir
        scope.launch { cleanup(dir) }
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val now = Date()
        val line = "${timeFormat.format(now)}  $message"
        _lines.update { old ->
            val next = old + line
            if (next.size > MEMORY_LINES) next.takeLast(MEMORY_LINES) else next
        }
        val dir = logDir ?: return
        scope.launch {
            runCatching {
                File(dir, "obd-${dayFormat.format(now)}.log").appendText("$line\n")
            }
        }
    }

    fun error(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
        log(if (error == null) "ОШИБКА: $message" else "ОШИБКА: $message (${error.message})")
    }

    fun logDirPath(): String = logDir?.absolutePath ?: "(не инициализирован)"

    private fun cleanup(dir: File) {
        val threshold = System.currentTimeMillis() - KEEP_DAYS * 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < threshold) it.delete() }
    }
}
