package dev.knsql.i18n

import java.util.prefs.Preferences

actual object LanguageSettings {
    private val prefs = Preferences.userRoot().node("dev/knsql")

    actual fun selected(): AppLanguage {
        val tag = prefs.get("language", "")
        return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.System
    }

    actual fun select(language: AppLanguage) {
        prefs.put("language", language.tag)
    }
}
