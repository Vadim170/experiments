package io.dodo.obdmap.obd

/**
 * PID-ы OBD-II режима 01, которые нужны для карты поездки, и их декодеры.
 *
 * Формулы — из SAE J1979. Все декодеры принимают полезную нагрузку ответа
 * (без префикса 41 XX) и возвращают null, если байтов не хватает.
 */
object Pids {

    // Битовые карты поддерживаемых PID
    const val SUPPORTED_01_20 = 0x00
    const val SUPPORTED_21_40 = 0x20
    const val SUPPORTED_41_60 = 0x40

    const val ENGINE_LOAD = 0x04
    const val COOLANT_TEMP = 0x05
    const val RPM = 0x0C
    const val SPEED = 0x0D
    const val INTAKE_TEMP = 0x0F
    const val MAF = 0x10
    const val THROTTLE = 0x11
    const val INTAKE_MAP = 0x0B
    const val FUEL_LEVEL = 0x2F
    const val MODULE_VOLTAGE = 0x42
    /** Engine fuel rate, л/ч. Поддерживается далеко не всеми блоками. */
    const val ENGINE_FUEL_RATE = 0x5E

    /** Скорость, км/ч. */
    fun speedKmh(data: IntArray): Int? = data.getOrNull(0)

    /** Обороты, об/мин. */
    fun rpm(data: IntArray): Double? =
        if (data.size >= 2) (data[0] * 256 + data[1]) / 4.0 else null

    /** Массовый расход воздуха, г/с. */
    fun mafGramsPerSecond(data: IntArray): Double? =
        if (data.size >= 2) (data[0] * 256 + data[1]) / 100.0 else null

    /** Расход топлива по данным ЭБУ, л/ч. */
    fun fuelRateLitersPerHour(data: IntArray): Double? =
        if (data.size >= 2) (data[0] * 256 + data[1]) / 20.0 else null

    /** Уровень топлива в баке, %. */
    fun fuelLevelPercent(data: IntArray): Double? =
        data.getOrNull(0)?.let { it * 100.0 / 255.0 }

    /** Температура охлаждающей жидкости, °C. */
    fun coolantTempC(data: IntArray): Int? = data.getOrNull(0)?.minus(40)

    /** Температура впускного воздуха, °C. */
    fun intakeTempC(data: IntArray): Int? = data.getOrNull(0)?.minus(40)

    /** Абсолютное давление во впуске, кПа. */
    fun intakeMapKpa(data: IntArray): Int? = data.getOrNull(0)

    /** Расчётная нагрузка на двигатель, %. */
    fun engineLoadPercent(data: IntArray): Double? =
        data.getOrNull(0)?.let { it * 100.0 / 255.0 }

    /** Положение дросселя, %. */
    fun throttlePercent(data: IntArray): Double? =
        data.getOrNull(0)?.let { it * 100.0 / 255.0 }

    /** Напряжение бортовой сети по данным блока, В. */
    fun moduleVoltage(data: IntArray): Double? =
        if (data.size >= 2) (data[0] * 256 + data[1]) / 1000.0 else null

    /** Команда режима 01 для ELM327: "010D" и т.п. */
    fun command(pid: Int): String = "01%02X".format(pid)
}
