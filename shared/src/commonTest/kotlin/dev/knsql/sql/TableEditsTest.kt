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

    @Test
    fun damengUsesPrimaryKeyAndOracleSyntax() {
        val s = structure(Engine.Dameng, TableRef(null, "SYSDBA", "T"))
        val data = EditableData(s, keyNames = listOf("ID"), rows = listOf(EditableRow(listOf("1"), listOf("1", "a"))), truncated = false)
        val statements = buildDataChanges(data, PendingChanges(edits = mapOf(0 to mapOf(1 to "b"))))
        assertEquals("UPDATE \"SYSDBA\".\"T\" SET \"NAME\" = ? WHERE \"ID\" = ?", statements.single().sql)
        assertEquals(
            listOf(
                "ALTER TABLE \"SYSDBA\".\"T\" ADD (\"CODE\" VARCHAR(20) NOT NULL)",
                "COMMENT ON COLUMN \"SYSDBA\".\"T\".\"CODE\" IS '编码'",
            ),
            addColumnDdl(s, ColumnSpec("CODE", "VARCHAR(20)", false, null, "编码")),
        )
    }

    @Test
    fun previewsWorkOnOldOracleAndDameng() {
        assertEquals("SELECT * FROM \"HR\".\"EMP\" WHERE ROWNUM <= 200", dev.knsql.model.previewSql(Engine.Oracle, null, "HR", "EMP"))
        assertEquals("SELECT * FROM \"SYSDBA\".\"EMP\" LIMIT 200", dev.knsql.model.previewSql(Engine.Dameng, null, "SYSDBA", "EMP"))
    }

    @Test
    fun mariaDbSharesMySqlSyntax() {
        val s = structure(Engine.MariaDb, TableRef("shop", null, "items"))
        val data = EditableData(s, keyNames = listOf("ID"), rows = listOf(EditableRow(listOf("7"), listOf("7", "x"))), truncated = false)
        val statements = buildDataChanges(data, PendingChanges(edits = mapOf(0 to mapOf(1 to "it's")), inserts = listOf(emptyMap())))
        assertEquals("UPDATE `shop`.`items` SET `NAME` = 'it''s' WHERE `ID` = 7", statements[0].preview)
        assertEquals("INSERT INTO `shop`.`items` () VALUES ()", statements[1].sql)
        assertEquals("SELECT * FROM `shop`.`items` LIMIT 200", dev.knsql.model.previewSql(Engine.MariaDb, "shop", null, "items"))
    }

    @Test
    fun postgresUsesSchemaAndStandardDdl() {
        val s = structure(Engine.PostgreSql, TableRef(null, "public", "Items"))
        val data = EditableData(s, keyNames = listOf("ID"), rows = listOf(EditableRow(listOf("1"), listOf("1", "a"))), truncated = false)
        val statements = buildDataChanges(data, PendingChanges(edits = mapOf(0 to mapOf(1 to "b")), inserts = listOf(emptyMap())))
        assertEquals("UPDATE \"public\".\"Items\" SET \"NAME\" = ? WHERE \"ID\" = ?", statements[0].sql)
        assertEquals("INSERT INTO \"public\".\"Items\" DEFAULT VALUES", statements[1].sql)
        assertEquals(
            listOf(
                "ALTER TABLE \"public\".\"Items\" RENAME COLUMN \"NAME\" TO \"TITLE\"",
                "ALTER TABLE \"public\".\"Items\" ALTER COLUMN \"TITLE\" TYPE varchar(80), " +
                    "ALTER COLUMN \"TITLE\" SET DEFAULT 'x', ALTER COLUMN \"TITLE\" SET NOT NULL",
                "COMMENT ON COLUMN \"public\".\"Items\".\"TITLE\" IS NULL",
            ),
            alterColumnDdl(s, name.copy(type = "varchar(50)"), ColumnSpec("TITLE", "varchar(80)", false, "'x'", null)),
        )
        assertEquals(
            listOf(
                "ALTER TABLE \"public\".\"Items\" ADD COLUMN \"CODE\" text NOT NULL",
                "COMMENT ON COLUMN \"public\".\"Items\".\"CODE\" IS '编码'",
            ),
            addColumnDdl(s, ColumnSpec("CODE", "text", false, null, "编码")),
        )
        assertEquals(listOf("COMMENT ON TABLE \"public\".\"Items\" IS 'x'"), tableCommentDdl(s, "x"))
        assertEquals("SELECT * FROM \"public\".\"Items\" LIMIT 200", dev.knsql.model.previewSql(Engine.PostgreSql, null, "public", "Items"))
        val flag = BindValueFor.boolean("1")
        assertEquals("TRUE", previewLiteral(Engine.PostgreSql, flag))
    }

    @Test
    fun sqliteUsesRowidWithoutPrimaryKey() {
        val plain = TableStructure(Engine.Sqlite, TableRef(null, null, "notes"), listOf(name), comment = null)
        val data = EditableData(plain, keyNames = listOf("rowid"), rows = listOf(EditableRow(listOf("3"), listOf("a"))), truncated = false)
        val statements = buildDataChanges(data, PendingChanges(edits = mapOf(0 to mapOf(0 to "b")), deleted = emptySet()))
        assertEquals("UPDATE \"notes\" SET \"NAME\" = ? WHERE rowid = CAST(? AS INTEGER)", statements.single().sql)
        assertEquals("UPDATE \"notes\" SET \"NAME\" = 'b' WHERE rowid = CAST('3' AS INTEGER)", statements.single().preview)
        assertEquals("SELECT * FROM \"notes\" LIMIT 200", dev.knsql.model.previewSql(Engine.Sqlite, null, null, "notes"))
    }

    @Test
    fun sqliteOnlyRenamesColumns() {
        val s = structure(Engine.Sqlite, TableRef(null, null, "t"))
        assertEquals(
            listOf("ALTER TABLE \"t\" RENAME COLUMN \"NAME\" TO \"TITLE\""),
            alterColumnDdl(s, name, ColumnSpec("TITLE", name.type, true, null, "名称")),
        )
        assertTrue(runCatching { alterColumnDdl(s, name, ColumnSpec("NAME", "TEXT", true, null, "名称")) }.isFailure)
        assertEquals(listOf("ALTER TABLE \"t\" DROP COLUMN \"NAME\""), dropColumnDdl(s, "NAME"))
        assertEquals(
            listOf("ALTER TABLE \"t\" ADD COLUMN \"N\" INTEGER DEFAULT 0 NOT NULL"),
            addColumnDdl(s, ColumnSpec("N", "INTEGER", false, "0", null)),
        )
    }
}

private object BindValueFor {
    fun boolean(text: String) = dev.knsql.model.BindValue(text, ValueKind.Boolean)
}
