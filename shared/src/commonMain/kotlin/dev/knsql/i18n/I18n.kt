package dev.knsql.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The languages the UI is written in. Simplified Chinese is the source language of every string. */
enum class Lang { SimplifiedChinese, TraditionalChinese, English }

/** What the user picked in settings. [System] follows the device (or, on Android 13+, the per-app language). */
enum class AppLanguage(val tag: String) {
    System(""),
    SimplifiedChinese("zh-CN"),
    TraditionalChinese("zh-TW"),
    English("en"),
}

/**
 * One translatable string. [hans] is the exact text passed to [tr] and doubles as the lookup key.
 * [context] tells apart the same Chinese text that needs different wording elsewhere, see [trc].
 */
data class Text(val hans: String, val hant: String, val en: String, val context: String = "")

object I18n {
    /** Compose state, so every composable that called [tr] recomposes when the language changes. */
    var lang by mutableStateOf(Lang.SimplifiedChinese)
        private set

    private val table: Map<String, Text> by lazy { allTexts.associateBy { key(it.context, it.hans) } }

    internal fun key(context: String, source: String) = if (context.isEmpty()) source else "$context|$source"

    fun has(source: String, context: String = ""): Boolean = key(context, source) in table

    /** Re-reads the effective language from the user's choice and the platform locale. */
    fun refresh() {
        lang = when (LanguageSettings.selected()) {
            AppLanguage.System -> resolveLang(platformLocaleTag())
            AppLanguage.SimplifiedChinese -> Lang.SimplifiedChinese
            AppLanguage.TraditionalChinese -> Lang.TraditionalChinese
            AppLanguage.English -> Lang.English
        }
    }

    fun select(language: AppLanguage) {
        LanguageSettings.select(language)
        refresh()
    }

    internal fun lookup(source: String, lang: Lang, context: String = ""): String {
        val text = table[key(context, source)] ?: table[source] ?: return source
        return when (lang) {
            Lang.SimplifiedChinese -> text.hans
            Lang.TraditionalChinese -> text.hant
            Lang.English -> text.en
        }
    }
}

/**
 * Translates [source], written in Simplified Chinese, into the current language. `{0}`, `{1}` … are
 * replaced by [args]. Unknown strings fall back to the source text.
 */
fun tr(source: String, vararg args: Any?): String = format(I18n.lookup(source, I18n.lang), args)

/** Like [tr], for text whose translation depends on where it is used, e.g. "表" as a heading ("Tables"). */
fun trc(context: String, source: String, vararg args: Any?): String =
    format(I18n.lookup(source, I18n.lang, context), args)

private fun format(pattern: String, args: Array<out Any?>): String {
    var text = pattern
    args.forEachIndexed { index, value -> text = text.replace("{$index}", value.toString()) }
    return text
}

fun resolveLang(tag: String): Lang {
    val lower = tag.lowercase().replace('_', '-')
    if (!lower.startsWith("zh")) return Lang.English
    val parts = lower.split('-')
    return when {
        "hans" in parts -> Lang.SimplifiedChinese
        "hant" in parts || parts.any { it == "tw" || it == "hk" || it == "mo" } -> Lang.TraditionalChinese
        else -> Lang.SimplifiedChinese
    }
}

/** BCP 47 tag of the locale the platform currently runs in. */
expect fun platformLocaleTag(): String

/** Where the chosen language is stored: Android's per-app language on 13+, preferences on desktop. */
expect object LanguageSettings {
    fun selected(): AppLanguage
    fun select(language: AppLanguage)
}
