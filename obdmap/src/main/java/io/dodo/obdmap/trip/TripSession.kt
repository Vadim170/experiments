package io.dodo.obdmap.trip

import io.dodo.obdmap.obd.FuelSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Стадия работы с адаптером — по ней экран решает, что показывать. */
enum class ConnectionState {
    IDLE,
    CONNECTING,
    INITIALIZING,
    LIVE,
    ERROR,
}

/** Точка живого трека для карты. */
data class TrackPoint(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double? = null,
    val litersPer100Km: Double? = null,
)

/** Всё, что показывает экран поездки прямо сейчас. */
data class LiveState(
    val connection: ConnectionState = ConnectionState.IDLE,
    val status: String = "Не подключено",
    val fuelSource: FuelSource = FuelSource.NONE,
    val speedKmh: Double? = null,
    val rpm: Double? = null,
    val fuelRateLitersPerHour: Double? = null,
    val litersPer100Km: Double? = null,
    val fuelLevelPercent: Double? = null,
    val coolantTempC: Int? = null,
    val stats: TripStats = TripStats(),
    val tripId: Long? = null,
    val hasLocation: Boolean = false,
)

/**
 * Мост между сервисом записи и интерфейсом. Живёт в процессе: экран может
 * пересоздаваться сколько угодно, запись от этого не прерывается.
 */
object TripSession {

    /** Больше точек карта всё равно не отрисует осмысленно, а память съест. */
    private const val MAX_TRACK_POINTS = 20_000

    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live

    private val _track = MutableStateFlow<List<TrackPoint>>(emptyList())
    val track: StateFlow<List<TrackPoint>> = _track

    fun update(transform: (LiveState) -> LiveState) {
        _live.value = transform(_live.value)
    }

    fun setStatus(connection: ConnectionState, status: String) {
        update { it.copy(connection = connection, status = status) }
    }

    fun addTrackPoint(point: TrackPoint) {
        val next = _track.value + point
        _track.value = if (next.size > MAX_TRACK_POINTS) next.takeLast(MAX_TRACK_POINTS) else next
    }

    fun startNewTrip(tripId: Long) {
        _track.value = emptyList()
        _live.value = LiveState(
            connection = ConnectionState.LIVE,
            status = "Идёт запись",
            fuelSource = _live.value.fuelSource,
            tripId = tripId,
        )
    }

    fun reset(status: String) {
        _live.value = LiveState(connection = ConnectionState.IDLE, status = status)
        _track.value = emptyList()
    }
}
