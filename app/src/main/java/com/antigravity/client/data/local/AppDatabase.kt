package com.antigravity.client.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "antigravity.db",
    null,
    3
) {
    private val _conversationDao = ConversationDaoImpl(this)
    private val _eventDao = EventDaoImpl(this)
    private val _stepDao = StepDaoImpl(this)
    private val _runDao = RunDaoImpl(this)
    private val _syncStateDao = SyncStateDaoImpl(this)
    private val _fileCacheDao = FileCacheDaoImpl(this)
    private val _attachmentDao = AttachmentDaoImpl(this)
    private val _outboxDao = OutboxDaoImpl(this)

    fun conversationDao(): ConversationDao = _conversationDao
    fun eventDao(): EventDao = _eventDao
    fun stepDao(): StepDao = _stepDao
    fun runDao(): RunDao = _runDao
    fun syncStateDao(): SyncStateDao = _syncStateDao
    fun fileCacheDao(): FileCacheDao = _fileCacheDao
    fun attachmentDao(): AttachmentDao = _attachmentDao
    fun outboxDao(): OutboxDao = _outboxDao

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS conversations (
                conversationId TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                preview TEXT NOT NULL,
                status TEXT NOT NULL,
                stepCount INTEGER NOT NULL,
                lastModified TEXT NOT NULL,
                workspace TEXT NOT NULL,
                parentConversationId TEXT,
                updatedAt INTEGER NOT NULL,
                isActive INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS events (
                seq INTEGER PRIMARY KEY NOT NULL,
                conversationId TEXT NOT NULL,
                runId TEXT NOT NULL,
                type TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                payloadJson TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_conv_seq ON events (conversationId, seq)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_runId ON events (runId)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS runs (
                runId TEXT PRIMARY KEY NOT NULL,
                conversationId TEXT NOT NULL,
                workspace TEXT NOT NULL,
                prompt TEXT NOT NULL,
                status TEXT NOT NULL,
                startedAt TEXT,
                finishedAt TEXT,
                error TEXT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS steps (
                conversationId TEXT NOT NULL,
                runId TEXT NOT NULL,
                stepIndex INTEGER NOT NULL,
                source TEXT NOT NULL,
                type TEXT NOT NULL,
                status TEXT NOT NULL,
                createdAt TEXT NOT NULL,
                thinking TEXT,
                content TEXT,
                userPrompt TEXT,
                toolCallsJson TEXT,
                diffsJson TEXT,
                attachmentsJson TEXT,
                error TEXT,
                PRIMARY KEY (conversationId, stepIndex)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_steps_conv_step ON steps (conversationId, stepIndex)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS file_cache (
                path TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                isDirectory INTEGER NOT NULL,
                isSymlink INTEGER NOT NULL,
                size INTEGER NOT NULL,
                lastModified TEXT NOT NULL,
                cachedContent TEXT,
                isBinary INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_state (
                key TEXT PRIMARY KEY NOT NULL,
                lastReceivedSeq INTEGER NOT NULL,
                serverUrl TEXT NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS attachments (
                id TEXT PRIMARY KEY NOT NULL,
                conversationId TEXT NOT NULL,
                messageId TEXT NOT NULL,
                type TEXT NOT NULL,
                fileName TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                size INTEGER NOT NULL,
                duration INTEGER,
                localUri TEXT,
                remoteUrl TEXT,
                serverId TEXT,
                uploadState TEXT NOT NULL,
                transcription TEXT,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_attachments_conv ON attachments (conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_attachments_msg ON attachments (messageId)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS outbox (
                id TEXT PRIMARY KEY NOT NULL,
                conversationId TEXT NOT NULL,
                text TEXT NOT NULL,
                attachmentIdsJson TEXT NOT NULL,
                state TEXT NOT NULL,
                retryCount INTEGER NOT NULL,
                lastError TEXT,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_outbox_conv ON outbox (conversationId)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE steps ADD COLUMN attachmentsJson TEXT")
            } catch (e: Exception) {}
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS attachments (
                    id TEXT PRIMARY KEY NOT NULL,
                    conversationId TEXT NOT NULL,
                    messageId TEXT NOT NULL,
                    type TEXT NOT NULL,
                    fileName TEXT NOT NULL,
                    mimeType TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    duration INTEGER,
                    localUri TEXT,
                    remoteUrl TEXT,
                    serverId TEXT,
                    uploadState TEXT NOT NULL,
                    transcription TEXT,
                    createdAt INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_attachments_conv ON attachments (conversationId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_attachments_msg ON attachments (messageId)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS outbox (
                    id TEXT PRIMARY KEY NOT NULL,
                    conversationId TEXT NOT NULL,
                    text TEXT NOT NULL,
                    attachmentIdsJson TEXT NOT NULL,
                    state TEXT NOT NULL,
                    retryCount INTEGER NOT NULL,
                    lastError TEXT,
                    createdAt INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_outbox_conv ON outbox (conversationId)")
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE conversations ADD COLUMN isActive INTEGER NOT NULL DEFAULT 0")
            } catch (e: Exception) {}
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
