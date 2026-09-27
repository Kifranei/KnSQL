package dev.knsql.sql

import dev.knsql.model.TableRef

private val stopWords = setOf(
    "where", "order", "group", "having", "fetch", "limit", "offset", "for", "union", "intersect", "minus",
    "except", "window", "qualify", "connect", "start", "model", "sample", "partition", "pivot", "unpivot",
)

/**
 * The table a plain single-table `SELECT … FROM t [alias] …` reads from, or null when the query joins,
 * lists several tables or selects from a subquery. Used to look up column comments for a result grid.
 */
fun singleSourceTable(sql: String): TableRef? {
    val text = stripComments(sql).trim()
    if (!text.startsWith("select", ignoreCase = true)) return null
    val from = topLevelFrom(text) ?: return null
    var index = from
    val parts = mutableListOf<String>()
    while (true) {
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length) return null
        val (part, next) = readIdentifier(text, index) ?: return null
        parts += part
        index = next
        while (index < text.length && text[index].isWhitespace()) index++
        if (index < text.length && text[index] == '.') {
            index++
        } else {
            break
        }
    }
    if (parts.size > 3) return null
    val rest = text.substring(index)
    if (rest.trimStart().startsWith(",")) return null
    if (Regex("\\bjoin\\b", RegexOption.IGNORE_CASE).containsMatchIn(rest)) return null
    val tail = rest.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    // Anything left must be an optional alias followed by a clause keyword.
    val afterAlias = when {
        tail.isEmpty() -> emptyList()
        tail[0].equals("as", true) -> tail.drop(2)
        tail[0].lowercase() in stopWords -> tail
        else -> tail.drop(1)
    }
    if (afterAlias.isNotEmpty() && afterAlias[0].lowercase().trimEnd(';') !in stopWords && afterAlias[0] != ";") return null
    return when (parts.size) {
        1 -> TableRef(null, null, parts[0])
        2 -> TableRef(null, parts[0], parts[1])
        else -> TableRef(parts[0], parts[1], parts[2])
    }
}

private fun readIdentifier(text: String, start: Int): Pair<String, Int>? {
    val open = text[start]
    val close = when (open) {
        '"' -> '"'
        '`' -> '`'
        '[' -> ']'
        else -> null
    }
    if (close != null) {
        val end = text.indexOf(close, start + 1)
        if (end < 0) return null
        return text.substring(start + 1, end) to end + 1
    }
    var end = start
    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '$' || text[end] == '#')) end++
    if (end == start) return null
    return text.substring(start, end) to end
}

/** Index just after the first FROM keyword outside parentheses and string literals. */
private fun topLevelFrom(text: String): Int? {
    var depth = 0
    var index = 0
    while (index < text.length) {
        val c = text[index]
        when {
            c == '\'' -> {
                index++
                while (index < text.length && text[index] != '\'') index++
            }
            c == '"' -> {
                index++
                while (index < text.length && text[index] != '"') index++
            }
            c == '(' -> depth++
            c == ')' -> depth--
            depth == 0 && text.regionMatches(index, "from", 0, 4, ignoreCase = true) &&
                (index == 0 || !text[index - 1].isIdentifierPart()) &&
                (index + 4 >= text.length || !text[index + 4].isIdentifierPart()) -> {
                val next = index + 4
                val first = text.substring(next).trimStart().firstOrNull() ?: return null
                return if (first == '(') null else next
            }
        }
        index++
    }
    return null
}

private fun Char.isIdentifierPart() = isLetterOrDigit() || this == '_' || this == '$' || this == '#'

private fun stripComments(sql: String): String =
    sql.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .lines()
        .joinToString("\n") { line -> line.substringBefore("--") }
