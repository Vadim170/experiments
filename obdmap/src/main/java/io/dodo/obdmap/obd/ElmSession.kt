package io.dodo.obdmap.obd

import io.dodo.obdmap.util.Logger
import kotlinx.coroutines.delay

/** Откуда берём расход топлива — зависит от того, что поддерживает блок. */
enum class FuelSource {
    /** PID 0x5E: блок сам отдаёт л/ч. */
    ENGINE_FUEL_RATE,

    /** PID 0x10: считаем из расхода воздуха. */
    MAF,

    /** RPM + MAP + IAT: грубая оценка, когда MAF-датчика нет. */
    SPEED_DENSITY,

    /** Ничего из перечисленного — расход посчитать не из чего. */
    NONE;

    val title: String
        get() = when (this) {
            ENGINE_FUEL_RATE -> "PID 5E (расход от ЭБУ)"
            MAF -> "PID 10 (MAF)"
            SPEED_DENSITY -> "оценка speed-density"
            NONE -> "нет источника"
        }
}

/** Снимок показаний за один цикл опроса. */
data class ObdSnapshot(
    val timeMs: Long,
    val speedKmh: Double? = null,
    val rpm: Double? = null,
    val fuelRateLitersPerHour: Double? = null,
    val fuelLevelPercent: Double? = null,
    val coolantTempC: Int? = null,
    val engineLoadPercent: Double? = null,
)

/**
 * Протокол общения с ELM327: инициализация, определение поддерживаемых PID,
 * циклический опрос.
 */
class ElmSession(private val io: ElmIo) {

    internal companion object {
        /**
         * Инициализация: эхо/переводы строк/пробелы/заголовки выключаем, протокол
         * выбираем автоматически. Ответы после этого — голый hex, что и разбирает
         * [ObdParser].
         */
        val INIT_COMMANDS = listOf(
            "ATE0" to "выключаю эхо",
            "ATL0" to "выключаю переводы строк",
            "ATS0" to "выключаю пробелы",
            "ATH0" to "выключаю заголовки",
        )

        /**
         * Протоколы OBD-II в порядке перебора.
         *
         * Автовыбор пробуем первым, но полагаться только на него нельзя: часть
         * клонов ELM327 при автопоиске отвечает UNABLE TO CONNECT на машине,
         * с которой прекрасно работает при явно заданном протоколе. Для
         * легковых 2008+ это почти всегда CAN 11 бит 500 кбод (номер 6),
         * поэтому он идёт сразу за автовыбором.
         */
        val PROTOCOLS = listOf(
            "0" to "автовыбор",
            "6" to "ISO 15765-4 CAN 11 бит 500 кбод",
            "7" to "ISO 15765-4 CAN 29 бит 500 кбод",
            "8" to "ISO 15765-4 CAN 11 бит 250 кбод",
            "9" to "ISO 15765-4 CAN 29 бит 250 кбод",
            "5" to "ISO 14230-4 KWP (fast init)",
            "3" to "ISO 9141-2",
        )

        /** Сколько ждём, пока адаптер домолчит после ATZ. */
        const val AFTER_RESET_SETTLE_MS = 700L

        /** Пауза перед повтором потерянной команды. */
        const val RETRY_PAUSE_MS = 300L

        /** Автопоиску даём две попытки: первая часто уходит на SEARCHING. */
        const val AUTO_PROBE_ATTEMPTS = 2

        /** Автопоиск думает дольше явного протокола. */
        const val AUTO_PROBE_TIMEOUT_MS = 12_000L
        const val PROBE_TIMEOUT_MS = 6_000L

        /** Столько неудачных циклов подряд — и переопределяем источник расхода. */
        const val REPROBE_AFTER_MISSES = 12

        /** Подстановка, когда датчика температуры впуска нет. */
        const val DEFAULT_INTAKE_TEMP_C = 25.0

        /**
         * Что говорить, когда ни один протокол не отозвался. Это уже не про
         * приложение: адаптер на связи и выполняет команды, а машина молчит.
         */
        const val BUS_ERROR_HINT = "шина не отвечает ни на одном протоколе. " +
            "Проверь: включено ли зажигание, до конца ли вставлен адаптер, " +
            "не занят ли он другим приложением"
    }

