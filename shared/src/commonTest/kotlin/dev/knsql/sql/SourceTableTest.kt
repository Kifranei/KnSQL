package dev.knsql.sql

import dev.knsql.model.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceTableTest {
    @Test
    fun findsQuotedOracleTable() {
        assertEquals(
            TableRef(null, "BSLIS", "MH_YLJG"),
            singleSourceTable("SELECT * FROM \"BSLIS\".\"MH_YLJG\" FETCH FIRST 200 ROWS ONLY"),
        )
    }

    @Test
    fun findsTableWithAliasAndWhere() {
        assertEquals(TableRef(null, null, "orders"), singleSourceTable("select o.id from orders o where o.id > 1"))
        assertEquals(TableRef("db", "dbo", "Users"), singleSourceTable("SELECT TOP 200 * FROM [db].[dbo].[Users] AS u"))
        assertEquals(TableRef(null, "shop", "items"), singleSourceTable("SELECT * FROM `shop`.`items` LIMIT 200;"))
    }

    @Test
    fun ignoresFromInsideSubqueriesAndStrings() {
        assertEquals(
            TableRef(null, null, "t"),
            singleSourceTable("SELECT (SELECT max(x) FROM other) AS m, 'from x' FROM t"),
        )
    }

    @Test
    fun rejectsJoinsListsAndSubqueries() {
        assertNull(singleSourceTable("SELECT * FROM a JOIN b ON a.id = b.id"))
        assertNull(singleSourceTable("SELECT * FROM a, b"))
        assertNull(singleSourceTable("SELECT * FROM (SELECT 1 FROM dual) x"))
        assertNull(singleSourceTable("UPDATE t SET a = 1"))
    }
}
