package dev.knsql.sql

import dev.knsql.i18n.tr
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

private const val MAX_SCRIPT_BYTES = 20 * 1024 * 1024

fun decodeSqlBytes(bytes: ByteArray): String {
    if (bytes.size > MAX_SCRIPT_BYTES) error(tr("文件超过 20MB"))
    if (bytes.any { it == 0.toByte() }) error(tr("这不是文本 SQL 文件"))
    val utf8 = Charset.forName("UTF-8").newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        utf8.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: CharacterCodingException) {
        bytes.toString(Charset.forName("GBK")).removePrefix("\uFEFF")
    }
}
