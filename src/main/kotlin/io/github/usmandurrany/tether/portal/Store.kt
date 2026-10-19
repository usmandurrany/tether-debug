package io.github.usmandurrany.tether.portal

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
data class DeviceView(
    val installId: String,
    val platform: String,
    val osVersion: String?,
    val model: String?,
    val appId: String?,
    val appVersion: String?,
    val firstSeen: Long,
    val lastSeen: Long,
    val lastAccount: String?,
    val sessions: Int,
    val entries: Long,
)

@Serializable
data class SessionView(
    val id: String,
    val installId: String,
    val startedAt: Long,
    val label: String?,
    val lastSeen: Long,
    val entries: Long,
    val dropped: Long,
    val lastAccount: String?,
)

@Serializable
data class StanzaView(val name: String, val type: String?, val id: String?, val from: String?, val to: String?, val payload: String?)

@Serializable
data class EntryView(
    /** Arrival order at the portal; the cursor for paging and live updates. */
    val id: Long,
    val sessionId: String,
    val installId: String,
    val label: String?,
    val seq: Long,
    val ts: Long,
    val receivedAt: Long,
    val level: String,
    val tag: String,
    val account: String?,
    val dir: String?,
    val text: String,
    val stanza: StanzaView?,
    val error: String?,
)

/** Filters shared by the entry query and the live stream. Every field is optional. */
data class EntryFilter(
    val installId: String? = null,
    val sessionId: String? = null,
    val label: String? = null,
    /** Matches the bare JID or any of its resources. */
    val account: String? = null,
    val dir: String? = null,
    /** Stanza name: message, presence, iq, or `event` for non-stanza entries. */
    val kind: String? = null,
    /** Minimum level. */
    val level: String? = null,
    val tag: String? = null,
    /** Case-insensitive substring of the text. */
    val text: String? = null,
) {
    fun matches(e: EntryView): Boolean =
        (installId == null || e.installId == installId) &&
            (sessionId == null || e.sessionId == sessionId) &&
            (label == null || e.label == label) &&
            (account == null || e.account == account || e.account?.startsWith("$account/") == true) &&
            (dir == null || e.dir == dir) &&
            (kind == null || (if (kind == "event") e.stanza == null else e.stanza?.name == kind)) &&
            (level == null || rank(e.level) >= rank(level)) &&
            (tag == null || e.tag == tag) &&
            (text == null || e.text.contains(text, ignoreCase = true))

    companion object {
        private val ORDER = listOf("verbose", "debug", "info", "warn", "error")
        fun rank(level: String): Int = ORDER.indexOf(level)
    }
}

/**
 * SQLite storage. One connection behind a lock: the portal serves a test team,
 * and SQLite in WAL mode writes a batch in well under a millisecond.
 * Callers run these methods on Dispatchers.IO.
 */
class Store(path: Path) : AutoCloseable {
    private val lock = ReentrantLock()
    private val db: Connection

