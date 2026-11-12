package dev.knsql.data

import dev.knsql.i18n.tr
import dev.knsql.model.AppSettings
import dev.knsql.model.ConnectionDraft
import dev.knsql.model.Engine
import dev.knsql.model.HistoryEntry
import dev.knsql.model.MetaNode
import dev.knsql.model.NodeKind
import dev.knsql.model.QueryResult
import dev.knsql.model.SavedConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.knsql.model.previewSql
import dev.knsql.model.toDraft
import dev.knsql.sql.scriptNeedsConfirmation
import dev.knsql.sql.splitSqlScript
import dev.knsql.sql.statementAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.TimeSource

data class TreeNode(
    val id: String,
    val sessionHandle: String,
    val savedId: String,
    val engineName: String,
    val meta: MetaNode,
    val depth: Int,
    val expanded: Boolean = false,
    val loading: Boolean = false,
)

data class LiveSession(
    val handle: String,
    val savedId: String,
    val name: String,
    val serverInfo: String,
    val rootId: String,
)

data class DdlView(
    val title: String,
    val subtitle: String,
    val fileName: String,
    val whole: Boolean,
    val text: String = "",
    val loading: Boolean = true,
    val progress: String = tr("正在读取定义"),
    val error: String? = null,
)

class Workbench(
    private val store: AppStore,
    internal val client: DatabaseClient,
) {
    // Anything that escapes a job is reported instead of taking the whole process down.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            logFailure("uncaught", error)
            _messages.tryEmit(error.describeFailure())
        },
    )
    private val gate = Mutex()
    private var running: Job? = null
    private var persist = StoredState(emptyList(), AppSettings(), emptyList())

    var connections by mutableStateOf<List<SavedConnection>>(emptyList())
        private set
    var settings by mutableStateOf(AppSettings())
        private set
    var history by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set
    var sessions by mutableStateOf<List<LiveSession>>(emptyList())
        private set
    var activeHandle by mutableStateOf<String?>(null)
        private set
    var nodes by mutableStateOf<Map<String, TreeNode>>(emptyMap())
        private set
    var children by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    var editor by mutableStateOf("SELECT 1")
    var editorCursor by mutableIntStateOf(0)
    var scriptName by mutableStateOf<String?>(null)
    var scriptLog by mutableStateOf<List<String>>(emptyList())
        private set
    var results by mutableStateOf<List<QueryResult>>(emptyList())
        private set
    var resultIndex by mutableIntStateOf(0)
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf(tr("就绪"))
        private set
    var loaded by mutableStateOf(false)
        private set

    var dataEditor by mutableStateOf<DataEditorState?>(null)
    var structureEditor by mutableStateOf<StructureEditorState?>(null)

    var ddl by mutableStateOf<DdlView?>(null)
        private set
    private var ddlJob: Job? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages

    fun visibleNodes(): List<TreeNode> {
        val output = mutableListOf<TreeNode>()
        fun walk(id: String) {
            val node = nodes[id] ?: return
            output += node
            if (node.expanded) children[id].orEmpty().forEach(::walk)
        }
        // Only the session in front: each open database keeps its own tree.
        sessions.find { it.handle == activeHandle }?.let { walk(it.rootId) }
        return output
    }

    @kotlin.concurrent.Volatile
    private var lastUsed = emptyMap<String, TimeSource.Monotonic.ValueTimeMark>()
    private var heartbeat: Job? = null

    fun load() {
        if (loaded) return
        startHeartbeat()
        scope.launch {
            val state = withContext(Dispatchers.IO) { store.read() }
            persist = state
            connections = state.connections
            settings = state.settings
            history = state.history
            loaded = true
        }
    }

    fun updateSettings(next: AppSettings) {
        settings = next
        persist = persist.copy(settings = next)
        persistAsync()
    }

    fun saveConnection(draft: ConnectionDraft, previousCipher: String): Boolean {
        val port = draft.port.toIntOrNull()
        if (draft.name.isBlank() || draft.host.isBlank() || port == null) {
            _messages.tryEmit(tr("请填写名称、主机和有效端口"))
            return false
        }
        if (draft.engine == dev.knsql.model.Engine.Oracle && draft.database.isBlank()) {
            _messages.tryEmit(tr("Oracle 需要服务名或 SID"))
            return false
        }
        val password = when {
            draft.password.isNotEmpty() -> store.encrypt(draft.password)
            draft.keepStoredPassword -> previousCipher
            else -> ""
        }
        val saved = SavedConnection(
            id = draft.id ?: newId(),
            name = draft.name.trim(),
            engine = draft.engine,
            host = draft.host.trim(),
            port = port,
            database = draft.database.trim(),
            oracleSid = draft.oracleSid,
            oracleDriver = draft.oracleDriver,
            username = draft.username.trim(),
            passwordCipher = password,
            sysdba = draft.sysdba,
            encrypt = draft.encrypt,
            readOnly = draft.readOnly,
        )
        val next = connections.filterNot { it.id == saved.id } + saved
        connections = next.sortedBy { it.name.lowercase() }
        persist = persist.copy(connections = connections)
        persistAsync()
        _messages.tryEmit(tr("已保存 {0}", saved.name))
        return true
    }

    fun deleteConnection(id: String) {
        connections = connections.filterNot { it.id == id }
        persist = persist.copy(connections = connections)
        persistAsync()
        sessions.filter { it.savedId == id }.forEach { closeSession(it.handle) }
    }

    fun test(draft: ConnectionDraft, onResult: (String) -> Unit) {
        scope.launch {
            val message = runCatching { client.test(draft) }
                .fold(
                    onSuccess = { it },
                    onFailure = {
                        logFailure("test", it)
                        it.describeFailure()
                    },
                )
            onResult(message)
        }
    }

    fun connect(saved: SavedConnection) {
        sessions.find { it.savedId == saved.id }?.let { existing ->
            focus(existing.handle)
            _messages.tryEmit(tr("已切换到 {0}", existing.name))
            return
        }
        if (connecting.contains(saved.id)) return
        connecting = connecting + saved.id
        scope.launch {
            busy = true
            status = tr("正在连接 {0}", saved.name)
            val draft = saved.toUnlockedDraft()
            val opened = runCatching { client.open(draft) }
            opened.onSuccess { session ->
                val root = TreeNode(
                    id = newId(),
                    sessionHandle = session.handle,
                    savedId = saved.id,
                    engineName = saved.engine.label,
                    meta = MetaNode(label = saved.name, kind = NodeKind.Session, expandable = true),
                    depth = 0,
                    expanded = true,
                )
                val childNodes = session.children.map { child ->
                    TreeNode(
                        id = newId(),
                        sessionHandle = session.handle,
                        savedId = saved.id,
                        engineName = saved.engine.label,
                        meta = child,
                        depth = 1,
                    )
                }
                nodes = nodes + (root.id to root) + childNodes.associateBy { it.id }
                children = children + (root.id to childNodes.map { it.id })
                val live = LiveSession(session.handle, saved.id, saved.name, session.serverInfo, root.id)
                sessions = sessions + live
                touch(session.handle)
                activeHandle = session.handle
                if (editor.trim() in defaultQueries) editor = defaultQuery(saved.engine)
                status = session.serverInfo
                _messages.tryEmit(tr("已连接 {0}", saved.name))
            }.onFailure {
                logFailure("connect", it)
                val detail = it.describeFailure().lineSequence().firstOrNull { line -> line.isNotBlank() } ?: tr("连接失败")
                status = detail
                _messages.tryEmit(it.describeFailure())
            }
            connecting = connecting - saved.id
            busy = false
        }
    }

    private var connecting = emptySet<String>()

    private fun touch(handle: String) {
        lastUsed = lastUsed + (handle to TimeSource.Monotonic.markNow())
    }

    private fun idleSeconds(handle: String): Long =
        lastUsed[handle]?.elapsedNow()?.inWholeSeconds ?: Long.MAX_VALUE

    private fun startHeartbeat() {
        if (heartbeat?.isActive == true) return
        heartbeat = scope.launch {
            while (true) {
                delay(HEARTBEAT_SECONDS * 1000)
                if (!settings.keepAlive) continue
                sessions.forEach { session ->
                    if (idleSeconds(session.handle) < HEARTBEAT_SECONDS - 5) return@forEach
                    if (!gate.tryLock()) return@forEach
                    try {
                        runCatching { client.ping(session.handle) }
                            .onSuccess { touch(session.handle) }
                            .onFailure { error ->
                                logFailure("heartbeat", error)
                                runCatching { reconnect(session) }
                                    .onFailure { status = tr("{0} 已断开，下次执行时会重连", session.name) }
                            }
                    } finally {
                        gate.unlock()
                    }
                }
            }
        }
    }

    /** Call while holding [gate]. Checks a session that sat idle and reconnects it if the link is gone. */
    private suspend fun ensureAlive(handle: String) {
        if (idleSeconds(handle) < RECHECK_AFTER_SECONDS) return
        val session = sessions.find { it.handle == handle } ?: return
        val alive = runCatching { client.ping(handle) }.isSuccess
        if (!alive) {
            status = tr("{0} 连接已断开，正在重连", session.name)
            reconnect(session)
        }
        touch(handle)
    }

    private suspend fun reconnect(session: LiveSession) {
        val saved = connections.find { it.id == session.savedId } ?: error(tr("找不到 {0} 的连接配置", session.name))
        client.reconnect(session.handle, saved.toUnlockedDraft())
        touch(session.handle)
        status = session.serverInfo
        _messages.tryEmit(tr("已重新连接 {0}", session.name))
    }

    fun closeAll() {
        sessions.map { it.handle }.forEach(::closeSession)
    }

    fun focus(handle: String) {
        activeHandle = handle
        status = sessions.find { it.handle == handle }?.serverInfo ?: status
    }

    fun closeSession(handle: String) {
        val live = sessions.find { it.handle == handle } ?: return
        client.close(handle)
        lastUsed = lastUsed - handle
        if (dataEditor?.handle == handle) dataEditor = null
        if (structureEditor?.handle == handle) structureEditor = null
        val drop = mutableSetOf<String>()
        fun mark(id: String) {
            drop += id
            children[id].orEmpty().forEach(::mark)
        }
        mark(live.rootId)
        nodes = nodes.filterKeys { it !in drop }
        children = children.filterKeys { it !in drop }
        sessions = sessions.filterNot { it.handle == handle }
        if (activeHandle == handle) activeHandle = sessions.lastOrNull()?.handle
        status = sessions.lastOrNull()?.serverInfo ?: tr("就绪")
    }

    fun toggle(nodeId: String) {
        val node = nodes[nodeId] ?: return
        if (!node.meta.expandable) return
        if (node.expanded) {
            nodes = nodes + (nodeId to node.copy(expanded = false))
            return
        }
        if (children.containsKey(nodeId)) {
            nodes = nodes + (nodeId to node.copy(expanded = true))
            return
        }
        nodes = nodes + (nodeId to node.copy(expanded = true, loading = true))
        scope.launch {
            val loaded = runCatching {
                gate.withLock {
                    ensureAlive(node.sessionHandle)
                    client.children(node.sessionHandle, node.meta).also { touch(node.sessionHandle) }
                }
            }
            loaded.onSuccess { metas ->
                val created = metas.map { meta ->
                    TreeNode(
                        id = newId(),
                        sessionHandle = node.sessionHandle,
                        savedId = node.savedId,
                        engineName = node.engineName,
                        meta = meta,
                        depth = node.depth + 1,
                    )
                }
                nodes = nodes + created.associateBy { it.id } + (nodeId to (nodes[nodeId]?.copy(loading = false, expanded = true) ?: node))
                children = children + (nodeId to created.map { it.id })
            }.onFailure {
                nodes = nodes + (nodeId to (nodes[nodeId]?.copy(loading = false, expanded = false) ?: node))
                _messages.tryEmit(it.message ?: tr("读取对象失败"))
            }
        }
    }

    fun openRelation(node: TreeNode) {
        val relation = node.meta.relation ?: return
        val saved = connections.find { it.id == node.savedId } ?: return
        val sql = previewSql(saved.engine, node.meta.catalog, node.meta.schema, relation)
        editor = sql
        editorCursor = sql.length
        scriptName = null
        runEditor(force = true)
    }

    fun showDdl(node: TreeNode) {
        val kind = node.meta.kind
        val whole = kind == NodeKind.Schema || kind == NodeKind.Catalog
        if (!whole && kind != NodeKind.Table && kind != NodeKind.View) return
        if (busy) {
            _messages.tryEmit(tr("等当前任务完成后再查看 DDL"))
            return
        }
        val saved = connections.find { it.id == node.savedId }
        val name = listOfNotNull(
            node.meta.catalog?.takeIf { saved?.engine == Engine.SqlServer || whole },
            node.meta.schema,
            node.meta.relation,
        ).distinct().joinToString(".").ifBlank { node.meta.label }
        val safeName = name.replace(Regex("[^A-Za-z0-9_.\\-]"), "_")
        ddl = DdlView(
            title = if (whole) tr("{0} 全部 DDL", name) else "$name DDL",
            subtitle = listOfNotNull(saved?.name, saved?.engine?.label).joinToString(" · "),
            fileName = if (whole) "${safeName}_ddl.sql" else "$safeName.sql",
            whole = whole,
        )
        busy = true
        val started = TimeSource.Monotonic.markNow()
        ddlJob = scope.launch {
            try {
                val text = gate.withLock {
                    ensureAlive(node.sessionHandle)
                    client.ddl(node.sessionHandle, node.meta) { progress -> ddl = ddl?.copy(progress = progress) }
                }
                val header = buildString {
                    append("-- ").append(if (whole) tr("{0} 全部 DDL", name) else "$name DDL").append('\n')
                    if (saved != null) append("-- ").append(tr("连接：{0} ({1})", saved.name, saved.engine.label)).append('\n')
                    append("-- ").append(tr("生成：KnSQL，用时 {0} ms", started.elapsedNow().inWholeMilliseconds)).append("\n\n")
                }
                val full = header + text.trim() + "\n"
                val lines = full.count { it == '\n' }
                ddl = ddl?.copy(text = full, loading = false, progress = tr("{0} 行 · {1}", lines, formatSize(full.length)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logFailure("ddl", error)
                ddl = ddl?.copy(loading = false, progress = tr("读取失败"), error = error.describeFailure())
            } finally {
                busy = false
                ddlJob = null
            }
        }
    }

    /**
     * Runs [block] on [handle]'s connection while holding the connection lock, after reconnecting if the
     * link went stale. Errors are handed to [onError] instead of escaping.
     */
    internal fun launchOnConnection(
        handle: String,
        onError: (Throwable) -> Unit,
        block: suspend () -> Unit,
    ): Job = scope.launch {
        try {
            gate.withLock {
                ensureAlive(handle)
                block()
                touch(handle)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logFailure("table-edit", error)
            onError(error)
        }
    }

    internal fun sessionEngine(handle: String): Engine? {
        val session = sessions.find { it.handle == handle } ?: return null
        return connections.find { it.id == session.savedId }?.engine
    }

    internal fun sessionReadOnly(handle: String): Boolean {
        val session = sessions.find { it.handle == handle } ?: return false
        return connections.find { it.id == session.savedId }?.readOnly == true
    }

    fun closeDdl() {
        ddlJob?.cancel()
        ddlJob = null
        ddl = null
    }

    fun ddlToEditor() {
        val view = ddl ?: return
        if (view.text.isEmpty()) return
        editor = view.text
        editorCursor = 0
        scriptName = view.fileName
        ddl = null
        _messages.tryEmit(tr("已放入编辑器：{0}", view.fileName))
    }

    private var commentJobs = emptySet<Int>()

    /** Looks up column comments for the result at [index] once, if it reads a single table. */
    fun ensureColumnComments(index: Int) {
        val result = results.getOrNull(index) ?: return
        val source = result.source ?: return
        val handle = activeHandle ?: return
        if (result.columnComments != null || index in commentJobs) return
        commentJobs = commentJobs + index
        scope.launch {
            val found = runCatching {
                gate.withLock { client.columnComments(handle, source) }
            }.onFailure { logFailure("comments", it) }.getOrDefault(emptyMap())
            val current = results.getOrNull(index)
            if (current != null && current.source == source) {
                val comments = current.columns.map { found[it.uppercase()] }
                results = results.toMutableList().also { it[index] = current.copy(columnComments = comments) }
            }
            commentJobs = commentJobs - index
        }
    }

    fun resultFileName(): String {
        val base = sessions.find { it.handle == activeHandle }?.name ?: "result"
        val index = if (results.size > 1) "_${resultIndex + 1}" else ""
        return base.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_") + "_result$index.csv"
    }

    fun report(message: String) {
        _messages.tryEmit(message)
    }

    fun openScript(name: String, text: String) {
        scriptName = name
        editor = text
        editorCursor = 0
        _messages.tryEmit(tr("已打开 {0}，Alt+X 执行整个脚本", name))
    }

    fun runEditor(force: Boolean = false) {
        runScript(editor, force)
    }

    fun runCurrentStatement() {
        val statement = statementAt(editor, editorCursor) ?: editor
        runScript(statement, force = false)
    }

    private fun runScript(script: String, force: Boolean) {
        if (running?.isActive == true) return
        val handle = activeHandle
        if (handle == null) {
            _messages.tryEmit(tr("先打开一个连接"))
            return
        }
        if (!force && settings.confirmWrites && scriptNeedsConfirmation(script)) {
            pendingSql = script
            return
        }
        execute(handle, script)
    }

    var pendingSql by mutableStateOf<String?>(null)

    fun confirmPending() {
        val sql = pendingSql ?: return
        val handle = activeHandle ?: return
        pendingSql = null
        execute(handle, sql)
    }

    fun dismissPending() {
        pendingSql = null
    }

    fun cancel() {
        val handle = activeHandle ?: return
        client.cancel(handle)
        running?.cancel()
        status = tr("正在取消")
    }

    private fun execute(handle: String, script: String) {
        val statements = splitSqlScript(script)
        if (statements.isEmpty()) {
            _messages.tryEmit(tr("没有可执行的语句"))
            return
        }
        val live = sessions.find { it.handle == handle }
        val startedAll = TimeSource.Monotonic.markNow()
        busy = true
        scriptLog = emptyList()
        results = emptyList()
        commentJobs = emptySet()
        running = scope.launch {
            var failed: String? = null
            var wasCancelled = false
            var finished = 0
            try {
                gate.withLock {
                    ensureAlive(handle)
                    val collected = mutableListOf<QueryResult>()
                    statements.forEachIndexed { index, statement ->
                        ensureActive()
                        status = tr("脚本 {0}/{1}", index + 1, statements.size)
                        val started = TimeSource.Monotonic.markNow()
                        try {
                            val limit = if (settings.unlimitedRows) 0 else settings.maxRows
                            val batch = client.execute(handle, statement, limit, settings.queryTimeoutSec)
                            touch(handle)
                            val elapsed = started.elapsedNow().inWholeMilliseconds
                            val summary = batch.joinToString(tr("；")) { result ->
                                if (result.columns.isEmpty()) tr("影响 {0} 行", result.updateCount) else tr("{0} 行", result.rows.size)
                            }.ifBlank { tr("完成") }
                            scriptLog = scriptLog + "${index + 1}. $summary · ${elapsed} ms"
                            val grids = batch.filter { it.columns.isNotEmpty() }
                            if (grids.isNotEmpty() && collected.size < 30) collected += grids
                            results = collected.toList()
                            finished = index + 1
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            val elapsed = started.elapsedNow().inWholeMilliseconds
                            val lost = error.looksDisconnected()
                            if (lost) lastUsed = lastUsed - handle
                            val reason = error.message ?: tr("执行失败")
                            val message = if (lost) {
                                tr("第 {0}/{1} 条失败：{2}。连接像是断了，再执行一次会自动重连。", index + 1, statements.size, reason)
                            } else {
                                tr("第 {0}/{1} 条失败：{2}", index + 1, statements.size, reason)
                            }
                            failed = message
                            scriptLog = scriptLog + tr("{0}. 失败 · {1}", index + 1, reason)
                            recordHistory(
                                live?.name ?: handle,
                                scriptName ?: statement,
                                false,
                                message,
                                elapsed,
                            )
                            return@withLock
                        }
                    }
                    results = collected
                    resultIndex = 0
                }
            } catch (_: CancellationException) {
                val message = tr("已取消，完成 {0}/{1}", finished, statements.size)
                failed = message
                wasCancelled = true
                scriptLog = scriptLog + message
            } catch (error: Throwable) {
                // Reconnecting before the first statement failed; nothing was sent to the server.
                logFailure("execute", error)
                val message = tr("连接失败：{0}", error.message?.lineSequence()?.firstOrNull().orEmpty())
                failed = message
                scriptLog = scriptLog + message
            } finally {
                val elapsed = startedAll.elapsedNow().inWholeMilliseconds
                val message = failed
                if (message == null) {
                    val summary = tr("完成 {0} 条 · {1} ms", statements.size, elapsed)
                    status = live?.serverInfo ?: summary
                    recordHistory(live?.name ?: handle, scriptName ?: script, true, summary, elapsed)
                    _messages.tryEmit(summary)
                } else {
                    status = message
                    if (wasCancelled) {
                        recordHistory(live?.name ?: handle, scriptName ?: script, false, message, elapsed)
                    }
                    _messages.tryEmit(message)
                }
                busy = false
                running = null
            }
        }
    }

    private fun recordHistory(name: String, sql: String, success: Boolean, message: String, elapsed: Long) {
        val entry = HistoryEntry(
            id = newId(),
            connectionName = name,
            sql = sql.take(4000),
            success = success,
            message = message.take(500),
            elapsedMs = elapsed,
            atEpochMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        )
        history = (listOf(entry) + history).take(100)
        persist = persist.copy(history = history)
        persistAsync()
    }

    fun deleteHistory(ids: Set<String>) {
        if (ids.isEmpty()) return
        history = history.filterNot { it.id in ids }
        persist = persist.copy(history = history)
        persistAsync()
    }

    fun clearHistory() {
        history = emptyList()
        persist = persist.copy(history = history)
        persistAsync()
    }

    private fun persistAsync() {
        val snapshot = persist
        scope.launch(Dispatchers.IO) { store.write(snapshot) }
    }

    private fun SavedConnection.toUnlockedDraft(): ConnectionDraft {
        val password = runCatching { store.decrypt(passwordCipher) }.getOrDefault("")
        return toDraft(password).copy(password = password, keepStoredPassword = false)
    }
}

fun Throwable.describeFailure(): String {
    val lines = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 6) {
        lines += "${current::class.simpleName}: ${current.message}"
        current.stackTrace.take(4).forEach { lines += "  at $it" }
        current = current.cause
        depth++
    }
    return lines.joinToString("\n")
}

fun newId(): String = kotlin.random.Random.nextBytes(16).joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private val defaultQueries = setOf("SELECT 1", "SELECT 1 FROM DUAL")

private fun defaultQuery(engine: Engine): String =
    if (engine.oracleLike) "SELECT 1 FROM DUAL" else "SELECT 1"

private fun formatSize(chars: Int): String = when {
    chars >= 1_048_576 -> tr("{0} M 字符", (chars / 104_857.6).toInt() / 10.0)
    chars >= 1024 -> tr("{0} K 字符", chars / 1024)
    else -> tr("{0} 字符", chars)
}

private const val HEARTBEAT_SECONDS = 90L
private const val RECHECK_AFTER_SECONDS = 45L

private fun Throwable.looksDisconnected(): Boolean {
    val text = generateSequence(this) { it.cause }.take(5)
        .joinToString(" ") { "${it::class.simpleName} ${it.message}" }
        .lowercase()
    return listOf(
        "recoverable", "closed connection", "connection reset", "broken pipe", "socket", "no more data to read",
        "ora-03113", "ora-03114", "ora-03135", "ora-17002", "ora-17008", "ora-17410", "communications link failure",
        "connection is closed", "connection closed", "i/o error",
    ).any { it in text }
}
