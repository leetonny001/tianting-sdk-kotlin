package com.tianting.sdk

import java.io.Closeable

/**
 * SDK 使用的 SQLite 最小接口，平台各实现一份：JVM 是 sdk-sqlite-jdbc，Android 是 sdk-android。
 * 所有 SQL 和事务边界都在核心模块（LocalDb），JVM 测试验证的就是 Android 上执行的同一份 SQL。
 * 只在 SDK 的单线程上调用。
 */
public interface LocalDatabase : Closeable {
    public fun execute(sql: String, args: List<Any?> = emptyList())

    /** 查询（含 PRAGMA）。整数列返回 Long，文本列返回 String。 */
    public fun query(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>>

    /** 事务：block 正常返回则提交，抛异常则回滚并把异常抛出。可以嵌套，最外层决定提交。 */
    public fun <T> transaction(block: () -> T): T
}

private const val SCHEMA_VERSION = 2L

/** 冷启动时每个会话最多恢复多少条消息，更早的从库里删掉，靠向上翻页。 */
internal const val RESTORE_LIMIT_PER_CONV = 200

/** 一个会话记下的"不能再写回来"的消息。 */
internal class DeletedRefs(
    val ids: MutableSet<String> = HashSet(),
    val seqs: MutableSet<Long> = HashSet(),
    val removedClient: MutableSet<String> = HashSet(),
    /** 文件已过期的 msgId（F-RCL-03 规则 2a：expired 只能从假变真，跨冷启动保留）。 */
    val expired: MutableSet<String> = HashSet(),
)

/** 从本地库恢复出来的全部状态。 */
internal class RestoredState(
    val owner: String?,
    val conversations: List<Triple<Conversation, Long, Boolean>>,
    val messages: List<Message>,
    val deletedRefs: Map<String, DeletedRefs>,
    val socialVer: Long,
    val requestSeenVer: Long,
    val settings: SocialSettings,
    val official: Boolean,
    val socialLoaded: Boolean,
    val friends: List<FriendItem>,
    val requests: List<RequestItem>,
    val blocks: List<BlockItem>,
    /** 还没发出去的附件的沙盒副本（F-FILE-07 规则 2）。 */
    val attachments: List<AttachmentRecord> = emptyList(),
    /** 最近一次重拉时各会话的 modVer（F-RCL-03 规则 2）。 */
    val modVers: Map<String, Long> = emptyMap(),
)

/** 一条附件消息的沙盒副本（文件在 attachmentDir 里，按 clientMsgId 定位）。name 是文件名，和消息正文一样只在本地库。 */
@kotlinx.serialization.Serializable
internal data class AttachmentRecord(
    val convId: String,
    val clientMsgId: String,
    val kind: String,
    val name: String,
    val mime: String,
    val thumbMime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** 原文件、缩略图已经复制进沙盒。处理中被打断的没有。 */
    val copied: Boolean = false,
    /**
     * 这台设备上传、complete 成功得到的 fileId（F-FILE-07 规则 4，v1.4，P-37 ①）：send 回 4048 时只有它按"已清理"处理。
     * 跨冷启动保留（json 列，旧行没有这个字段按 null 解码）。
     */
    val uploadedFileId: String? = null,
)

/**
 * 本地库（FE-131、FE-435）。消息正文只放在 messages.json、摘要只放在 conversations.json，不建索引、不进全文检索，
 * 这样改写这一列就能清掉原文。
 *
 * 删除原文后数据库文件里搜不到特征串（AC-ADM-31-4）靠三件事：
 * 1. PRAGMA secure_delete=ON：释放的单元和页面清零。个别构建不支持时（返回值不是 1）改用每次清除后 VACUUM；
 * 2. PRAGMA journal_mode=DELETE：不用 WAL，原文不会留在 -wal 里，回滚日志在提交时删除；
 * 3. 清除后最外层事务提交时执行 wal_checkpoint(TRUNCATE)，防止平台强制 WAL（Android 的 compatibility WAL）。
 */
internal class LocalDb(private val db: LocalDatabase, private val log: Logger) : StoreSink, SocialSink {
    private var secureDeleteOk = true
    private var depth = 0
    private var dirty = false
    private var txFailed = false

