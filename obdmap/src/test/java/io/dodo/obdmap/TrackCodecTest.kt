package io.dodo.obdmap

import io.dodo.obdmap.data.HistoryStore
import io.dodo.obdmap.data.PointEntity
import io.dodo.obdmap.data.TrackCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackCodecTest {

    private fun track(count: Int) = List(count) { index ->
        PointEntity(
            tripId = 1,
            timeMs = 1_700_000_000_000L + index * 300L,
            latitude = 55.75 + index * 0.00002,
            longitude = 37.61 + index * 0.00003,
            speedKmh = 40.0 + index % 30,
            rpm = 1500.0 + index % 500,
            fuelRateLitersPerHour = 5.0 + index % 4 * 0.25,
            litersPer100Km = 7.0 + index % 5 * 0.1,
            accelerationMs2 = (index % 7 - 3) * 0.4,
        )
    }

    @Test
    fun `трек переживает упаковку и распаковку`() {
        val original = track(500)
        val restored = TrackCodec.decode(1, TrackCodec.encode(original))

        assertEquals(original.size, restored.size)
        original.zip(restored).forEach { (a, b) ->
            assertEquals(a.timeMs, b.timeMs)
            assertEquals(a.latitude!!, b.latitude!!, 0.0000005)
            assertEquals(a.longitude!!, b.longitude!!, 0.0000005)
            assertEquals(a.speedKmh!!, b.speedKmh!!, 0.05)
            assertEquals(a.litersPer100Km!!, b.litersPer100Km!!, 0.005)
            assertEquals(a.accelerationMs2!!, b.accelerationMs2!!, 0.0005)
        }
    }

    @Test
    fun `пропуски остаются пропусками`() {
        val points = listOf(
            PointEntity(tripId = 7, timeMs = 1000, latitude = null, longitude = null, speedKmh = 30.0),
        )
        val restored = TrackCodec.decode(7, TrackCodec.encode(points))
        assertEquals(1, restored.size)
        assertNull(restored[0].latitude)
        assertNull(restored[0].longitude)
        assertNull(restored[0].accelerationMs2)
        assertEquals(30.0, restored[0].speedKmh!!, 0.05)
    }

    @Test
    fun `архив заметно меньше исходных строк`() {
        val points = track(3_000)
        val blob = TrackCodec.encode(points)
        // 110 байт на строку в базе против сжатого блоба
        val rawBytes = points.size * HistoryStore.BYTES_PER_POINT
        assertTrue(
            "архив ${blob.size} Б против ${rawBytes} Б — сжатие слишком слабое",
            blob.size * 4 < rawBytes,
        )
    }

    @Test
    fun `пустой трек не ломает кодек`() {
        assertTrue(TrackCodec.decode(1, TrackCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun `мусор вместо архива не роняет разбор`() {
        assertTrue(TrackCodec.decode(1, ByteArray(0)).isEmpty())
        assertTrue(TrackCodec.decode(1, byteArrayOf(1, 2, 3, 4, 5)).isEmpty())
        assertTrue(TrackCodec.decode(1, "не архив".toByteArray()).isEmpty())
    }

    @Test
    fun `прореживание сохраняет края`() {
        val points = track(1000)
        val thinned = HistoryStore.thin(points, 100)
        assertTrue(thinned.size in 100..102)
        assertEquals(points.first().timeMs, thinned.first().timeMs)
        assertEquals(points.last().timeMs, thinned.last().timeMs)
    }

    @Test
    fun `короткий трек не прореживается`() {
        val points = track(50)
        assertEquals(50, HistoryStore.thin(points, 100).size)
    }

    @Test
    fun `оценка времени записи для лимита правдоподобна`() {
        // 500 МБ при 110 Б на точку и 3 точках в секунду
        val hours = HistoryStore.estimatedHours(500L * 1024 * 1024)
        assertTrue("ожидали сотни часов, получили $hours", hours in 300.0..600.0)
    }
}
