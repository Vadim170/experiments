package io.dodo.blescanner.ble

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
 * Логирование в logcat, в файл на внешнем private-storage приложения и
 * в кольцевой буфер для показа на экране.
 *
 * Файлы лежат в Android/data/io.dodo.blescanner/files/logs/ble-YYYY-MM-DD.log —
 * их видно через файловый менеджер / adb pull без каких-либо разрешений.
 */
object BleLogger {

    private const val TAG = "BleScanner"
    private const val MEMORY_LINES = 500
    private const val KEEP_DAYS = 7

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** Последние строки лога для UI, самые свежие — в конце. */
    val lines: StateFlow<List<String>> = _lines

    @Volatile
    private var logDir: File? = null

    fun init(context: Context) {
        if (logDir != null) return
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        dir.mkdirs()
        logDir = dir
        scope.launch { cleanupOldFiles(dir) }
        log("Логи пишутся в ${dir.absolutePath}")
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
                File(dir, "ble-${dayFormat.format(now)}.log")
                    .appendText("${dayFormat.format(now)} $line\n")
            }
        }
    }

    fun logError(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
        log(if (error == null) "ОШИБКА: $message" else "ОШИБКА: $message (${error.message})")
    }

    /** Путь к каталогу с логами — показываем на экране. */
    fun logDirPath(): String = logDir?.absolutePath ?: "(не инициализирован)"

    private fun cleanupOldFiles(dir: File) {
        val threshold = System.currentTimeMillis() - KEEP_DAYS * 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < threshold) file.delete()
        }
    }
}