    var supportedPids: Set<Int> = emptySet()
        private set

    var fuelSource: FuelSource = FuelSource.NONE
        private set

    /** Что ответило при последней пробе — показываем на экране, чтобы не гадать. */
    var diagnostics: String = ""
        private set

    /** Протокол, на котором шина в итоге ответила. */
    var protocol: String = ""
        private set

    /** Сколько циклов подряд источник расхода молчит. */
    private var missStreak = 0

    /** Есть ли датчик температуры впуска: без него speed-density берёт 25 °C. */
    private var hasIntakeTemp = false

    /**
     * Медленные показатели, на которые блок реально ответил.
     *
     * Как и с расходом, битовой карте тут верить нельзя: уровень бака часто в
     * ней не заявлен, но читается — из-за этой проверки бак и не показывался.
     * Пробуем все разом при первом медленном цикле и дальше спрашиваем только
     * то, что ответило.
     */
    private var slowPids: Set<Int>? = null

    /**
     * Полная инициализация адаптера и шины.
     *
     * @return null при успехе, иначе текст ошибки для интерфейса.
     */
    suspend fun initialize(): String? {
        val banner = runCatching { io.send("ATZ", ElmIo.RESET_TIMEOUT_MS) }
            .getOrElse { return "адаптер не ответил на сброс: ${it.message}" }
        // Версия прошивки в баннере — первое, что нужно знать про клон
        Logger.log("ELM ATZ -> ${clean(banner)}")

        // После сброса клон ещё какое-то время досылает баннер и мусор.
        // Если это не выбросить, хвост достанется следующей команде, ответы
        // разъедутся на одну, и очередная будет ждать уже съеденный ответ.
        delay(AFTER_RESET_SETTLE_MS)
        io.flush()

        INIT_COMMANDS.forEach { (command, description) ->
            val response = sendWithRetry(command)
                ?: return "$description: адаптер не ответил"
            Logger.log("ELM $command -> ${clean(response)}")
        }

        val probe = openBus() ?: return BUS_ERROR_HINT

        supportedPids = detectSupportedPids(probe)
        fuelSource = probeFuelSource()
        Logger.log("поддерживается PID: ${supportedPids.size}, расход: ${fuelSource.title}")
        return null
    }

    /**
     * Отправляет команду, при неудаче — ещё раз после сброса буфера.
     *
     * Один потерянный ответ у клона не редкость, а вся инициализация из-за
     * него валится целиком.
     */
    private suspend fun sendWithRetry(command: String, timeoutMs: Long = ElmIo.DEFAULT_TIMEOUT_MS): String? {
        runCatching { io.send(command, timeoutMs) }.onSuccess { return it }
            .onFailure { Logger.log("ELM $command не ответил, повторяю: ${it.message}") }
        io.flush()
        delay(RETRY_PAUSE_MS)
        return runCatching { io.send(command, timeoutMs) }.getOrNull()
    }

    /**
     * Поднимает шину, перебирая протоколы. Успехом считаем не «нет ошибки», а
     * разобранный ответ на 0100: клоны умеют отвечать мусором без слова ERROR.
     *
     * @return сырой ответ на 0100 либо null, если ни один протокол не ответил
     */
    private suspend fun openBus(): String? {
        val command = Pids.command(Pids.SUPPORTED_01_20)

        PROTOCOLS.forEach { (code, title) ->
            val set = sendWithRetry("ATSP$code")
            if (set == null) {
                Logger.error("не удалось задать протокол $title")
                return@forEach
            }

            val attempts = if (code == "0") AUTO_PROBE_ATTEMPTS else 1
            val timeout = if (code == "0") AUTO_PROBE_TIMEOUT_MS else PROBE_TIMEOUT_MS

            repeat(attempts) {
                val response = runCatching { io.send(command, timeout) }.getOrNull()
                if (response != null &&
                    ObdParser.dataBytes(0x01, Pids.SUPPORTED_01_20, response, command) != null
                ) {
                    protocol = title
                    Logger.log("шина поднялась, протокол: $title")
                    return response
                }
                val reason = response?.let { ObdParser.errorOf(it) ?: clean(it) } ?: "нет ответа"
                Logger.log("протокол $title не подошёл: $reason")
            }
        }
        return null
    }

