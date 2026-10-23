package org.anikodev.candywrapper.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.builtins.ListSerializer
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.model.DirectRoute
import org.anikodev.candywrapper.core.model.EnvironmentKind
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.model.SavedEnvironment
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.store.CachedShell
import org.anikodev.candywrapper.core.store.CachedThread
import org.anikodev.candywrapper.core.store.LocalCache
import org.anikodev.candywrapper.core.store.ShellWrite

/**
 * The local cache in one SQLite file, WAL mode. Thread-list rows are plain columns so a cold
 * start reads them without decoding JSON; an open thread is one JSON blob per thread, the newest
 * [THREADS_KEPT] per environment. Secrets live in their own table, already encrypted by [SecretBox].
 *
 * Every method blocks; call them off the main thread. The schema is a cache: an upgrade drops it.
 */
class CacheDb(context: Context) : SQLiteOpenHelper(context, "cache.db", null, VERSION), LocalCache {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA synchronous = NORMAL", null).close()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE env (id TEXT PRIMARY KEY, label TEXT NOT NULL, kind TEXT NOT NULL, http_base TEXT NOT NULL, ws_base TEXT NOT NULL, direct_routes TEXT, added_at INTEGER NOT NULL, shell_sequence INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE project (env_id TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, workspace_root TEXT NOT NULL, PRIMARY KEY (env_id, id)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE thread (env_id TEXT NOT NULL, id TEXT NOT NULL, project_id TEXT NOT NULL, title TEXT NOT NULL, pill TEXT NOT NULL, unread INTEGER NOT NULL, section INTEGER NOT NULL, order_key TEXT, sort_time INTEGER NOT NULL, activity_at INTEGER NOT NULL, working_since INTEGER NOT NULL, updated_at INTEGER NOT NULL DEFAULT 0, visited_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (env_id, id)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE thread_cache (env_id TEXT NOT NULL, thread_id TEXT NOT NULL, data BLOB NOT NULL, saved_at INTEGER NOT NULL, PRIMARY KEY (env_id, thread_id)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE secret (name TEXT PRIMARY KEY, data BLOB NOT NULL) WITHOUT ROWID")
        db.execSQL("CREATE TABLE ui (key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1) {
            // Version 2 keeps the visit watermarks. Secrets and environments stay; the shell resumes from 0.
            db.execSQL("ALTER TABLE thread ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE thread ADD COLUMN visited_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE env SET shell_sequence = 0")
            return
        }
        for (t in listOf("env", "project", "thread", "thread_cache", "secret", "ui")) db.execSQL("DROP TABLE IF EXISTS $t")
        onCreate(db)
    }

    // Environments

    override fun environments(): List<SavedEnvironment> = readableDatabase.rawQuery(
        "SELECT id, label, kind, http_base, ws_base, direct_routes, added_at FROM env ORDER BY added_at", null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(SavedEnvironment(
                id = c.getString(0), label = c.getString(1),
                kind = runCatching { EnvironmentKind.valueOf(c.getString(2)) }.getOrDefault(EnvironmentKind.PAIRED),
                httpBase = c.getString(3), wsBase = c.getString(4),
                directRoutes = c.getString(5)?.let { runCatching { WireJson.decodeFromString(ROUTES, it) }.getOrNull() }.orEmpty(),
                addedAtMs = c.getLong(6),
            ))
        }
    }

    override fun saveEnvironment(env: SavedEnvironment) {
        val v = ContentValues().apply {
            put("id", env.id); put("label", env.label); put("kind", env.kind.name)
            put("http_base", env.httpBase); put("ws_base", env.wsBase)
            put("direct_routes", WireJson.encodeToString(ROUTES, env.directRoutes)); put("added_at", env.addedAtMs)
        }
        val db = writableDatabase
        if (db.update("env", v, "id = ?", arrayOf(env.id)) == 0) db.insert("env", null, v)
    }

    override fun removeEnvironment(id: String) = transaction {
        for (t in listOf("project", "thread", "thread_cache")) delete(t, "env_id = ?", arrayOf(id))
        delete("env", "id = ?", arrayOf(id))
    }

    // Shell

    override fun shell(envId: String): CachedShell? {
        val db = readableDatabase
        val sequence = db.rawQuery("SELECT shell_sequence FROM env WHERE id = ?", arrayOf(envId)).use { if (it.moveToFirst()) it.getLong(0) else return null }
        val projects = db.rawQuery("SELECT id, title, workspace_root FROM project WHERE env_id = ?", arrayOf(envId)).use { c ->
            buildList { while (c.moveToNext()) add(ProjectSummary(c.getString(0), c.getString(1), c.getString(2))) }
        }
        val threads = db.rawQuery(
            "SELECT id, project_id, title, pill, unread, section, order_key, sort_time, activity_at, working_since, updated_at, visited_at FROM thread WHERE env_id = ?", arrayOf(envId),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(ThreadSummary(
                    id = c.getString(0), projectId = c.getString(1), title = c.getString(2),
                    pill = runCatching { StatusPill.valueOf(c.getString(3)) }.getOrDefault(StatusPill.NONE),
                    unread = c.getInt(4) != 0, section = c.getInt(5), orderKey = c.getString(6),
                    sortTime = c.getLong(7), activityAt = c.getLong(8), workingSince = c.getLong(9),
                    updatedAt = c.getLong(10), visitedAt = c.getLong(11),
                ))
            }
        }
        return CachedShell(sequence, projects, threads)
    }

    override fun saveShell(envId: String, write: ShellWrite) = transaction {
        if (write.replaceAll) {
            delete("project", "env_id = ?", arrayOf(envId))
            delete("thread", "env_id = ?", arrayOf(envId))
        }
        for (id in write.removedProjects) delete("project", "env_id = ? AND id = ?", arrayOf(envId, id))
        for (id in write.removedThreads) delete("thread", "env_id = ? AND id = ?", arrayOf(envId, id))
        for (p in write.projects) insertWithOnConflict("project", null, ContentValues().apply {
            put("env_id", envId); put("id", p.id); put("title", p.title); put("workspace_root", p.workspaceRoot)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        for (t in write.threads) insertWithOnConflict("thread", null, ContentValues().apply {
            put("env_id", envId); put("id", t.id); put("project_id", t.projectId); put("title", t.title)
            put("pill", t.pill.name); put("unread", if (t.unread) 1 else 0); put("section", t.section)
            put("order_key", t.orderKey); put("sort_time", t.sortTime); put("activity_at", t.activityAt); put("working_since", t.workingSince)
            put("updated_at", t.updatedAt); put("visited_at", t.visitedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        update("env", ContentValues().apply { put("shell_sequence", write.sequence) }, "id = ?", arrayOf(envId))
    }

    // Threads

    override fun thread(envId: String, threadId: String): CachedThread? = readableDatabase.rawQuery(
        "SELECT data FROM thread_cache WHERE env_id = ? AND thread_id = ?", arrayOf(envId, threadId),
    ).use { c ->
        if (!c.moveToFirst()) return null
        runCatching { WireJson.decodeFromString(CachedThread.serializer(), c.getBlob(0).decodeToString()) }.getOrNull()
    }

    override fun saveThread(envId: String, threadId: String, thread: CachedThread) {
        val data = WireJson.encodeToString(CachedThread.serializer(), thread).encodeToByteArray()
        transaction {
            insertWithOnConflict("thread_cache", null, ContentValues().apply {
                put("env_id", envId); put("thread_id", threadId); put("data", data); put("saved_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            execSQL(
                "DELETE FROM thread_cache WHERE env_id = ? AND thread_id NOT IN (SELECT thread_id FROM thread_cache WHERE env_id = ? ORDER BY saved_at DESC LIMIT $THREADS_KEPT)",
                arrayOf(envId, envId),
            )
        }
    }

    // Secrets and UI state

    fun secret(name: String): ByteArray? =
        readableDatabase.rawQuery("SELECT data FROM secret WHERE name = ?", arrayOf(name)).use { if (it.moveToFirst()) it.getBlob(0) else null }

    fun putSecret(name: String, data: ByteArray?) {
        if (data == null) writableDatabase.delete("secret", "name = ?", arrayOf(name))
        else writableDatabase.insertWithOnConflict("secret", null, ContentValues().apply { put("name", name); put("data", data) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearSecrets() {
        writableDatabase.delete("secret", null, null)
    }

    fun uiValue(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM ui WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setUiValue(key: String, value: String?) {
        writableDatabase.insertWithOnConflict("ui", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private inline fun transaction(block: SQLiteDatabase.() -> Unit) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val VERSION = 2
        const val THREADS_KEPT = 40
        val ROUTES = ListSerializer(DirectRoute.serializer())
    }
}
