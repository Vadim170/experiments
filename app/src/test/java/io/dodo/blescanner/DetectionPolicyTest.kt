package io.dodo.blescanner

import io.dodo.blescanner.ble.DetectionPolicy
import io.dodo.blescanner.model.Detection
import io.dodo.blescanner.model.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionPolicyTest {

    private val now = 1_700_000_000_000L

    private fun fix(lat: Double, lon: Double, time: Long = now) =
        LocationFix(lat, lon, accuracyMeters = 5f, provider = "gps", timeMs = time)

    private fun detection(time: Long, location: LocationFix?) =
        Detection(timeMs = time, rssi = -70, location = location)

    @Test
    fun `первое обнаружение записывается всегда`() {
        assertTrue(DetectionPolicy.shouldRecord(null, now, null))
        assertTrue(DetectionPolicy.shouldRecord(null, now, fix(55.0, 37.0)))
    }

    @Test
    fun `свежая точка в том же месте не дублируется`() {
        val last = detection(now - 5_000, fix(55.0, 37.0))
        assertFalse(DetectionPolicy.shouldRecord(last, now, fix(55.0, 37.0)))
    }

    @Test
    fun `по таймауту точка записывается даже без движения`() {
        val last = detection(now - DetectionPolicy.MIN_TIME_GAP_MS, fix(55.0, 37.0))
        assertTrue(DetectionPolicy.shouldRecord(last, now, fix(55.0, 37.0)))
    }

    @Test
    fun `заметное смещение записывается сразу`() {
        val last = detection(now - 5_000, fix(55.0, 37.0))
        // ~0.001 градуса широты ≈ 111 м, порог 25 м
        assertTrue(DetectionPolicy.shouldRecord(last, now, fix(55.001, 37.0)))
    }

    @Test
    fun `сдвиг меньше порога игнорируется`() {
        val last = detection(now - 5_000, fix(55.0, 37.0))
        // ~0.0001 градуса ≈ 11 м, порог 25 м
        assertFalse(DetectionPolicy.shouldRecord(last, now, fix(55.0001, 37.0)))
    }

    @Test
    fun `появление координат фиксируется не дожидаясь таймаута`() {
        val last = detection(now - 5_000, null)
        assertTrue(DetectionPolicy.shouldRecord(last, now, fix(55.0, 37.0)))
    }

    @Test
    fun `пропажа координат новую точку не создаёт`() {
        val last = detection(now - 5_000, fix(55.0, 37.0))
        assertFalse(DetectionPolicy.shouldRecord(last, now, null))
    }

    @Test
    fun `без координат вообще ждём таймаут`() {
        val last = detection(now - 5_000, null)
        assertFalse(DetectionPolicy.shouldRecord(last, now, null))

        val old = detection(now - DetectionPolicy.MIN_TIME_GAP_MS, null)
        assertTrue(DetectionPolicy.shouldRecord(old, now, null))
    }

    @Test
    fun `список точек не растёт бесконечно`() {
        var detections = emptyList<Detection>()
        repeat(DetectionPolicy.MAX_DETECTIONS + 20) { index ->
            detections = DetectionPolicy.append(
                detections,
                detection(now + index * 1_000L, fix(55.0, 37.0)),
            )
        }
        assertEquals(DetectionPolicy.MAX_DETECTIONS, detections.size)
        // выбрасываются самые старые, свежая остаётся последней
        assertEquals(
            now + (DetectionPolicy.MAX_DETECTIONS + 19) * 1_000L,
            detections.last().timeMs,
        )
    }
}
