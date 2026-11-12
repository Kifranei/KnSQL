package dev.knsql.sql

private val writeLead = Regex(
    """(?is)^\s*(insert|update|delete|merge|drop|alter|truncate|create|grant|revoke|comment|call|exec|execute)\b""",
)

private val delimiterCommand = Regex("""(?i)^DELIMITER\s+(\S+)\s*$""")

private val routineLead = Regex(
    """(?is)^CREATE\s+(OR\s+REPLACE\s+)?(EDITIONABLE\s+|NONEDITIONABLE\s+)?(PROCEDURE|FUNCTION|TRIGGER|PACKAGE(\s+BODY)?|TYPE(\s+BODY)?|LIBRARY)\b""",
)

data class ScriptStatement(
    val text: String,
    val start: Int,
    val endExclusive: Int,
)

fun splitSqlScript(script: String): List<String> = scriptStatements(script).map { it.text }

fun statementAt(script: String, cursor: Int): String? {
    val statements = scriptStatements(script)
    if (statements.isEmpty()) return null
    val safe = cursor.coerceIn(0, script.length)
    return statements.firstOrNull { safe < it.endExclusive }?.text ?: statements.last().text
}

/**
 * Splits a script into statements.
 * Semicolons separate ordinary statements. A line that is only `/` or `GO` ends the current batch,
 * which keeps semicolons inside Oracle PL/SQL and SQL Server batches. `DELIMITER` changes the separator.
 */
fun scriptStatements(script: String): List<ScriptStatement> {
    val statements = mutableListOf<ScriptStatement>()
    val buffer = StringBuilder()
    var delimiter = ";"
    var quote: Char? = null
    var blockComment = false
    var inRoutine = false
    var lineStart = true
    var statementStart = 0
    var capturing = false

    fun mark(index: Int) {
        if (!capturing && buffer.isNotEmpty()) {
            statementStart = index
            capturing = true
        }
    }

    fun flush(end: Int) {
        val text = buffer.toString().trim()
        buffer.clear()
        inRoutine = false
        if (text.isNotEmpty()) statements += ScriptStatement(text, statementStart, end)
        capturing = false
    }

    fun considerRoutine() {
        if (!inRoutine && isRoutineBlock(buffer.toString())) inRoutine = true
    }

    var index = 0
    while (index < script.length) {
        val char = script[index]
        val next = script.getOrNull(index + 1) ?: '\u0000'

        if (blockComment) {
            if (!capturing) statementStart = index
            capturing = true
            buffer.append(char)
            if (char == '*' && next == '/') {
                buffer.append(next)
                index += 2
                blockComment = false
            } else {
                if (char == '\n') lineStart = true
                index++
            }
            continue
        }

        if (quote != null) {
            buffer.append(char)
            if (char == quote) {
                if (next == quote) {
                    buffer.append(next)
                    index += 2
                    continue
                }
                quote = null
            }
            if (char == '\n') lineStart = true
            index++
            continue
        }

        if (lineStart) {
            val lineEnd = script.indexOf('\n', index).let { if (it < 0) script.length else it }
            val raw = script.substring(index, lineEnd).trimEnd('\r').trim()
            val delimiterLine = delimiterCommand.matchEntire(raw)
            val command = when {
                raw == "/" || raw.equals("GO", ignoreCase = true) -> "batch"
                raw.startsWith("#") -> "hash"
                delimiterLine != null -> "delimiter"
                else -> null
            }
            if (command != null) {
                if (command == "batch" || command == "delimiter") flush(index)
                if (delimiterLine != null) delimiter = delimiterLine.groupValues[1]
                index = if (lineEnd < script.length) lineEnd + 1 else lineEnd
                lineStart = true
                continue
            }
        }

        if (char == '-' && next == '-') {
            val lineEnd = script.indexOf('\n', index).let { if (it < 0) script.length else it }
            index = if (lineEnd < script.length) lineEnd + 1 else lineEnd
            lineStart = true
            continue
        }
        if (char == '#') {
            val lineEnd = script.indexOf('\n', index).let { if (it < 0) script.length else it }
            index = if (lineEnd < script.length) lineEnd + 1 else lineEnd
            lineStart = true
            continue
        }
        if (char == '/' && next == '*') {
            if (!capturing) statementStart = index
            capturing = true
            blockComment = true
            buffer.append("/*")
            index += 2
            lineStart = false
            continue
        }
        if (char == '\'' || char == '"') {
            if (!capturing) statementStart = index
            capturing = true
            quote = char
            buffer.append(char)
            lineStart = false
            index++
            considerRoutine()
            continue
        }
        // PostgreSQL dollar quoting: $$ ... $$ or $tag$ ... $tag$ (DO blocks, function bodies).
        if (char == '$' && delimiter == ";") {
            val previous = script.getOrNull(index - 1)
            val tag = dollarTag.matchAt(script, index)?.value
            if (tag != null && (previous == null || !(previous.isLetterOrDigit() || previous == '_' || previous == '$'))) {
                val close = script.indexOf(tag, index + tag.length)
                val end = if (close < 0) script.length else close + tag.length
                if (!capturing) statementStart = index
                capturing = true
                val body = script.substring(index, end)
                buffer.append(body)
                lineStart = false
                index = end
                considerRoutine()
                continue
            }
        }
        val routineHoldsSemicolon = inRoutine && delimiter == ";"
        if (!routineHoldsSemicolon && script.startsWith(delimiter, index)) {
            val at = if (capturing) statementStart else index
            if (!capturing) statementStart = at
            flush(index)
            index += delimiter.length
            if (script.getOrNull(index) == '\r') index++
            lineStart = script.getOrNull(index - 1) == '\n' || index >= script.length
            continue
        }

        if (!char.isWhitespace() && !capturing) {
            statementStart = index
            capturing = true
        }
        buffer.append(char)
        lineStart = char == '\n'
        index++
        if (!char.isWhitespace()) considerRoutine()
    }
    flush(script.length)
    return statements
}

fun isWriteStatement(sql: String): Boolean = writeLead.containsMatchIn(sql)

fun scriptNeedsConfirmation(script: String): Boolean = splitSqlScript(script).any(::isWriteStatement)

private val dollarTag = Regex("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$")

private fun isRoutineBlock(sql: String): Boolean {
    val source = sql.trimStart()
    if (source.isEmpty()) return false
    val upper = source.uppercase()
    if (upper.startsWith("DECLARE")) return true
    if (upper.startsWith("BEGIN")) {
        return !upper.startsWith("BEGIN TRAN") &&
            !upper.startsWith("BEGIN TRANSACTION") &&
            !upper.startsWith("BEGIN TRY") &&
            !upper.startsWith("BEGIN CATCH") &&
            !upper.startsWith("BEGIN DISTRIBUTED")
    }
    return routineLead.containsMatchIn(upper)
}
