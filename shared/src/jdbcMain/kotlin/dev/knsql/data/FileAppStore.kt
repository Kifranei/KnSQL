package dev.knsql.data

import dev.knsql.model.AppSettings
import dev.knsql.model.HistoryEntry
import dev.knsql.model.SavedConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
private data class DiskState(
    val connections: List<SavedConnection> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val history: List<HistoryEntry> = emptyList(),
)

class FileAppStore(private val root: File) : AppStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val stateFile = File(root, "state.json")
    private val key: ByteArray = loadKey(File(root, "device.key"))

    override fun read(): StoredState {
        if (!stateFile.exists()) return StoredState(emptyList(), AppSettings(), emptyList())
        val disk = json.decodeFromString(DiskState.serializer(), stateFile.readText())
        return StoredState(disk.connections, disk.settings, disk.history)
    }

    override fun write(state: StoredState) {
        root.mkdirs()
        val disk = DiskState(state.connections, state.settings, state.history)
        val tmp = File(root, "state.json.tmp")
        tmp.writeText(json.encodeToString(DiskState.serializer(), disk))
        if (stateFile.exists() && !stateFile.delete()) {
            stateFile.writeText(tmp.readText())
            tmp.delete()
        } else if (!tmp.renameTo(stateFile)) {
            stateFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    override fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    override fun decrypt(cipher: String): String {
        if (cipher.isEmpty()) return ""
        val raw = Base64.getDecoder().decode(cipher)
        val iv = raw.copyOfRange(0, 12)
        val body = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(body).toString(Charsets.UTF_8)
    }

    private fun loadKey(file: File): ByteArray {
        if (file.exists() && file.length() == 32L) return file.readBytes()
        root.mkdirs()
        val created = ByteArray(32).also { SecureRandom().nextBytes(it) }
        file.writeBytes(created)
        return created
    }
}

fun createFileStore(root: File): AppStore = FileAppStore(root)
