package dev.knsql.i18n

import android.app.LocaleManager
import android.os.LocaleList
import dev.knsql.data.AndroidNetwork

/**
 * Backed by Android 13's per-app language, so the choice also shows up under
 * System settings › Apps › KnSQL › Language, and changing it there is picked up here.
 */
actual object LanguageSettings {
    private val manager: LocaleManager?
        get() = AndroidNetwork.context?.getSystemService(LocaleManager::class.java)

    actual fun selected(): AppLanguage {
        val locales = manager?.applicationLocales ?: return AppLanguage.System
        if (locales.isEmpty) return AppLanguage.System
        return when (resolveLang(locales[0].toLanguageTag())) {
            Lang.SimplifiedChinese -> AppLanguage.SimplifiedChinese
            Lang.TraditionalChinese -> AppLanguage.TraditionalChinese
            Lang.English -> AppLanguage.English
        }
    }

    actual fun select(language: AppLanguage) {
        val locales = if (language == AppLanguage.System) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(language.tag)
        }
        manager?.applicationLocales = locales
    }
}
