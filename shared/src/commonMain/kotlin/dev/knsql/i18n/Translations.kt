package dev.knsql.i18n

import dev.knsql.i18n.texts.coreTexts
import dev.knsql.i18n.texts.dataTexts
import dev.knsql.i18n.texts.ddlTexts
import dev.knsql.i18n.texts.dialogTexts
import dev.knsql.i18n.texts.editTexts
import dev.knsql.i18n.texts.workbenchTexts

internal val allTexts: List<Text>
    get() = coreTexts + dataTexts + ddlTexts + dialogTexts + workbenchTexts + editTexts
