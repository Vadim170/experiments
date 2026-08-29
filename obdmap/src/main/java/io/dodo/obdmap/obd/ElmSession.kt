package io.dodo.obdmap.obd

import io.dodo.obdmap.util.Logger

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

    private companion object {
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
            "ATSP0" to "автовыбор протокола",
        )
    }

    var supportedPids: Set<Int> = emptySet()
        private set

    var fuelSource: FuelSource = FuelSource.NONE
        private set

    /**
     * Полная инициализация адаптера и шины.
     *
     * @return null при успехе, иначе текст ошибки для интерфейса.
     */
    suspend fun initialize(): String? {
        runCatching { io.send("ATZ", ElmIo.RESET_TIMEOUT_MS) }
            .onFailure { return "адаптер не ответил на сброс: ${it.message}" }

        INIT_COMMANDS.forEach { (command, description) ->
            val response = runCatching { io.send(command) }
                .getOrElse { return "$description: ${it.message}" }
            // На ATZ-эхо и мусор в буфере не ругаемся: важно, что адаптер отвечает.
            Logger.log("ELM $command -> ${response.replace("\r", " ").trim()}")
        }

        // Первый запрос заодно поднимает шину: адаптер сам подберёт протокол.
        val probe = runCatching { io.send(Pids.command(Pids.SUPPORTED_01_20), 10_000) }
            .getOrElse { return "шина не поднялась: ${it.message}" }
        ObdParser.errorOf(probe)?.let { return it }

        supportedPids = detectSupportedPids(probe)
        fuelSource = chooseFuelSource(supportedPids)
        Logger.log("поддерживается PID: ${supportedPids.size}, расход: ${fuelSource.title}")
        return null
    }

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
                val intakeTemp = read(Pids.INTAKE_TEMP)?.let { Pids.intakeTempC(it) }?.toDouble()
                if (rpm != null && mapKpa != null && intakeTemp != null) {
                    FuelMath.fuelRateFromMaf(
                        FuelMath.estimateMafSpeedDensity(rpm, mapKpa, intakeTemp),
                    )
                } else {
                    null
                }
            }

            FuelSource.NONE -> null
        }

        var fuelLevel: Double? = null
        var coolant: Int? = null
        var load: Double? = null
        if (includeSlow) {
            if (Pids.FUEL_LEVEL in supportedPids) {
                fuelLevel = read(Pids.FUEL_LEVEL)?.let { Pids.fuelLevelPercent(it) }
            }
            if (Pids.COOLANT_TEMP in supportedPids) {
                coolant = read(Pids.COOLANT_TEMP)?.let { Pids.coolantTempC(it) }
            }
            if (Pids.ENGINE_LOAD in supportedPids) {
                load = read(Pids.ENGINE_LOAD)?.let { Pids.engineLoadPercent(it) }
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
