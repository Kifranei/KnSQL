package dev.knsql.data

import dev.knsql.model.AppSettings
import dev.knsql.model.HistoryEntry
import dev.knsql.model.SavedConnection

data class StoredState(
    val connections: List<SavedConnection>,
    val settings: AppSettings,
    val history: List<HistoryEntry>,
)

interface AppStore {
    fun read(): StoredState
    fun write(state: StoredState)
    fun encrypt(plain: String): String
    fun decrypt(cipher: String): String
}

object AppRuntime {
    lateinit var store: AppStore

    /** Lives as long as the process, so open sessions survive the activity being recreated. */
    val workbench: Workbench by lazy { Workbench(store, databaseClient()) }

    val workbenchOrNull: Workbench?
        get() = if (::store.isInitialized) workbench else null

    fun install(created: AppStore) {
        if (!::store.isInitialized) store = created
    }
}
