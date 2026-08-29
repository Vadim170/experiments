package io.dodo.obdmap.obd

/**
 * Транспорт до ELM327: отправить команду — получить сырой ответ до приглашения '>'.
 *
 * Вынесено в интерфейс, чтобы протокол ([ElmSession]) можно было гонять в тестах
 * на подставном адаптере, без BLE и без машины.
 */
interface ElmIo {
    /** @throws java.io.IOException если связь потеряна или адаптер не ответил вовремя. */
    suspend fun send(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L

        /** ATZ (полный сброс) на клонах думает заметно дольше остальных команд. */
        const val RESET_TIMEOUT_MS = 12_000L
    }
}
