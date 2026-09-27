package dev.knsql.i18n

import java.util.Locale

actual fun platformLocaleTag(): String = Locale.getDefault().toLanguageTag()