    init {
        path.parent?.let { Files.createDirectories(it) }
        db = DriverManager.getConnection("jdbc:sqlite:$path")
        db.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA synchronous=NORMAL")
            s.execute("PRAGMA foreign_keys=ON")
            SCHEMA.forEach { s.execute(it) }
        }
    }

    /** Stores a validated batch and returns the entries that were new (a retried batch returns none). */
    fun ingest(batch: UploadBatch, now: Long): List<EntryView> = lock.withLock {
        transaction {
            val d = batch.device
            val s = batch.session
            val lastAccount = batch.entries.lastOrNull { it.account != null }?.account
            prepare(
                """INSERT INTO devices(install_id, platform, os_version, model, app_id, app_version, first_seen, last_seen, last_account)
                   VALUES(?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(install_id) DO UPDATE SET platform=excluded.platform, os_version=excluded.os_version,
                     model=excluded.model, app_id=excluded.app_id, app_version=excluded.app_version, last_seen=excluded.last_seen,
                     last_account=COALESCE(excluded.last_account, devices.last_account)""",
            ) { st ->
                st.bind(d.installId, d.platform, d.osVersion, d.model, d.appId, d.appVersion, now, now, lastAccount)
                st.executeUpdate()
            }
            prepare(
                """INSERT INTO sessions(id, install_id, started_at, label, first_seen, last_seen, dropped, last_account)
                   VALUES(?,?,?,?,?,?,0,?)
                   ON CONFLICT(id) DO UPDATE SET last_seen=excluded.last_seen,
                     last_account=COALESCE(excluded.last_account, sessions.last_account)""",
            ) { st ->
                st.bind(s.id, d.installId, s.startedAt, s.label, now, now, lastAccount)
                st.executeUpdate()
            }
            val inserted = ArrayList<EntryView>()
            prepare(
                """INSERT OR IGNORE INTO entries(session_id, install_id, label, seq, ts, received_at, level, tag, account, dir, text,
                     st_name, st_type, st_id, st_from, st_to, st_payload, error)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            ) { st ->
                for (e in batch.entries) {
                    val stz = e.stanza
                    st.bind(
                        s.id, d.installId, s.label, e.seq, e.ts, now, e.level, e.tag, e.account, e.dir, e.text,
                        stz?.name, stz?.type, stz?.id, stz?.from, stz?.to, stz?.payload, e.error,
                    )
                    if (st.executeUpdate() == 1) {
                        val id = db.createStatement().use { q -> q.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
                        inserted += EntryView(
                            id, s.id, d.installId, s.label, e.seq, e.ts, now, e.level, e.tag, e.account, e.dir, e.text,
                            stz?.let { StanzaView(it.name, it.type, it.id, it.from, it.to, it.payload) }, e.error,
                        )
                    }
                }
            }
            // Count drops only with new entries, so a retried batch does not count them twice.
            val dropped = if (inserted.isNotEmpty() || batch.entries.isEmpty()) batch.dropped else 0
            prepare("UPDATE sessions SET entries = entries + ?, dropped = dropped + ? WHERE id = ?") { st ->
                st.bind(inserted.size, dropped, s.id)
                st.executeUpdate()
            }
            inserted
        }
    }

    fun devices(): List<DeviceView> = lock.withLock {
        prepare(
            """SELECT d.*, COUNT(s.id) AS n_sessions, COALESCE(SUM(s.entries), 0) AS n_entries
               FROM devices d LEFT JOIN sessions s ON s.install_id = d.install_id
               GROUP BY d.install_id ORDER BY d.last_seen DESC""",
        ) { st ->
            st.executeQuery().rows {
                DeviceView(
                    getString("install_id"), getString("platform"), getString("os_version"), getString("model"),
                    getString("app_id"), getString("app_version"), getLong("first_seen"), getLong("last_seen"),
                    getString("last_account"), getInt("n_sessions"), getLong("n_entries"),
                )
            }
        }
    }

    fun sessions(installId: String?, label: String?, limit: Int): List<SessionView> = lock.withLock {
        val where = ArrayList<String>()
        val args = ArrayList<Any?>()
        installId?.let { where += "install_id = ?"; args += it }
        label?.let { where += "label = ?"; args += it }
        val sql = "SELECT * FROM sessions" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY last_seen DESC LIMIT ?"
        prepare(sql) { st ->
            st.bind(*args.toTypedArray(), limit)
            st.executeQuery().rows {
                SessionView(
                    getString("id"), getString("install_id"), getLong("started_at"), getString("label"),
                    getLong("last_seen"), getLong("entries"), getLong("dropped"), getString("last_account"),
                )
            }
        }
    }

    fun labels(): List<String> = lock.withLock {
        prepare("SELECT DISTINCT label FROM sessions WHERE label IS NOT NULL ORDER BY label") { st -> st.executeQuery().rows { getString(1) } }
    }

    /** Newest first. [beforeId] pages back; [afterId] fetches what arrived since (oldest first is then reversed by the caller). */
    fun entries(filter: EntryFilter, beforeId: Long?, afterId: Long?, limit: Int): List<EntryView> = lock.withLock {
        val where = ArrayList<String>()
        val args = ArrayList<Any?>()
        filter.installId?.let { where += "install_id = ?"; args += it }
        filter.sessionId?.let { where += "session_id = ?"; args += it }
        filter.label?.let { where += "label = ?"; args += it }
        filter.account?.let {
            where += "(account = ? OR substr(account, 1, ?) = ?)"
            args += it; args += it.length + 1; args += "$it/"
        }
        filter.dir?.let { where += "dir = ?"; args += it }
        filter.kind?.let { if (it == "event") where += "st_name IS NULL" else { where += "st_name = ?"; args += it } }
        filter.level?.let { min ->
            val allowed = UploadRules.LEVELS.filter { EntryFilter.rank(it) >= EntryFilter.rank(min) }
            where += "level IN (${allowed.joinToString { "?" }})"
            args.addAll(allowed)
        }
        filter.tag?.let { where += "tag = ?"; args += it }
        filter.text?.let { where += "instr(lower(text), lower(?)) > 0"; args += it }
        beforeId?.let { where += "id < ?"; args += it }
        afterId?.let { where += "id > ?"; args += it }
        val sql = "SELECT * FROM entries" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY id DESC LIMIT ?"
        prepare(sql) { st ->
            st.bind(*args.toTypedArray(), limit)
            st.executeQuery().rows { entry() }
        }
    }

    /** All entries of one session in device order, for export. */
    fun sessionEntries(sessionId: String, limit: Int): List<EntryView> = lock.withLock {
        prepare("SELECT * FROM entries WHERE session_id = ? ORDER BY seq LIMIT ?") { st ->
            st.bind(sessionId, limit)
            st.executeQuery().rows { entry() }
        }
    }

    fun deleteSession(sessionId: String): Boolean = lock.withLock {
        transaction {
            prepare("DELETE FROM entries WHERE session_id = ?") { it.bind(sessionId); it.executeUpdate() }
            prepare("DELETE FROM sessions WHERE id = ?") { it.bind(sessionId); it.executeUpdate() } == 1
        }
    }

    /** Deletes entries received before [cutoff], then sessions and devices left without any. Returns the entries removed. */
    fun prune(cutoff: Long): Int = lock.withLock {
        transaction {
            val removed = prepare("DELETE FROM entries WHERE received_at < ?") { it.bind(cutoff); it.executeUpdate() }
            prepare("DELETE FROM sessions WHERE last_seen < ? AND NOT EXISTS (SELECT 1 FROM entries e WHERE e.session_id = sessions.id)") {
                it.bind(cutoff); it.executeUpdate()
            }
            prepare("DELETE FROM devices WHERE last_seen < ? AND NOT EXISTS (SELECT 1 FROM sessions s WHERE s.install_id = devices.install_id)") {
                it.bind(cutoff); it.executeUpdate()
            }
            removed
        }
    }

    override fun close() {
        lock.withLock { db.close() }
    }

    private fun ResultSet.entry(): EntryView = EntryView(
        getLong("id"), getString("session_id"), getString("install_id"), getString("label"), getLong("seq"), getLong("ts"),
        getLong("received_at"), getString("level"), getString("tag"), getString("account"), getString("dir"), getString("text"),
        getString("st_name")?.let { StanzaView(it, getString("st_type"), getString("st_id"), getString("st_from"), getString("st_to"), getString("st_payload")) },
        getString("error"),
    )

    private fun <T> transaction(block: () -> T): T {
        db.autoCommit = false
        try {
            return block().also { db.commit() }
        } catch (t: Throwable) {
            db.rollback()
            throw t
        } finally {
            db.autoCommit = true
        }
    }

    private fun <T> prepare(sql: String, block: (PreparedStatement) -> T): T = db.prepareStatement(sql).use(block)

    private fun PreparedStatement.bind(vararg values: Any?) {
        values.flatMap { if (it is List<*>) it else listOf(it) }.forEachIndexed { i, v ->
            when (v) {
                null -> setNull(i + 1, Types.NULL)
                is String -> setString(i + 1, v)
                is Int -> setInt(i + 1, v)
                is Long -> setLong(i + 1, v)
                else -> error("Unsupported parameter ${v::class}")
            }
        }
    }

    private fun <T> ResultSet.rows(map: ResultSet.() -> T): List<T> = use { rs ->
        buildList { while (rs.next()) add(rs.map()) }
    }

    private companion object {
        val SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS devices(
                 install_id TEXT PRIMARY KEY, platform TEXT NOT NULL, os_version TEXT, model TEXT, app_id TEXT, app_version TEXT,
                 first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, last_account TEXT)""",
            """CREATE TABLE IF NOT EXISTS sessions(
                 id TEXT PRIMARY KEY, install_id TEXT NOT NULL, started_at INTEGER NOT NULL, label TEXT,
                 first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, entries INTEGER NOT NULL DEFAULT 0,
                 dropped INTEGER NOT NULL DEFAULT 0, last_account TEXT)""",
            "CREATE INDEX IF NOT EXISTS sessions_device ON sessions(install_id, last_seen)",
            """CREATE TABLE IF NOT EXISTS entries(
                 id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, install_id TEXT NOT NULL, label TEXT,
                 seq INTEGER NOT NULL, ts INTEGER NOT NULL, received_at INTEGER NOT NULL, level TEXT NOT NULL, tag TEXT NOT NULL,
                 account TEXT, dir TEXT, text TEXT NOT NULL, st_name TEXT, st_type TEXT, st_id TEXT, st_from TEXT, st_to TEXT,
                 st_payload TEXT, error TEXT, UNIQUE(session_id, seq))""",
            "CREATE INDEX IF NOT EXISTS entries_device ON entries(install_id, id)",
            "CREATE INDEX IF NOT EXISTS entries_account ON entries(account, id)",
            "CREATE INDEX IF NOT EXISTS entries_received ON entries(received_at)",
        )
    }
}
