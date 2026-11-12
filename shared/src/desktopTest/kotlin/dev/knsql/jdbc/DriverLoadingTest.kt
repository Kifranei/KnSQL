package dev.knsql.jdbc

import java.sql.Driver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The bundled drivers load side by side: ojdbc 23, the relocated ojdbc 19 and DM. */
class DriverLoadingTest {
    private fun driver(name: String) = Class.forName(name).getDeclaredConstructor().newInstance() as Driver

    @Test
    fun bothOracleDriversLoadSeparately() {
        val modern = driver("oracle.jdbc.OracleDriver")
        val legacy = driver("dev.knsql.ojdbc19.oracle.jdbc.OracleDriver")
        assertEquals(23, modern.majorVersion)
        assertEquals(19, legacy.majorVersion)
        assertNotEquals<Class<*>>(modern.javaClass, legacy.javaClass)
        assertTrue(legacy.acceptsURL("jdbc:oracle:thin:@//db:1521/ORCL"))
    }

    @Test
    fun relocatedDriverReadsItsOwnMessages() {
        val legacy = driver("dev.knsql.ojdbc19.oracle.jdbc.OracleDriver")
        val props = java.util.Properties().apply {
            put("user", "u")
            put("password", "p")
            put("oracle.net.CONNECT_TIMEOUT", "2000")
        }
        val error = runCatching { legacy.connect("jdbc:oracle:thin:@//127.0.0.1:1/X", props) }.exceptionOrNull()
        assertTrue(error is java.sql.SQLException, "expected an SQLException, got $error")
        assertEquals(17002, error.errorCode)
    }

    @Test
    fun damengDriverLoads() {
        val dm = driver("dm.jdbc.driver.DmDriver")
        assertTrue(dm.acceptsURL("jdbc:dm://db:5236"))
    }
}
