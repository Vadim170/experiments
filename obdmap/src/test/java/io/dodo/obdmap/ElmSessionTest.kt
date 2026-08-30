package io.dodo.obdmap

import io.dodo.obdmap.obd.ElmIo
import io.dodo.obdmap.obd.ElmSession
import io.dodo.obdmap.obd.FuelSource
import io.dodo.obdmap.obd.Pids
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Подставной адаптер: отдаёт заранее заданные ответы и запоминает команды. */
private class FakeElm(private val responses: Map<String, String>) : ElmIo {

    val sent = mutableListOf<String>()

    override suspend fun send(command: String, timeoutMs: Long): String {
        sent += command
        return responses[command] ?: "NO DATA\r\r>"
    }
}

class ElmSessionTest {

    /** Škoda Rapid отвечает так: есть MAF, нет PID 5E. */
    private val rapidLike = mapOf(
        "ATZ" to "ELM327 v1.5\r\r>",
        "ATE0" to "OK\r\r>",
        "ATL0" to "OK\r\r>",
        "ATS0" to "OK\r\r>",
        "ATH0" to "OK\r\r>",
        "ATSP0" to "OK\r\r>",
        // BE1FA813: поддержаны 04, 05, 0C, 0D, 0F, 10 и следующая карта
        "0100" to "4100BE1FA813\r\r>",
        "0120" to "412080000000\r\r>",
        "010D" to "410D3C\r\r>",
        "010C" to "410C1AF8\r\r>",
        "0110" to "41100BB8\r\r>",
    )

    @Test
    fun `инициализация проходит и определяет источник расхода`() = runTest {
        val io = FakeElm(rapidLike)
        val session = ElmSession(io)

        assertNull(session.initialize())

        // порядок важен: эхо и переводы строк надо выключить до первого запроса
        // данных, а источник расхода определяется реальными запросами 5E и 10
        assertEquals(
            listOf(
                "ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0",
                "0100", "0120", "010D", "015E", "0110",
            ),
            io.sent,
        )
        assertTrue(Pids.SPEED in session.supportedPids)
        assertTrue(Pids.MAF in session.supportedPids)
        assertTrue(Pids.ENGINE_FUEL_RATE !in session.supportedPids)
        assertEquals(FuelSource.MAF, session.fuelSource)
    }

    @Test
    fun `обход битовых карт останавливается когда следующей нет`() = runTest {
        val session = ElmSession(FakeElm(rapidLike))
        session.initialize()
        // 0120 отдал 80000000: младший бит сброшен, карта 0x40 не запрашивается
        assertTrue(0x21 in session.supportedPids)
        assertTrue(session.supportedPids.none { it > 0x40 })
    }

    @Test
    fun `шина не поднялась - инициализация возвращает ошибку`() = runTest {
        val io = FakeElm(rapidLike + ("0100" to "UNABLE TO CONNECT\r\r>"))
        val session = ElmSession(io)

        val error = session.initialize()
        assertNotNull(error)
        assertEquals(FuelSource.NONE, session.fuelSource)
        assertEquals("", session.protocol)
    }

    @Test
    fun `снимок считает расход из MAF`() = runTest {
        val session = ElmSession(FakeElm(rapidLike))
        session.initialize()

        val snapshot = session.readSnapshot(nowMs = 1_000)

        assertEquals(60.0, snapshot.speedKmh!!, 0.001)
        assertEquals(1726.0, snapshot.rpm!!, 0.001)
        // 0x0BB8 = 3000 -> 30 г/с -> 30/14.7*3600/745 = 9.86 л/ч
        assertEquals(9.859, snapshot.fuelRateLitersPerHour!!, 0.01)
    }

    @Test
    fun `источник берётся из живого ответа даже если карта о нём молчит`() = runTest {
        // Битовая карта 0x00 не обещает MAF, но блок на него отвечает.
        // Раньше расход в этом случае не считался вовсе.
        val responses = rapidLike +
            mapOf("0100" to "410004000000\r\r>", "0120" to "412000000000\r\r>")
        val session = ElmSession(FakeElm(responses))

        assertNull(session.initialize())
        assertTrue(Pids.MAF !in session.supportedPids)
        assertEquals(FuelSource.MAF, session.fuelSource)
    }

    @Test
    fun `speed-density выбирается без датчика температуры впуска`() = runTest {
        val responses = mapOf(
            "ATZ" to "ELM327 v1.5\r\r>",
            "ATE0" to "OK\r\r>", "ATL0" to "OK\r\r>", "ATS0" to "OK\r\r>",
            "ATH0" to "OK\r\r>", "ATSP0" to "OK\r\r>",
            "0100" to "410000000000\r\r>",
            "010C" to "410C1AF8\r\r>",
            "010B" to "410B2E\r\r>",
            // 010F не отвечает — температуры впуска нет
        )
        val session = ElmSession(FakeElm(responses))

        assertNull(session.initialize())
        assertEquals(FuelSource.SPEED_DENSITY, session.fuelSource)

        val snapshot = session.readSnapshot(nowMs = 1_000)
        assertNotNull("без IAT расход всё равно должен считаться", snapshot.fuelRateLitersPerHour)
        assertTrue(snapshot.fuelRateLitersPerHour!! > 0)
    }