    private fun clean(response: String) =
        response.replace("\r", " ").replace("\n", " ").replace(">", "").trim()

    /**
     * Обходит битовые карты 0x00/0x20/0x40 и собирает список поддерживаемых PID.
     * Ответ на первую карту уже получен при инициализации — переиспользуем его.
     */
    suspend fun detectSupportedPids(firstResponse: String? = null): Set<Int> {
        val result = mutableSetOf<Int>()
        val bases = listOf(Pids.SUPPORTED_01_20, Pids.SUPPORTED_21_40, Pids.SUPPORTED_41_60)

        bases.forEachIndexed { index, base ->
            val raw = if (index == 0 && firstResponse != null) {
                firstResponse
            } else {
                runCatching { io.send(Pids.command(base)) }.getOrNull() ?: return@forEachIndexed
            }
            val data = ObdParser.dataBytes(0x01, base, raw, echoOf = Pids.command(base))
                ?: return@forEachIndexed
            result += ObdParser.supportedPids(base, data)
            // Старший бит последнего байта = «следующая карта тоже поддерживается».
            if (data.size < 4 || data[3] and 0x01 == 0) return result
        }
        return result
    }

    /**
     * Определяет источник расхода реальным запросом, а не битовой картой.
     *
     * Карта врёт в обе стороны: блоки отвечают на PID, которых в ней нет, и
     * молчат на обещанные. Именно из-за доверия к карте расход мог не считаться
     * вовсе, поэтому теперь спрашиваем напрямую и запоминаем, что ответило.
     */
    suspend fun probeFuelSource(): FuelSource {
        val notes = mutableListOf<String>()

        // Скорость нужна и сама по себе, и как вход для ускорения — показываем
        // её состояние первой, чтобы было видно, если молчит именно она.
        notes += if (read(Pids.SPEED) != null) "SPD ✓" else "SPD —"

        val engineRate = read(Pids.ENGINE_FUEL_RATE)?.let { Pids.fuelRateLitersPerHour(it) }
        notes += if (engineRate != null) "5E ✓" else "5E —"
        if (engineRate != null) {
            diagnostics = notes.joinToString(" · ")
            return FuelSource.ENGINE_FUEL_RATE
        }

        val maf = read(Pids.MAF)?.let { Pids.mafGramsPerSecond(it) }
        notes += if (maf != null) "MAF ✓" else "MAF —"
        if (maf != null) {
            diagnostics = notes.joinToString(" · ")
            return FuelSource.MAF
        }

        val rpm = read(Pids.RPM)?.let { Pids.rpm(it) }
        val mapKpa = read(Pids.INTAKE_MAP)?.let { Pids.intakeMapKpa(it) }
        hasIntakeTemp = read(Pids.INTAKE_TEMP)?.let { Pids.intakeTempC(it) } != null
        notes += if (rpm != null) "RPM ✓" else "RPM —"
        notes += if (mapKpa != null) "MAP ✓" else "MAP —"
        notes += if (hasIntakeTemp) "IAT ✓" else "IAT —"
        diagnostics = notes.joinToString(" · ")

        // Температура впуска не обязательна: без неё подставим 25 °C
        if (rpm != null && mapKpa != null) return FuelSource.SPEED_DENSITY

        // Последний шанс — то, что обещает битовая карта
        return chooseFuelSource(supportedPids)
    }

    fun chooseFuelSource(supported: Set<Int>): FuelSource = when {
        Pids.ENGINE_FUEL_RATE in supported -> FuelSource.ENGINE_FUEL_RATE
        Pids.MAF in supported -> FuelSource.MAF
        Pids.RPM in supported && Pids.INTAKE_MAP in supported &&
            Pids.INTAKE_TEMP in supported -> FuelSource.SPEED_DENSITY
        else -> FuelSource.NONE
    }

