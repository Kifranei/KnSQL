package dev.knsql.sql

import dev.knsql.model.ColumnInfo
import dev.knsql.model.ColumnSpec
import dev.knsql.model.EditableData
import dev.knsql.model.EditableRow
import dev.knsql.model.Engine
import dev.knsql.model.TableRef
import dev.knsql.model.TableStructure
import dev.knsql.model.ValueKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableEditsTest {
    private val id = ColumnInfo("ID", "NUMBER(10)", ValueKind.Number, nullable = false, defaultValue = null, comment = null, primaryKey = true)
    private val name = ColumnInfo("NAME", "VARCHAR2(50 CHAR)", ValueKind.Text, nullable = true, defaultValue = null, comment = "名称")

    private fun structure(engine: Engine, table: TableRef) = TableStructure(engine, table, listOf(id, name), comment = null)

    @Test
    fun oracleUsesRowidAndBindsValues() {
        val data = EditableData(
            structure(Engine.Oracle, TableRef(null, "BSLIS", "T")),
            keyNames = listOf("ROWID"),
            rows = listOf(EditableRow(listOf("AAAx1"), listOf("1", "a")), EditableRow(listOf("AAAx2"), listOf("2", "b"))),
            truncated = false,
        )
        val statements = buildDataChanges(
            data,
            PendingChanges(
                edits = mapOf(0 to mapOf(1 to "O'Neil")),
                deleted = setOf(1),
                inserts = listOf(mapOf(0 to "3", 1 to null)),
            ),
        )
        assertEquals(
            listOf(
                "DELETE FROM \"BSLIS\".\"T\" WHERE ROWID = CHARTOROWID(?)",
                "UPDATE \"BSLIS\".\"T\" SET \"NAME\" = ? WHERE ROWID = CHARTOROWID(?)",
                "INSERT INTO \"BSLIS\".\"T\" (\"ID\", \"NAME\") VALUES (?, ?)",
            ),
            statements.map { it.sql },
        )
        assertEquals("UPDATE \"BSLIS\".\"T\" SET \"NAME\" = 'O''Neil' WHERE ROWID = CHARTOROWID('AAAx1')", statements[1].preview)
        assertEquals("INSERT INTO \"BSLIS\".\"T\" (\"ID\", \"NAME\") VALUES (3, NULL)", statements[2].preview)
        assertEquals(listOf(true, true, false), statements.map { it.expectSingleRow })
    }

    @Test
    fun mysqlUsesPrimaryKey() {
        val data = EditableData(
            structure(Engine.MySql, TableRef("shop", null, "items")),
            keyNames = listOf("ID"),
            rows = listOf(EditableRow(listOf("7"), listOf("7", "x"))),
            truncated = false,
        )
        val update = buildDataChanges(data, PendingChanges(edits = mapOf(0 to mapOf(1 to "y")))).single()
        assertEquals("UPDATE `shop`.`items` SET `NAME` = ? WHERE `ID` = ?", update.sql)
    }

    @Test
    fun oracleAlterOnlyTouchesWhatChanged() {
        val s = structure(Engine.Oracle, TableRef(null, "BSLIS", "T"))
        val ddl = alterColumnDdl(s, name, ColumnSpec("FULL_NAME", "VARCHAR2(50 CHAR)", nullable = false, defaultValue = null, comment = "名称"))
        assertEquals(
            listOf(
                "ALTER TABLE \"BSLIS\".\"T\" RENAME COLUMN \"NAME\" TO \"FULL_NAME\"",
                "ALTER TABLE \"BSLIS\".\"T\" MODIFY (\"FULL_NAME\" NOT NULL)",
            ),
            ddl,
        )
        assertTrue(alterColumnDdl(s, name, ColumnSpec("NAME", "varchar2(50 char)", true, null, "名称")).isEmpty())
    }

    @Test
    fun mysqlRedefinesTheWholeColumn() {
        val s = structure(Engine.MySql, TableRef("shop", null, "items"))
        val old = name.copy(type = "varchar(50)", extra = "on update CURRENT_TIMESTAMP")
        assertEquals(
            listOf("ALTER TABLE `shop`.`items` CHANGE COLUMN `NAME` `NAME` varchar(80) NULL DEFAULT 'x' on update CURRENT_TIMESTAMP COMMENT '名称'"),
            alterColumnDdl(s, old, ColumnSpec("NAME", "varchar(80)", true, "'x'", "名称")),
        )
    }

    @Test
    fun sqlServerDropRemovesDefaultConstraintFirst() {
        val s = structure(Engine.SqlServer, TableRef("db", "dbo", "T"))
        val ddl = dropColumnDdl(s, "NAME")
        assertEquals(2, ddl.size)
        assertTrue(ddl[0].contains("sys.default_constraints"))
        assertEquals("ALTER TABLE [dbo].[T] DROP COLUMN [NAME]", ddl[1])
    }
}