    @Test
    fun `проба записывает что ответило`() = runTest {
        val session = ElmSession(FakeElm(rapidLike))
        session.initialize()
        assertTrue(session.diagnostics.contains("SPD ✓"))
        assertTrue(session.diagnostics.contains("5E —"))
        assertTrue(session.diagnostics.contains("MAF ✓"))
    }

    @Test
    fun `молчащая скорость видна в диагностике`() = runTest {
        // Если молчит скорость, не будет ни трека по OBD, ни ускорения —
        // это должно быть видно на экране, а не гадаться
        val session = ElmSession(FakeElm(rapidLike - "010D"))
        session.initialize()
        assertTrue(session.diagnostics.contains("SPD —"))
    }

    @Test
    fun `замолчавший источник переопределяется на ходу`() = runTest {
        // MAF отвечает при пробе, но потом пропадает; зато появляется 5E
        val responses = rapidLike.toMutableMap()
        val io = object : ElmIo {
            var cycles = 0
            override suspend fun send(command: String, timeoutMs: Long): String {
                if (command == "0110" && cycles > 0) return "NO DATA\r\r>"
                if (command == "015E" && cycles > 0) return "415E0064\r\r>"
                return responses[command] ?: "NO DATA\r\r>"
            }
        }
        val session = ElmSession(io)
        session.initialize()
        assertEquals(FuelSource.MAF, session.fuelSource)

        io.cycles = 1
        repeat(ElmSession.REPROBE_AFTER_MISSES + 1) { session.readSnapshot(nowMs = 1_000) }
        assertEquals(FuelSource.ENGINE_FUEL_RATE, session.fuelSource)
    }

    @Test
    fun `при отказе автовыбора протокол подбирается явно`() = runTest {
        // Ровно то, что было в логе на машине: адаптер отвечает OK на все
        // настройки, а 0100 при автопоиске возвращает UNABLE TO CONNECT
        val io = object : ElmIo {
            val sent = mutableListOf<String>()
            var protocolSet = "0"

            override suspend fun send(command: String, timeoutMs: Long): String {
                sent += command
                if (command.startsWith("ATSP")) {
                    protocolSet = command.removePrefix("ATSP")
                    return "OK\r\r>"
                }
                // Шина отвечает только на явно заданном протоколе 6
                if (protocolSet == "0") return "SEARCHING...\rUNABLE TO CONNECT\r\r>"
                if (protocolSet != "6") return "NO DATA\r\r>"
                return rapidLike[command] ?: "NO DATA\r\r>"
            }
        }
        val session = ElmSession(io)

        assertNull(session.initialize())
        assertEquals("ISO 15765-4 CAN 11 бит 500 кбод", session.protocol)
        assertTrue("автовыбор должен пробоваться первым", io.sent.indexOf("ATSP0") < io.sent.indexOf("ATSP6"))
        assertTrue(Pids.MAF in session.supportedPids)
    }

    @Test
    fun `когда молчат все протоколы ошибка объясняет что проверить`() = runTest {
        val io = object : ElmIo {
            val tried = mutableListOf<String>()
            override suspend fun send(command: String, timeoutMs: Long): String {
                if (command.startsWith("ATSP")) tried += command
                if (command == "0100") return "UNABLE TO CONNECT\r\r>"
                return "OK\r\r>"
            }
        }
        val session = ElmSession(io)

        val error = session.initialize()
        assertNotNull(error)
        assertTrue("ошибка должна подсказывать про зажигание: $error", error!!.contains("зажигание"))
        assertEquals(ElmSession.PROTOCOLS.size, io.tried.size)
    }

    @Test
    fun `мусор вместо данных не считается поднятой шиной`() = runTest {
        // Клоны умеют отвечать чем угодно без слова ERROR
        val io = FakeElm(rapidLike + ("0100" to "?\r\r>"))
        val session = ElmSession(io)
        assertNotNull(session.initialize())
    }

    @Test
    fun `PID 5E важнее MAF когда поддержан`() {
        val session = ElmSession(FakeElm(emptyMap()))
        assertEquals(
            FuelSource.ENGINE_FUEL_RATE,
            session.chooseFuelSource(setOf(Pids.ENGINE_FUEL_RATE, Pids.MAF)),
        )
        assertEquals(FuelSource.MAF, session.chooseFuelSource(setOf(Pids.MAF)))
    }

    @Test
    fun `без MAF берётся speed-density если есть обороты давление и температура`() {
        val session = ElmSession(FakeElm(emptyMap()))
        assertEquals(
            FuelSource.SPEED_DENSITY,
            session.chooseFuelSource(setOf(Pids.RPM, Pids.INTAKE_MAP, Pids.INTAKE_TEMP)),
        )
        assertEquals(FuelSource.NONE, session.chooseFuelSource(setOf(Pids.SPEED)))
    }

    @Test
    fun `неподдержанный PID даёт null а не падение`() = runTest {
        val session = ElmSession(FakeElm(rapidLike))
        session.initialize()
        assertNull(session.read(Pids.ENGINE_FUEL_RATE))
        assertNotNull(session.read(Pids.SPEED))
    }
}