    /** Читает один PID и отдаёт полезную нагрузку. */
    suspend fun read(pid: Int): IntArray? {
        val command = Pids.command(pid)
        val raw = runCatching { io.send(command) }.getOrElse {
            Logger.error("не прочитал PID %02X".format(pid), it)
            return null
        }
        return ObdParser.dataBytes(0x01, pid, raw, echoOf = command)
    }

    /**
     * Цикл опроса: скорость и расход каждый раз, медленно меняющиеся показатели —
     * только когда попросили ([includeSlow]).
     */
    suspend fun readSnapshot(nowMs: Long, includeSlow: Boolean = false): ObdSnapshot {
        val speed = read(Pids.SPEED)?.let { Pids.speedKmh(it)?.toDouble() }
        val rpm = if (needsRpm()) read(Pids.RPM)?.let { Pids.rpm(it) } else null

        val fuelRate = when (fuelSource) {
            FuelSource.ENGINE_FUEL_RATE ->
                read(Pids.ENGINE_FUEL_RATE)?.let { Pids.fuelRateLitersPerHour(it) }

            FuelSource.MAF ->
                read(Pids.MAF)?.let { Pids.mafGramsPerSecond(it) }?.let { FuelMath.fuelRateFromMaf(it) }

            FuelSource.SPEED_DENSITY -> {
                val mapKpa = read(Pids.INTAKE_MAP)?.let { Pids.intakeMapKpa(it) }?.toDouble()
                val intakeTemp = if (hasIntakeTemp) {
                    read(Pids.INTAKE_TEMP)?.let { Pids.intakeTempC(it) }?.toDouble()
                } else {
                    null
                } ?: DEFAULT_INTAKE_TEMP_C
                if (rpm != null && mapKpa != null) {
                    FuelMath.fuelRateFromMaf(
                        FuelMath.estimateMafSpeedDensity(rpm, mapKpa, intakeTemp),
                    )
                } else {
                    null
                }
            }

            FuelSource.NONE -> null
        }

        // Источник мог быть выбран на заглушенном моторе и замолчать на ходу
        // (или наоборот). Молчит подряд — пробуем определить заново.
        if (fuelRate == null) {
            missStreak++
            if (missStreak >= REPROBE_AFTER_MISSES) {
                missStreak = 0
                val previous = fuelSource
                fuelSource = probeFuelSource()
                if (fuelSource != previous) {
                    Logger.log("источник расхода переопределён: ${fuelSource.title}")
                }
            }
        } else {
            missStreak = 0
        }

        var fuelLevel: Double? = null
        var coolant: Int? = null
        var load: Double? = null
        if (includeSlow) {
            // Первый медленный цикл пробует всё, дальше спрашиваем только то,
            // что действительно ответило.
            val known = slowPids
            val answered = mutableSetOf<Int>()

            if (known == null || Pids.FUEL_LEVEL in known) {
                fuelLevel = read(Pids.FUEL_LEVEL)?.let { Pids.fuelLevelPercent(it) }
                if (fuelLevel != null) answered += Pids.FUEL_LEVEL
            }
            if (known == null || Pids.COOLANT_TEMP in known) {
                coolant = read(Pids.COOLANT_TEMP)?.let { Pids.coolantTempC(it) }
                if (coolant != null) answered += Pids.COOLANT_TEMP
            }
            if (known == null || Pids.ENGINE_LOAD in known) {
                load = read(Pids.ENGINE_LOAD)?.let { Pids.engineLoadPercent(it) }
                if (load != null) answered += Pids.ENGINE_LOAD
            }

            if (known == null) {
                slowPids = answered
                Logger.log(
                    "медленные PID ответили: " +
                        if (answered.isEmpty()) "ни один" else answered.joinToString { "%02X".format(it) },
                )
            }
        }

        return ObdSnapshot(
            timeMs = nowMs,
            speedKmh = speed,
            rpm = rpm,
            fuelRateLitersPerHour = fuelRate,
            fuelLevelPercent = fuelLevel,
            coolantTempC = coolant,
            engineLoadPercent = load,
        )
    }

    /** Обороты нужны либо сами по себе, либо как вход для speed-density. */
    private fun needsRpm(): Boolean =
        Pids.RPM in supportedPids || fuelSource == FuelSource.SPEED_DENSITY
}
