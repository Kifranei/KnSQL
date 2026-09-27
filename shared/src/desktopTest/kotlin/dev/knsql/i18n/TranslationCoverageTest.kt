package dev.knsql.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** Scans the shared sources so a string can't be wrapped in tr() without a translation, or left unwrapped. */
class TranslationCoverageTest {
    private val sources: List<File> = File("src").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filterNot { file -> file.invariantSeparatorsPath.let { "Test/" in it || "/i18n/" in it } }
        .toList()

    private val literal = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
    private val trCall = Regex("\\btr\\(\\s*\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
    private val trcCall = Regex("\\btrc\\(\\s*\"([^\"]*)\"\\s*,\\s*\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
    private val trcPrefix = Regex("\\btrc\\(\\s*\"[^\"]*\"\\s*,\\s*$")
    private val han = Regex("[\\u4e00-\\u9fff]")

    private fun unescape(text: String) = text.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")

    @Test
    fun sourcesFound() {
        assertTrue(sources.size > 10, "expected to scan the shared sources, found ${sources.size} files")
    }

    @Test
    fun everyTrCallHasATranslation() {
        val missing = sources.flatMap { file ->
            val text = file.readText()
            val plain = trCall.findAll(text).map { unescape(it.groupValues[1]) }
                .filterNot { I18n.has(it) }
                .map { "${file.name}: $it" }
            val scoped = trcCall.findAll(text)
                .filterNot { I18n.has(unescape(it.groupValues[2]), it.groupValues[1]) }
                .map { "${file.name}: [${it.groupValues[1]}] ${it.groupValues[2]}" }
            (plain + scoped).toList()
        }
        if (missing.isNotEmpty()) fail("tr() strings without a translation:\n" + missing.joinToString("\n"))
    }

    @Test
    fun noConflictingDuplicates() {
        val conflicts = allTexts.groupBy { I18n.key(it.context, it.hans) }
            .filterValues { entries -> entries.distinct().size > 1 }
            .keys
        if (conflicts.isNotEmpty()) fail("same source text translated differently:\n" + conflicts.joinToString("\n"))
    }

    @Test
    fun translationsKeepPlaceholders() {
        val placeholder = Regex("\\{\\d+}")
        val broken = allTexts.filter { text ->
            val expected = placeholder.findAll(text.hans).map { it.value }.toSet()
            placeholder.findAll(text.hant).map { it.value }.toSet() != expected ||
                placeholder.findAll(text.en).map { it.value }.toSet() != expected
        }
        if (broken.isNotEmpty()) fail("placeholders differ between languages:\n" + broken.joinToString("\n") { it.hans })
    }

    @Test
    fun noUntranslatedChineseLiterals() {
        val stray = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if ("i18n-ignore" in line) return@mapIndexedNotNull null
                val code = line.substringBefore("//").trimStart()
                if (code.startsWith("*") || code.startsWith("/*")) return@mapIndexedNotNull null
                val loose = literal.findAll(code)
                    .filter { han.containsMatchIn(it.value) }
                    .filterNot { match ->
                        val before = code.substring(0, match.range.first)
                        before.trimEnd().endsWith("tr(") || trcPrefix.containsMatchIn(before)
                    }
                    .filterNot { I18n.has(unescape(it.groupValues[1])) }
                    .toList()
                if (loose.isEmpty()) null else "${file.name}:${index + 1}: ${line.trim()}"
            }
        }
        if (stray.isNotEmpty()) fail("Chinese literals not passed through tr():\n" + stray.joinToString("\n"))
    }
}
