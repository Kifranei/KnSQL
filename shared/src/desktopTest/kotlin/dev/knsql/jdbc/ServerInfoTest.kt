package dev.knsql.jdbc

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerInfoTest {
    @Test
    fun oracleCollapsesToOneLine() {
        val version = "Oracle Database 19c Enterprise Edition Release 19.0.0.0.0 - Production\nVersion 19.22.0.0.0"
        assertEquals("Oracle Database 19c Enterprise Edition 19.22.0.0.0", compactServerInfo("Oracle", version))
    }

    @Test
    fun plainVersionsKeepTheProductName() {
        assertEquals("MySQL 8.0.36", compactServerInfo("MySQL", "8.0.36"))
        assertEquals("Microsoft SQL Server 16.00.1000", compactServerInfo("Microsoft SQL Server", "16.00.1000"))
    }
}
