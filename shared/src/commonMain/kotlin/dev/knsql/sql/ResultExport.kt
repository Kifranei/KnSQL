package dev.knsql.sql

import dev.knsql.model.QueryResult

/** CSV per RFC 4180, with a UTF-8 BOM so Excel opens Chinese text correctly. NULL becomes an empty field. */
fun QueryResult.toCsv(): String = buildString {
    append('﻿')
    appendRow(columns) { csvField(it) }
    rows.forEach { row -> appendRow(row) { csvField(it) } }
}

/** Tab-separated text that pastes straight into a spreadsheet. */
fun QueryResult.toTsv(): String = buildString {
    appendRow(columns, separator = '\t', newline = "\n") { tsvField(it) }
    rows.forEach { row -> appendRow(row, separator = '\t', newline = "\n") { tsvField(it) } }
}

private inline fun StringBuilder.appendRow(
    values: List<String?>,
    separator: Char = ',',
    newline: String = "\r\n",
    field: (String?) -> String,
) {
    values.forEachIndexed { index, value ->
        if (index > 0) append(separator)
        append(field(value))
    }
    append(newline)
}

private fun csvField(value: String?): String {
    if (value == null) return ""
    val quote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
        value.startsWith(' ') || value.endsWith(' ')
    return if (quote) "\"" + value.replace("\"", "\"\"") + "\"" else value
}

private fun tsvField(value: String?): String =
    value?.replace('\t', ' ')?.replace("\r\n", " ")?.replace('\n', ' ')?.replace('\r', ' ') ?: ""