    /** 被踢清库后为 true：内存里还留着失败消息给界面看，但不再写库，直到同一账号重新登录。 */
    var paused = false

    fun open() {
        secureDeleteOk = (db.query("PRAGMA secure_delete=ON").firstOrNull()?.values?.firstOrNull() as? Long) == 1L
        db.query("PRAGMA journal_mode=DELETE")
        val version = db.query("PRAGMA user_version").firstOrNull()?.values?.firstOrNull() as? Long ?: 0L
        if (version == 1L) {
            // 第 1 版 → 第 2 版（FE-631，C2）：加附件沙盒和 modVer 两张表，原有的会话、消息（含待发、失败）、社交数据都保留。
            // 新的发送状态（处理中、上传中）和消息的 image、file 都在 json 列里，旧行照常解码。
            db.transaction {
                createV2Tables()
                db.execute("PRAGMA user_version = $SCHEMA_VERSION")
            }
        } else if (version != SCHEMA_VERSION) {
            // 本地库只是缓存，服务端是真相源：版本不同直接重建。
            for (t in TABLES) db.execute("DROP TABLE IF EXISTS $t")
            createTables()
            db.execute("PRAGMA user_version = $SCHEMA_VERSION")
        }
    }

    private fun createTables() = db.transaction {
        db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execute("CREATE TABLE conversations(conv_id TEXT PRIMARY KEY, read_base INTEGER NOT NULL, placeholder INTEGER NOT NULL, json TEXT NOT NULL)")
        db.execute("CREATE TABLE messages(conv_id TEXT NOT NULL, row_key TEXT NOT NULL, seq INTEGER, json TEXT NOT NULL, PRIMARY KEY(conv_id, row_key))")
        db.execute("CREATE TABLE deleted_refs(conv_id TEXT NOT NULL, kind TEXT NOT NULL, ref TEXT NOT NULL, PRIMARY KEY(conv_id, kind, ref))")
        db.execute("CREATE TABLE social_meta(id INTEGER PRIMARY KEY CHECK(id = 1), social_ver INTEGER NOT NULL, request_seen_ver INTEGER NOT NULL, settings TEXT NOT NULL, official INTEGER NOT NULL, loaded INTEGER NOT NULL)")
        db.execute("CREATE TABLE friends(user_id TEXT PRIMARY KEY, ver INTEGER NOT NULL, json TEXT NOT NULL)")
        db.execute("CREATE TABLE friend_requests(user_id TEXT NOT NULL, direction TEXT NOT NULL, ver INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(user_id, direction))")
        db.execute("CREATE TABLE blocks(user_id TEXT PRIMARY KEY, ver INTEGER NOT NULL, json TEXT NOT NULL)")
        createV2Tables()
    }

    private fun createV2Tables() {
        db.execute("CREATE TABLE attachments(client_msg_id TEXT PRIMARY KEY, conv_id TEXT NOT NULL, json TEXT NOT NULL)")
        db.execute("CREATE TABLE mod_vers(conv_id TEXT PRIMARY KEY, mod_ver INTEGER NOT NULL)")
    }

    /**
     * 本地库写失败不能影响收发：内存状态继续有效，服务端是真相源，下次同步修复。所以写操作不向外抛异常：
     * 事务里失败时记下来，等调用方（改内存的代码）跑完再整体回滚，保证 socialVer 与数据要么一起写入、要么都不写。
     */
    private inline fun guarded(block: () -> Unit) {
        if (paused || txFailed) return
        try {
            block()
        } catch (e: Exception) {
            if (depth > 0) txFailed = true else logFailure(e)
        }
    }

    private fun logFailure(e: Exception) = log.error("im: local database failed: ${safeDescribe(e)}")

    private class Rollback : Exception()

