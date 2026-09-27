package dev.knsql.sql

import dev.knsql.model.QueryResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ResultExportTest {
    private val result = QueryResult(
        columns = listOf("ID", "名称", "备注"),
        rows = listOf(
            listOf("1", "张三", "含,逗号"),
            listOf("2", null, "引号\"和\n换行"),
        ),
        updateCount = -1,
        elapsedMs = 0,
        truncated = false,
    )

    @Test
    fun csvQuotesSpecialFieldsAndStartsWithBom() {
        val expected = "﻿ID,名称,备注\r\n1,张三,\"含,逗号\"\r\n2,,\"引号\"\"和\n换行\"\r\n"
        assertEquals(expected, result.toCsv())
    }

    @Test
    fun tsvFlattensTabsAndNewlines() {
        assertEquals("ID\t名称\t备注\n1\t张三\t含,逗号\n2\t\t引号\"和 换行\n", result.toTsv())
    }
}
