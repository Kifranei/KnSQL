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

    @Test
    fun postgresAndSqliteDriversLoad() {
        assertTrue(driver("org.postgresql.Driver").acceptsURL(postgresUrl("db", 5432, "app", encrypt = false)))
        assertTrue(driver("org.mariadb.jdbc.Driver").acceptsURL("jdbc:mariadb://db:3306/app"))
        val sqlite = driver("org.sqlite.JDBC")
        val file = java.io.File.createTempFile("knsql", ".db").apply { deleteOnExit() }
        sqlite.connect("jdbc:sqlite:" + file.path, java.util.Properties())!!.use { connection ->
            connection.createStatement().use { it.executeQuery("SELECT sqlite_version()").use { set -> assertTrue(set.next()) } }
        }
    }

    @Test
    fun sqlServerUrlsCarryInstanceAndCertificateChoice() {
        assertEquals(
            "jdbc:sqlserver://db\\SQLEXPRESS;databaseName=app;encrypt=true;trustServerCertificate=false;loginTimeout=8",
            sqlServerUrl("db", 1433, "SQLEXPRESS", "app", encrypt = true, trustServerCertificate = false, microsoft = true),
        )
        assertEquals(
            "jdbc:sqlserver://db:14330;encrypt=false;trustServerCertificate=true;loginTimeout=8",
            sqlServerUrl("db", 14330, "", "", encrypt = false, trustServerCertificate = true, microsoft = true),
        )
        assertEquals(
            "jdbc:jtds:sqlserver://db/app;instance=SQLEXPRESS;loginTimeout=8;socketKeepAlive=true",
            sqlServerUrl("db", 1433, "SQLEXPRESS", "app", encrypt = false, trustServerCertificate = true, microsoft = false),
        )
    }
}