    /** 最外层事务提交后，如果清除过原文，把它从磁盘上也清掉。 */
    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        depth = 1
        txFailed = false
        var result: Result<T>? = null
        try {
            db.transaction {
                result = runCatching(block)
                if (txFailed || result!!.isFailure) throw Rollback()
            }
        } catch (e: Exception) {
            if (e !is Rollback || txFailed) logFailure(if (e is Rollback) IllegalStateException("write failed") else e)
        } finally {
            depth = 0
            txFailed = false
        }
        if (dirty) {
            dirty = false
            flushDeleted()
        }
        // db.transaction 没能开始（比如文件坏了）时 result 是 null：内存逻辑还是要跑。
        val r = result ?: return block()
        return r.getOrThrow()
    }

    private fun markDirty() {
        dirty = true
        if (depth == 0) {
            dirty = false
            flushDeleted()
        }
    }

    private fun flushDeleted() {
        try {
            db.query("PRAGMA wal_checkpoint(TRUNCATE)")
            if (!secureDeleteOk) db.execute("VACUUM")
        } catch (e: Exception) {
            logFailure(e)
        }
    }

    // ---- 账号 ----

    fun owner(): String? = db.query("SELECT value FROM meta WHERE key = 'owner'").firstOrNull()?.get("value") as? String

    fun setOwner(userId: String) {
        guarded { db.execute("INSERT OR REPLACE INTO meta(key, value) VALUES('owner', ?)", listOf(userId)) }
    }

    /** 被踢 / 退出登录 / 换账号：清空全部表（FE-131），并把内容从文件里清掉。 */
    fun wipeAll() {
        db.transaction { for (t in TABLES) db.execute("DELETE FROM $t") }
        db.query("PRAGMA wal_checkpoint(TRUNCATE)")
        db.execute("VACUUM")
    }

    // ---- 会话 ----

    override fun upsertConversation(c: ConvState, placeholder: Boolean) = guarded {
        writeConv(c, placeholder)
        if (c.view.lastMsg?.deleted == true) markDirty()
    }

    private fun writeConv(c: ConvState, placeholder: Boolean) {
        db.execute(
            "INSERT OR REPLACE INTO conversations(conv_id, read_base, placeholder, json) VALUES(?, ?, ?, ?)",
            listOf(c.view.convId, c.readBase, if (placeholder) 1L else 0L, ImJson.encodeToString(Conversation.serializer(), c.view)),
        )
    }

    override fun deleteConversation(convId: String) = guarded {
        transaction {
            guarded {
                db.execute("DELETE FROM conversations WHERE conv_id = ?", listOf(convId))
                db.execute("DELETE FROM messages WHERE conv_id = ?", listOf(convId))
                db.execute("DELETE FROM deleted_refs WHERE conv_id = ?", listOf(convId))
                markDirty()
            }
        }
    }

    override fun replaceConversations(list: List<Pair<ConvState, Boolean>>) = guarded {
        transaction {
            guarded {
                db.execute("DELETE FROM conversations")
                for ((c, placeholder) in list) writeConv(c, placeholder)
                if (list.any { it.first.view.lastMsg?.deleted == true }) markDirty()
            }
        }
    }

    // ---- 消息 ----

    private fun rowKey(m: Message) = m.seq?.let { "s$it" } ?: "c${m.clientMsgId}"

    override fun upsertMessage(m: Message) = guarded {
        db.execute(
            "INSERT OR REPLACE INTO messages(conv_id, row_key, seq, json) VALUES(?, ?, ?, ?)",
            listOf(m.convId, rowKey(m), m.seq, ImJson.encodeToString(Message.serializer(), m)),
        )
    }

    override fun wipeMessage(m: Message) = guarded {
        upsertMessage(m)
        markDirty()
    }

    override fun deleteUnsent(convId: String, clientMsgId: String) = guarded {
        db.execute("DELETE FROM messages WHERE conv_id = ? AND row_key = ?", listOf(convId, "c$clientMsgId"))
        markDirty()
    }

