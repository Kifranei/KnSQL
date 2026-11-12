package dev.knsql.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlScriptTest {
    @Test
    fun splitsOnSemicolonOutsideQuotes() {
        val script = "SELECT ';';\nUPDATE t SET a = 'x;y'"
        assertEquals(listOf("SELECT ';'", "UPDATE t SET a = 'x;y'"), splitSqlScript(script))
    }

    @Test
    fun stripsLineComments() {
        assertEquals(listOf("SELECT 1"), splitSqlScript("-- note\nSELECT 1"))
    }

    @Test
    fun detectsWrites() {
        assertTrue(scriptNeedsConfirmation("delete from t where id = 1"))
        assertFalse(scriptNeedsConfirmation("SELECT * FROM t"))
        assertFalse(isWriteStatement("SELECT update_time FROM t"))
    }

    @Test
    fun keepsOracleBlockUntilSlash() {
        val script = """
            BEGIN
              NULL;
              UPDATE t SET a = 1;
            END;
            /
            SELECT 1 FROM DUAL;
        """.trimIndent()
        assertEquals(
            listOf("BEGIN\n  NULL;\n  UPDATE t SET a = 1;\nEND;", "SELECT 1 FROM DUAL"),
            splitSqlScript(script),
        )
    }

    @Test
    fun splitsSqlServerBatchesOnGo() {
        val script = "SELECT 1;\nGO\nUPDATE t SET a = 1;\nGO"
        assertEquals(listOf("SELECT 1", "UPDATE t SET a = 1"), splitSqlScript(script))
    }

    @Test
    fun honorsMysqlDelimiter() {
        val script = """
            DELIMITER ${'$'}${'$'}
            CREATE PROCEDURE p()
            BEGIN
              SELECT 1;
            END${'$'}${'$'}
            DELIMITER ;
            SELECT 2;
        """.trimIndent()
        assertEquals(
            listOf("CREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\nEND", "SELECT 2"),
            splitSqlScript(script),
        )
    }

    @Test
    fun findsStatementUnderCursor() {
        val script = "SELECT 1;\nSELECT 2;"
        assertEquals("SELECT 2", statementAt(script, script.indexOf('2')))
    }

    @Test
    fun keepsPostgresDollarQuotedBodiesTogether() {
        val script = "DO $$ BEGIN PERFORM 1; END $$;\nSELECT \$tag\$a;b\$tag\$;\nSELECT V\$SESSION FROM t"
        assertEquals(
            listOf("DO $$ BEGIN PERFORM 1; END $$", "SELECT \$tag\$a;b\$tag\$", "SELECT V\$SESSION FROM t"),
            splitSqlScript(script),
        )
    }
}
