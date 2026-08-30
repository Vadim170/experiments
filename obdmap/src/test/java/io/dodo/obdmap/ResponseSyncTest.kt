package io.dodo.obdmap

import io.dodo.obdmap.obd.ElmIo
import io.dodo.obdmap.obd.ElmSession
import io.dodo.obdmap.obd.ObdParser
import io.dodo.obdmap.obd.Pids
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Рассинхрон ответов: хвост предыдущей команды приезжает вместе с ответом на
 * текущую. Ровно это ломало инициализацию на живом адаптере — в лог попадало
 * "ELM ATZ -> OKELM327 v2.1", а следующая команда ждала уже съеденный ответ.
 */
class ResponseSyncTest {

    @Test
    fun `склеенные ответы разделяются и берётся последний`() {
        assertEquals("ELM327 v2.1", ObdParser.lastResponse("OK\r\r>ELM327 v2.1\r\r>"))
    }

    @Test
    fun `обычный ответ не портится`() {
        assertEquals("410D3C", ObdParser.lastResponse("410D3C\r\r>"))
        assertEquals("OK", ObdParser.lastResponse("OK\r\r>"))
    }

    @Test
    fun `многострочный ответ остаётся целым`() {
        // Приглашение приходит только в конце, внутри ответа его нет
        val raw = "410D3C\r410D3C\r\r>"
        assertTrue(ObdParser.lastResponse(raw).contains("410D3C"))
        assertEquals(
            60,
            Pids.speedKmh(ObdParser.dataBytes(0x01, Pids.SPEED, ObdParser.lastResponse(raw))!!),
        )
    }

    @Test
    fun `ответ без приглашения возвращается как есть`() {
        assertEquals("410D3C", ObdParser.lastResponse("410D3C"))
    }

    @Test
    fun `пустой ответ не ломает разбор`() {
        assertEquals(">", ObdParser.lastResponse(">"))
    }

    @Test
    fun `потерянный ответ на команду повторяется`() = runTest {
        // Клон роняет первый ATS0 — раньше на этом валилась вся инициализация
        val io = object : ElmIo {
            val sent = mutableListOf<String>()
            var droppedOnce = false

            override suspend fun send(command: String, timeoutMs: Long): String {
                sent += command
                if (command == "ATS0" && !droppedOnce) {
                    droppedOnce = true
                    throw java.io.IOException("таймаут ответа на ATS0")
                }
                if (command == "ATZ") return "ELM327 v2.1"
                if (command.startsWith("AT")) return "OK"
                if (command == "0100") return "4100BE1FA813"
                if (command == "0120") return "412080000000"
                if (command == "010D") return "410D3C"
                if (command == "0110") return "41100BB8"
                return "NO DATA"
            }
        }

        val session = ElmSession(io)
        assertNull("инициализация не должна падать из-за одного потерянного ответа", session.initialize())
        assertEquals(2, io.sent.count { it == "ATS0" })
    }

    @Test
    fun `буфер сбрасывается после сброса адаптера`() = runTest {
        val io = object : ElmIo {
            var flushed = 0
            override suspend fun send(command: String, timeoutMs: Long): String = when {
                command == "ATZ" -> "ELM327 v2.1"
                command.startsWith("AT") -> "OK"
                command == "0100" -> "4100BE1FA813"
                command == "0120" -> "412080000000"
                else -> "NO DATA"
            }

            override suspend fun flush() {
                flushed++
            }
        }

        ElmSession(io).initialize()
        assertTrue("после ATZ буфер должен чиститься", io.flushed >= 1)
    }
}