    override fun clearServerMessages(convId: String) = guarded {
        db.execute("DELETE FROM messages WHERE conv_id = ? AND seq IS NOT NULL", listOf(convId))
        markDirty()
    }

    override fun addDeletedRefs(convId: String, msgIds: Collection<String>, seqs: Collection<Long>) = guarded {
        for (id in msgIds) db.execute("INSERT OR IGNORE INTO deleted_refs(conv_id, kind, ref) VALUES(?, 'id', ?)", listOf(convId, id))
        for (s in seqs) db.execute("INSERT OR IGNORE INTO deleted_refs(conv_id, kind, ref) VALUES(?, 'seq', ?)", listOf(convId, s.toString()))
    }

    /** removeFailed 删过的 clientMsgId。 */
    override fun addRemovedClient(convId: String, clientMsgIds: Collection<String>) = guarded {
        for (id in clientMsgIds) db.execute("INSERT OR IGNORE INTO deleted_refs(conv_id, kind, ref) VALUES(?, 'client', ?)", listOf(convId, id))
    }

    override fun addExpiredRefs(convId: String, msgIds: Collection<String>) = guarded {
        for (id in msgIds) db.execute("INSERT OR IGNORE INTO deleted_refs(conv_id, kind, ref) VALUES(?, 'expired', ?)", listOf(convId, id))
    }

    // ---- 附件和 modVer（FE-631） ----

    fun saveAttachment(a: AttachmentRecord) = guarded {
        db.execute(
            "INSERT OR REPLACE INTO attachments(client_msg_id, conv_id, json) VALUES(?, ?, ?)",
            listOf(a.clientMsgId, a.convId, ImJson.encodeToString(AttachmentRecord.serializer(), a)),
        )
    }

    fun deleteAttachment(clientMsgId: String) = guarded {
        db.execute("DELETE FROM attachments WHERE client_msg_id = ?", listOf(clientMsgId))
        markDirty()
    }

    fun setModVer(convId: String, modVer: Long) = guarded {
        db.execute("INSERT OR REPLACE INTO mod_vers(conv_id, mod_ver) VALUES(?, ?)", listOf(convId, modVer))
    }

    // ---- 社交 ----

    override fun upsertFriend(f: FriendItem) = guarded {
        db.execute("INSERT OR REPLACE INTO friends(user_id, ver, json) VALUES(?, ?, ?)", listOf(f.userId, f.ver, ImJson.encodeToString(FriendItem.serializer(), f)))
    }

    override fun upsertRequest(r: RequestItem) = guarded {
        db.execute(
            "INSERT OR REPLACE INTO friend_requests(user_id, direction, ver, json) VALUES(?, ?, ?, ?)",
            listOf(r.userId, r.direction.wire, r.ver, ImJson.encodeToString(RequestItem.serializer(), r)),
        )
    }

    override fun upsertBlock(b: BlockItem) = guarded {
        db.execute("INSERT OR REPLACE INTO blocks(user_id, ver, json) VALUES(?, ?, ?)", listOf(b.userId, b.ver, ImJson.encodeToString(BlockItem.serializer(), b)))
    }

    override fun deleteRequest(userId: String, direction: RequestDirection) = guarded {
        db.execute("DELETE FROM friend_requests WHERE user_id = ? AND direction = ?", listOf(userId, direction.wire))
    }

    override fun replaceAll(friends: Collection<FriendItem>, requests: Collection<RequestItem>, blocks: Collection<BlockItem>) = guarded {
        db.execute("DELETE FROM friends")
        db.execute("DELETE FROM friend_requests")
        db.execute("DELETE FROM blocks")
        friends.forEach { upsertFriend(it) }
        requests.forEach { upsertRequest(it) }
        blocks.forEach { upsertBlock(it) }
    }

    override fun saveMeta(state: SocialState) = guarded {
        db.execute(
            "INSERT OR REPLACE INTO social_meta(id, social_ver, request_seen_ver, settings, official, loaded) VALUES(1, ?, ?, ?, ?, ?)",
            listOf(
                state.ver,
                state.requestSeenVer,
                ImJson.encodeToString(SocialSettings.serializer(), state.settings),
                if (state.official) 1L else 0L,
                if (state.loaded) 1L else 0L,
            ),
        )
    }

