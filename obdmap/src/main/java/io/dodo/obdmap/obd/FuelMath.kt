package io.dodo.obdmap.obd

/**
 * Расчёт расхода топлива.
 *
 * Škoda Rapid 2022 — обычный бензиновый VAG, поэтому по умолчанию бензиновые
 * константы. Источники данных, по убыванию точности:
 *
 * 1. PID 0x5E (engine fuel rate) — блок сам отдаёт л/ч. На VAG встречается редко.
 * 2. PID 0x10 (MAF) — расход воздуха, делим на стехиометрию. Рабочий вариант
 *    для 1.6 MPI и большинства TSI.
 * 3. Speed-density из RPM + MAP + IAT — если MAF-датчика нет вовсе. Оценка
 *    грубая: зависит от коэффициента наполнения, который мы не знаем.
 */
object FuelMath {

    /** Стехиометрия для бензина: столько граммов воздуха на грамм топлива. */
    const val STOICH_AFR_GASOLINE = 14.7

    /** Плотность бензина, г/л (АИ-95, ~15 °C). */
    const val GASOLINE_DENSITY_G_PER_L = 745.0

    /** Объём двигателя Škoda Rapid 2022 1.6 MPI, л. */
    const val DEFAULT_DISPLACEMENT_L = 1.598

    /** Коэффициент наполнения по умолчанию для оценки speed-density. */
    const val DEFAULT_VOLUMETRIC_EFFICIENCY = 0.80

    private const val AIR_MOLAR_MASS_G_PER_MOL = 28.97
    private const val GAS_CONSTANT_J_PER_MOL_K = 8.314

    /**
     * Расход топлива из массового расхода воздуха.
     *
     * @param mafGramsPerSecond г/с воздуха
     * @return л/ч топлива
     */
    fun fuelRateFromMaf(
        mafGramsPerSecond: Double,
        afr: Double = STOICH_AFR_GASOLINE,
        densityGPerL: Double = GASOLINE_DENSITY_G_PER_L,
    ): Double {
        if (mafGramsPerSecond <= 0) return 0.0
        val fuelGramsPerSecond = mafGramsPerSecond / afr
        return fuelGramsPerSecond * 3600.0 / densityGPerL
    }

    /**
     * Оценка массового расхода воздуха по оборотам, давлению и температуре впуска
     * (speed-density). Применяется, только когда MAF-датчика нет.
     *
     * @return г/с
     */
    fun estimateMafSpeedDensity(
        rpm: Double,
        mapKpa: Double,
        intakeTempC: Double,
        displacementL: Double = DEFAULT_DISPLACEMENT_L,
        volumetricEfficiency: Double = DEFAULT_VOLUMETRIC_EFFICIENCY,
    ): Double {
        val intakeTempK = intakeTempC + 273.15
        if (rpm <= 0 || mapKpa <= 0 || intakeTempK <= 0) return 0.0
        val imap = rpm * mapKpa / intakeTempK / 2.0
        return (imap / 60.0) * volumetricEfficiency * displacementL *
            AIR_MOLAR_MASS_G_PER_MOL / GAS_CONSTANT_J_PER_MOL_K
    }

    /**
     * Мгновенный расход в л/100 км.
     *
     * На стоянке величина не определена (деление на ноль), поэтому ниже
     * [MIN_SPEED_KMH] возвращаем null — интерфейс в этот момент показывает л/ч.
     */
    fun litersPer100Km(fuelRateLitersPerHour: Double, speedKmh: Double): Double? {
        if (speedKmh < MIN_SPEED_KMH) return null
        return fuelRateLitersPerHour / speedKmh * 100.0
    }

    /** Средний расход за поездку. */
    fun averageLitersPer100Km(fuelLiters: Double, distanceMeters: Double): Double? {
        if (distanceMeters < MIN_DISTANCE_M) return null
        return fuelLiters / (distanceMeters / 1000.0) * 100.0
    }

    /** Ниже этой скорости мгновенный л/100 км не считаем. */
    const val MIN_SPEED_KMH = 3.0

    /** Ниже этого пробега средний расход не считаем — слишком шумно. */
    const val MIN_DISTANCE_M = 50.0
}