    // ---- 冷启动恢复 ----

    fun load(): RestoredState {
        val conversations = db.query("SELECT read_base, placeholder, json FROM conversations").map {
            Triple(
                ImJson.decodeFromString(Conversation.serializer(), it["json"] as String),
                it["read_base"] as Long,
                (it["placeholder"] as Long) != 0L,
            )
        }
        // 每个会话保留最新的 RESTORE_LIMIT_PER_CONV 条；没有 seq 的（待发 / 失败）全部保留。
        val rows = db.query("SELECT conv_id, seq, json FROM messages ORDER BY conv_id, seq IS NULL DESC, seq DESC")
        val kept = HashMap<String, Int>()
        val trimBelow = HashMap<String, Long>()
        val messages = ArrayList<Message>()
        for (r in rows) {
            val conv = r["conv_id"] as String
            val seq = r["seq"] as Long?
            if (seq != null) {
                val n = kept.getOrDefault(conv, 0)
                if (n >= RESTORE_LIMIT_PER_CONV) {
                    trimBelow.putIfAbsent(conv, seq + 1)
                    continue
                }
                kept[conv] = n + 1
            }
            messages.add(ImJson.decodeFromString(Message.serializer(), r["json"] as String))
        }
        if (trimBelow.isNotEmpty()) {
            db.transaction {
                for ((conv, below) in trimBelow) db.execute("DELETE FROM messages WHERE conv_id = ? AND seq < ?", listOf(conv, below))
            }
            flushDeleted()
        }
        val refs = HashMap<String, DeletedRefs>()
        for (r in db.query("SELECT conv_id, kind, ref FROM deleted_refs")) {
            val e = refs.getOrPut(r["conv_id"] as String) { DeletedRefs() }
            when (r["kind"]) {
                "id" -> e.ids.add(r["ref"] as String)
                "seq" -> e.seqs.add((r["ref"] as String).toLong())
                "client" -> e.removedClient.add(r["ref"] as String)
                "expired" -> e.expired.add(r["ref"] as String)
            }
        }
        val meta = db.query("SELECT social_ver, request_seen_ver, settings, official, loaded FROM social_meta").firstOrNull()
        return RestoredState(
            owner = owner(),
            conversations = conversations,
            messages = messages,
            deletedRefs = refs,
            socialVer = meta?.get("social_ver") as Long? ?: 0,
            requestSeenVer = meta?.get("request_seen_ver") as Long? ?: 0,
            settings = (meta?.get("settings") as String?)?.let { ImJson.decodeFromString(SocialSettings.serializer(), it) } ?: SocialSettings(),
            official = (meta?.get("official") as Long? ?: 0L) != 0L,
            socialLoaded = (meta?.get("loaded") as Long? ?: 0L) != 0L,
            friends = db.query("SELECT json FROM friends").map { ImJson.decodeFromString(FriendItem.serializer(), it["json"] as String) },
            requests = db.query("SELECT json FROM friend_requests").map { ImJson.decodeFromString(RequestItem.serializer(), it["json"] as String) },
            blocks = db.query("SELECT json FROM blocks").map { ImJson.decodeFromString(BlockItem.serializer(), it["json"] as String) },
            attachments = db.query("SELECT json FROM attachments").map { ImJson.decodeFromString(AttachmentRecord.serializer(), it["json"] as String) },
            modVers = db.query("SELECT conv_id, mod_ver FROM mod_vers").associate { it["conv_id"] as String to it["mod_ver"] as Long },
        )
    }

    fun close() {
        try {
            db.close()
        } catch (e: Exception) {
            log.warn("im: close database failed: ${safeDescribe(e)}")
        }
    }

    private companion object {
        val TABLES = listOf("meta", "conversations", "messages", "deleted_refs", "social_meta", "friends", "friend_requests", "blocks", "attachments", "mod_vers")
    }
}
