package com.antigravity.client.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// --- Interfaces ---

interface ConversationDao {
    suspend fun insertOrUpdate(conversation: ConversationEntity)
    suspend fun insertAll(conversations: List<ConversationEntity>)
    fun getAllConversations(): Flow<List<ConversationEntity>>
    fun getConversation(id: String): Flow<ConversationEntity?>
    fun searchConversations(query: String): Flow<List<ConversationEntity>>
    suspend fun updateStatus(id: String, status: String)
    suspend fun updateTitle(id: String, title: String)
    suspend fun updateActiveStatus(id: String, isActive: Boolean)
    suspend fun delete(id: String)
}

interface EventDao {
    suspend fun insert(event: EventEntity): Long
    suspend fun insertAll(events: List<EventEntity>): List<Long>
    suspend fun getEventsAfter(afterSeq: Long): List<EventEntity>
    fun getEventsForConversation(conversationId: String): Flow<List<EventEntity>>
    suspend fun getLatestSeq(): Long?
    suspend fun countBySeq(seq: Long): Int
}

interface StepDao {
    suspend fun insertOrUpdate(step: StepEntity)
    suspend fun insertAll(steps: List<StepEntity>)
    fun getStepsForConversation(conversationId: String): Flow<List<StepEntity>>
    suspend fun getStep(conversationId: String, stepIndex: Int): StepEntity?
    suspend fun getMaxStepIndex(conversationId: String): Int?
    suspend fun getMinStepIndex(conversationId: String): Int?
    suspend fun deleteForConversation(conversationId: String)
    suspend fun deleteCheckpointSteps(conversationId: String)
}

interface RunDao {
    suspend fun insertOrUpdate(run: RunEntity)
    fun getRun(runId: String): Flow<RunEntity?>
    fun getRunsForConversation(conversationId: String): Flow<List<RunEntity>>
    suspend fun updateRunStatus(runId: String, status: String, finishedAt: String? = null, error: String? = null)
}

interface SyncStateDao {
    suspend fun getSyncState(key: String = "global_sync"): SyncStateEntity?
    suspend fun insertOrUpdate(state: SyncStateEntity)
    suspend fun updateLastSeq(seq: Long, key: String = "global_sync", updatedAt: Long = System.currentTimeMillis())
}

interface FileCacheDao {
    suspend fun getFile(path: String): FileCacheEntity?
    suspend fun insertOrUpdate(file: FileCacheEntity)
    suspend fun delete(path: String)
}

interface AttachmentDao {
    suspend fun insertOrUpdate(attachment: AttachmentEntity)
    suspend fun insertAll(attachments: List<AttachmentEntity>)
    fun getAttachmentsForConversation(conversationId: String): Flow<List<AttachmentEntity>>
    fun getAttachmentsForMessage(messageId: String): Flow<List<AttachmentEntity>>
    suspend fun getAttachment(id: String): AttachmentEntity?
    suspend fun updateUploadState(id: String, state: String, remoteUrl: String? = null, serverId: String? = null)
    suspend fun updateTranscription(id: String, transcription: String)
    suspend fun delete(id: String)
}

interface OutboxDao {
    suspend fun insert(item: OutboxEntity)
    fun getPendingItems(): Flow<List<OutboxEntity>>
    fun getItemsForConversation(conversationId: String): Flow<List<OutboxEntity>>
    suspend fun updateState(id: String, state: String, error: String? = null)
    suspend fun incrementRetry(id: String, error: String? = null)
    suspend fun delete(id: String)
}

// --- Implementations ---

class ConversationDaoImpl(private val dbHelper: AppDatabase) : ConversationDao {
    private val notifier = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
        tryEmit(Unit)
    }

    private fun notifyChange() {
        notifier.tryEmit(Unit)
    }

    override suspend fun insertOrUpdate(conversation: ConversationEntity) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("conversationId", conversation.conversationId)
            put("title", conversation.title)
            put("preview", conversation.preview)
            put("status", conversation.status)
            put("stepCount", conversation.stepCount)
            put("lastModified", conversation.lastModified)
            put("workspace", conversation.workspace)
            put("parentConversationId", conversation.parentConversationId)
            put("updatedAt", conversation.updatedAt)
            put("isActive", if (conversation.isActive) 1 else 0)
        }
        db.insertWithOnConflict("conversations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        notifyChange()
    }

    override suspend fun insertAll(conversations: List<ConversationEntity>) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (conversation in conversations) {
                val cv = ContentValues().apply {
                    put("conversationId", conversation.conversationId)
                    put("title", conversation.title)
                    put("preview", conversation.preview)
                    put("status", conversation.status)
                    put("stepCount", conversation.stepCount)
                    put("lastModified", conversation.lastModified)
                    put("workspace", conversation.workspace)
                    put("parentConversationId", conversation.parentConversationId)
                    put("updatedAt", conversation.updatedAt)
                    put("isActive", if (conversation.isActive) 1 else 0)
                }
                db.insertWithOnConflict("conversations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChange()
    }

    override fun getAllConversations(): Flow<List<ConversationEntity>> = notifier.map {
        withContext(Dispatchers.IO) {
            val list = mutableListOf<ConversationEntity>()
            val db = dbHelper.readableDatabase
            db.rawQuery("SELECT * FROM conversations ORDER BY lastModified DESC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursor.toConversationEntity())
                }
            }
            list
        }
    }.flowOn(Dispatchers.IO)

    override fun getConversation(id: String): Flow<ConversationEntity?> = notifier.map {
        withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            db.rawQuery("SELECT * FROM conversations WHERE conversationId = ? LIMIT 1", arrayOf(id)).use { cursor ->
                if (cursor.moveToNext()) {
                    cursor.toConversationEntity()
                } else null
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun searchConversations(query: String): Flow<List<ConversationEntity>> = notifier.map {
        withContext(Dispatchers.IO) {
            val list = mutableListOf<ConversationEntity>()
            val db = dbHelper.readableDatabase
            val wild = "%$query%"
            db.rawQuery(
                "SELECT * FROM conversations WHERE title LIKE ? OR preview LIKE ? ORDER BY lastModified DESC",
                arrayOf(wild, wild)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursor.toConversationEntity())
                }
            }
            list
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun updateStatus(id: String, status: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("status", status)
        }
        db.update("conversations", cv, "conversationId = ?", arrayOf(id))
        notifyChange()
    }

    override suspend fun updateTitle(id: String, title: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("title", title)
            put("updatedAt", System.currentTimeMillis())
        }
        db.update("conversations", cv, "conversationId = ?", arrayOf(id))
        notifyChange()
    }

    override suspend fun updateActiveStatus(id: String, isActive: Boolean) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("isActive", if (isActive) 1 else 0)
        }
        db.update("conversations", cv, "conversationId = ?", arrayOf(id))
        notifyChange()
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete("conversations", "conversationId = ?", arrayOf(id))
        notifyChange()
    }

    private fun Cursor.toConversationEntity(): ConversationEntity {
        val actIdx = getColumnIndex("isActive")
        val isAct = if (actIdx >= 0 && !isNull(actIdx)) getInt(actIdx) == 1 else false
        return ConversationEntity(
            conversationId = getString(getColumnIndexOrThrow("conversationId")),
            title = getString(getColumnIndexOrThrow("title")),
            preview = getString(getColumnIndexOrThrow("preview")),
            status = getString(getColumnIndexOrThrow("status")),
            stepCount = getInt(getColumnIndexOrThrow("stepCount")),
            lastModified = getString(getColumnIndexOrThrow("lastModified")),
            workspace = getString(getColumnIndexOrThrow("workspace")),
            parentConversationId = if (isNull(getColumnIndexOrThrow("parentConversationId"))) null else getString(getColumnIndexOrThrow("parentConversationId")),
            updatedAt = getLong(getColumnIndexOrThrow("updatedAt")),
            isActive = isAct
        )
    }
}

class EventDaoImpl(private val dbHelper: AppDatabase) : EventDao {
    private val notifier = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
        tryEmit(Unit)
    }

    private fun notifyChange() {
        notifier.tryEmit(Unit)
    }

    override suspend fun insert(event: EventEntity): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("seq", event.seq)
            put("conversationId", event.conversationId)
            put("runId", event.runId)
            put("type", event.type)
            put("timestamp", event.timestamp)
            put("payloadJson", event.payloadJson)
        }
        val rowId = db.insertWithOnConflict("events", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (rowId != -1L) {
            notifyChange()
        }
        rowId
    }

    override suspend fun insertAll(events: List<EventEntity>): List<Long> = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val rowIds = mutableListOf<Long>()
        db.beginTransaction()
        try {
            for (event in events) {
                val cv = ContentValues().apply {
                    put("seq", event.seq)
                    put("conversationId", event.conversationId)
                    put("runId", event.runId)
                    put("type", event.type)
                    put("timestamp", event.timestamp)
                    put("payloadJson", event.payloadJson)
                }
                val rowId = db.insertWithOnConflict("events", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                rowIds.add(rowId)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (rowIds.any { it != -1L }) {
            notifyChange()
        }
        rowIds
    }

    override suspend fun getEventsAfter(afterSeq: Long): List<EventEntity> = withContext(Dispatchers.IO) {
        val list = mutableListOf<EventEntity>()
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT * FROM events WHERE seq > ? ORDER BY seq ASC",
            arrayOf(afterSeq.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toEventEntity())
            }
        }
        list
    }

    override fun getEventsForConversation(conversationId: String): Flow<List<EventEntity>> = notifier.map {
        withContext(Dispatchers.IO) {
            val list = mutableListOf<EventEntity>()
            val db = dbHelper.readableDatabase
            db.rawQuery(
                "SELECT * FROM events WHERE conversationId = ? ORDER BY seq ASC",
                arrayOf(conversationId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursor.toEventEntity())
                }
            }
            list
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getLatestSeq(): Long? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT MAX(seq) FROM events", null).use { cursor ->
            if (cursor.moveToNext() && !cursor.isNull(0)) {
                cursor.getLong(0)
            } else null
        }
    }

    override suspend fun countBySeq(seq: Long): Int = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM events WHERE seq = ?", arrayOf(seq.toString())).use { cursor ->
            if (cursor.moveToNext()) cursor.getInt(0) else 0
        }
    }

    private fun Cursor.toEventEntity(): EventEntity {
        return EventEntity(
            seq = getLong(getColumnIndexOrThrow("seq")),
            conversationId = getString(getColumnIndexOrThrow("conversationId")),
            runId = getString(getColumnIndexOrThrow("runId")),
            type = getString(getColumnIndexOrThrow("type")),
            timestamp = getString(getColumnIndexOrThrow("timestamp")),
            payloadJson = getString(getColumnIndexOrThrow("payloadJson"))
        )
    }
}

class StepDaoImpl(private val dbHelper: AppDatabase) : StepDao {
    private val notifier = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
        tryEmit(Unit)
    }

    private fun notifyChange() {
        notifier.tryEmit(Unit)
    }

    override suspend fun insertOrUpdate(step: StepEntity) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.insertWithOnConflict("steps", null, step.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE)
        notifyChange()
    }

    override suspend fun insertAll(steps: List<StepEntity>) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (step in steps) {
                db.insertWithOnConflict("steps", null, step.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChange()
    }

    override fun getStepsForConversation(conversationId: String): Flow<List<StepEntity>> = notifier.map {
        withContext(Dispatchers.IO) {
            val list = mutableListOf<StepEntity>()
            val db = dbHelper.readableDatabase
            db.rawQuery(
                "SELECT * FROM steps WHERE conversationId = ? ORDER BY stepIndex ASC",
                arrayOf(conversationId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursor.toStepEntity())
                }
            }
            list
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getStep(conversationId: String, stepIndex: Int): StepEntity? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT * FROM steps WHERE conversationId = ? AND stepIndex = ? LIMIT 1",
            arrayOf(conversationId, stepIndex.toString())
        ).use { cursor ->
            if (cursor.moveToNext()) {
                cursor.toStepEntity()
            } else null
        }
    }

    override suspend fun getMaxStepIndex(conversationId: String): Int? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT MAX(stepIndex) FROM steps WHERE conversationId = ?",
            arrayOf(conversationId)
        ).use { cursor ->
            if (cursor.moveToNext() && !cursor.isNull(0)) {
                cursor.getInt(0)
            } else null
        }
    }

    override suspend fun getMinStepIndex(conversationId: String): Int? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT MIN(stepIndex) FROM steps WHERE conversationId = ?",
            arrayOf(conversationId)
        ).use { cursor ->
            if (cursor.moveToNext() && !cursor.isNull(0)) {
                cursor.getInt(0)
            } else null
        }
    }

    override suspend fun deleteForConversation(conversationId: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete("steps", "conversationId = ?", arrayOf(conversationId))
        notifyChange()
    }

    override suspend fun deleteCheckpointSteps(conversationId: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val deleted = db.delete(
            "steps",
            "conversationId = ? AND (type = 'CHECKPOINT' OR content LIKE '%# Resuming from a compaction%' OR content LIKE '%<CONTEXT_SUMMARY>%')",
            arrayOf(conversationId)
        )
        if (deleted > 0) {
            notifyChange()
        }
    }

    private fun StepEntity.toContentValues(): ContentValues {
        return ContentValues().apply {
            put("conversationId", conversationId)
            put("runId", runId)
            put("stepIndex", stepIndex)
            put("source", source)
            put("type", type)
            put("status", status)
            put("createdAt", createdAt)
            put("thinking", thinking)
            put("content", content)
            put("userPrompt", userPrompt)
            put("toolCallsJson", toolCallsJson)
            put("diffsJson", diffsJson)
            put("attachmentsJson", attachmentsJson)
            put("error", error)
        }
    }

    private fun Cursor.toStepEntity(): StepEntity {
        val attachmentsIdx = getColumnIndex("attachmentsJson")
        return StepEntity(
            conversationId = getString(getColumnIndexOrThrow("conversationId")),
            runId = getString(getColumnIndexOrThrow("runId")),
            stepIndex = getInt(getColumnIndexOrThrow("stepIndex")),
            source = getString(getColumnIndexOrThrow("source")),
            type = getString(getColumnIndexOrThrow("type")),
            status = getString(getColumnIndexOrThrow("status")),
            createdAt = getString(getColumnIndexOrThrow("createdAt")),
            thinking = if (isNull(getColumnIndexOrThrow("thinking"))) null else getString(getColumnIndexOrThrow("thinking")),
            content = if (isNull(getColumnIndexOrThrow("content"))) null else getString(getColumnIndexOrThrow("content")),
            userPrompt = if (isNull(getColumnIndexOrThrow("userPrompt"))) null else getString(getColumnIndexOrThrow("userPrompt")),
            toolCallsJson = if (isNull(getColumnIndexOrThrow("toolCallsJson"))) null else getString(getColumnIndexOrThrow("toolCallsJson")),
            diffsJson = if (isNull(getColumnIndexOrThrow("diffsJson"))) null else getString(getColumnIndexOrThrow("diffsJson")),
            attachmentsJson = if (attachmentsIdx >= 0 && !isNull(attachmentsIdx)) getString(attachmentsIdx) else null,
            error = if (isNull(getColumnIndexOrThrow("error"))) null else getString(getColumnIndexOrThrow("error"))
        )
    }
}

class RunDaoImpl(private val dbHelper: AppDatabase) : RunDao {
    private val notifier = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
        tryEmit(Unit)
    }

    private fun notifyChange() {
        notifier.tryEmit(Unit)
    }

    override suspend fun insertOrUpdate(run: RunEntity) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("runId", run.runId)
            put("conversationId", run.conversationId)
            put("workspace", run.workspace)
            put("prompt", run.prompt)
            put("status", run.status)
            put("startedAt", run.startedAt)
            put("finishedAt", run.finishedAt)
            put("error", run.error)
        }
        db.insertWithOnConflict("runs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        notifyChange()
    }

    override fun getRun(runId: String): Flow<RunEntity?> = notifier.map {
        withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            db.rawQuery("SELECT * FROM runs WHERE runId = ? LIMIT 1", arrayOf(runId)).use { cursor ->
                if (cursor.moveToNext()) {
                    cursor.toRunEntity()
                } else null
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun getRunsForConversation(conversationId: String): Flow<List<RunEntity>> = notifier.map {
        withContext(Dispatchers.IO) {
            val list = mutableListOf<RunEntity>()
            val db = dbHelper.readableDatabase
            db.rawQuery(
                "SELECT * FROM runs WHERE conversationId = ? ORDER BY startedAt DESC",
                arrayOf(conversationId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursor.toRunEntity())
                }
            }
            list
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun updateRunStatus(runId: String, status: String, finishedAt: String?, error: String?) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("status", status)
            if (finishedAt != null) put("finishedAt", finishedAt)
            if (error != null) put("error", error)
        }
        db.update("runs", cv, "runId = ?", arrayOf(runId))
        notifyChange()
    }

    private fun Cursor.toRunEntity(): RunEntity {
        return RunEntity(
            runId = getString(getColumnIndexOrThrow("runId")),
            conversationId = getString(getColumnIndexOrThrow("conversationId")),
            workspace = getString(getColumnIndexOrThrow("workspace")),
            prompt = getString(getColumnIndexOrThrow("prompt")),
            status = getString(getColumnIndexOrThrow("status")),
            startedAt = if (isNull(getColumnIndexOrThrow("startedAt"))) null else getString(getColumnIndexOrThrow("startedAt")),
            finishedAt = if (isNull(getColumnIndexOrThrow("finishedAt"))) null else getString(getColumnIndexOrThrow("finishedAt")),
            error = if (isNull(getColumnIndexOrThrow("error"))) null else getString(getColumnIndexOrThrow("error"))
        )
    }
}

class SyncStateDaoImpl(private val dbHelper: AppDatabase) : SyncStateDao {
    override suspend fun getSyncState(key: String): SyncStateEntity? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM sync_state WHERE key = ? LIMIT 1", arrayOf(key)).use { cursor ->
            if (cursor.moveToNext()) {
                SyncStateEntity(
                    key = cursor.getString(cursor.getColumnIndexOrThrow("key")),
                    lastReceivedSeq = cursor.getLong(cursor.getColumnIndexOrThrow("lastReceivedSeq")),
                    serverUrl = cursor.getString(cursor.getColumnIndexOrThrow("serverUrl")),
                    updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt"))
                )
            } else null
        }
    }

    override suspend fun insertOrUpdate(state: SyncStateEntity) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("key", state.key)
            put("lastReceivedSeq", state.lastReceivedSeq)
            put("serverUrl", state.serverUrl)
            put("updatedAt", state.updatedAt)
        }
        db.insertWithOnConflict("sync_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override suspend fun updateLastSeq(seq: Long, key: String, updatedAt: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("lastReceivedSeq", seq)
            put("updatedAt", updatedAt)
        }
        val rows = db.update("sync_state", cv, "key = ?", arrayOf(key))
        if (rows == 0) {
            cv.put("key", key)
            cv.put("serverUrl", "")
            db.insertWithOnConflict("sync_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
        Unit
    }
}

class FileCacheDaoImpl(private val dbHelper: AppDatabase) : FileCacheDao {
    override suspend fun getFile(path: String): FileCacheEntity? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM file_cache WHERE path = ? LIMIT 1", arrayOf(path)).use { cursor ->
            if (cursor.moveToNext()) {
                FileCacheEntity(
                    path = cursor.getString(cursor.getColumnIndexOrThrow("path")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    isDirectory = cursor.getInt(cursor.getColumnIndexOrThrow("isDirectory")) == 1,
                    isSymlink = cursor.getInt(cursor.getColumnIndexOrThrow("isSymlink")) == 1,
                    size = cursor.getLong(cursor.getColumnIndexOrThrow("size")),
                    lastModified = cursor.getString(cursor.getColumnIndexOrThrow("lastModified")),
                    cachedContent = if (cursor.isNull(cursor.getColumnIndexOrThrow("cachedContent"))) null else cursor.getString(cursor.getColumnIndexOrThrow("cachedContent")),
                    isBinary = cursor.getInt(cursor.getColumnIndexOrThrow("isBinary")) == 1
                )
            } else null
        }
    }

    override suspend fun insertOrUpdate(file: FileCacheEntity) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("path", file.path)
            put("name", file.name)
            put("isDirectory", if (file.isDirectory) 1 else 0)
            put("isSymlink", if (file.isSymlink) 1 else 0)
            put("size", file.size)
            put("lastModified", file.lastModified)
            put("cachedContent", file.cachedContent)
            put("isBinary", if (file.isBinary) 1 else 0)
        }
        db.insertWithOnConflict("file_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete("file_cache", "path = ?", arrayOf(path))
        Unit
    }
}

class AttachmentDaoImpl(private val dbHelper: AppDatabase) : AttachmentDao {
    private val notifier = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
        tryEmit(Unit)
    }

    private fun notifyChange() {
        notifier.tryEmit(Unit)
    }

    override suspend fun insertOrUpdate(attachment: AttachmentEntity) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.insertWithOnConflict("attachments", null, attachment.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE)
        notifyChange()
    }

    override suspend fun insertAll(attachments: List<AttachmentEntity>) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (att in attachments) {
                db.insertWithOnConflict("attachments", null, att.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChange()
    }

    override fun getAttachmentsForConversation(conversationId: String): Flow<List<AttachmentEntity>> = notifier.map {
        withContext(Dispatchers.IO) {
            val list = mutableListOf<AttachmentEntity>()
            val db = dbHelper.readableDatabase
            db.rawQuery("SELECT * FROM attachments WHERE conversationId = ? ORDER BY createdAt ASC", arrayOf(conversationId)).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursor.toAttachmentEntity())
                }
            }
            list
        }
    }.flowOn(Dispatchers.IO)

    override fun getAttachmentsForMessage(messageId: String): Flow<List<AttachmentEntity>> = notifier.map {
        withContext(Dispatchers.IO) {
            val list = mutableListOf<AttachmentEntity>()
            val db = dbHelper.readableDatabase
            db.rawQuery("SELECT * FROM attachments WHERE messageId = ? ORDER BY createdAt ASC", arrayOf(messageId)).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursor.toAttachmentEntity())
                }
            }
            list
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getAttachment(id: String): AttachmentEntity? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM attachments WHERE id = ? LIMIT 1", arrayOf(id)).use { cursor ->
            if (cursor.moveToNext()) cursor.toAttachmentEntity() else null
        }
    }

    override suspend fun updateUploadState(id: String, state: String, remoteUrl: String?, serverId: String?) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("uploadState", state)
            if (remoteUrl != null) put("remoteUrl", remoteUrl)
            if (serverId != null) put("serverId", serverId)
        }
        db.update("attachments", cv, "id = ?", arrayOf(id))
        notifyChange()
    }

    override suspend fun updateTranscription(id: String, transcription: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("transcription", transcription)
        }
        db.update("attachments", cv, "id = ? OR serverId = ? OR remoteUrl = ?", arrayOf(id, id, id))
        notifyChange()
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete("attachments", "id = ?", arrayOf(id))
        notifyChange()
    }

    private fun AttachmentEntity.toContentValues(): ContentValues {
        return ContentValues().apply {
            put("id", id)
            put("conversationId", conversationId)
            put("messageId", messageId)
            put("type", type)
            put("fileName", fileName)
            put("mimeType", mimeType)
            put("size", size)
            put("duration", duration)
            put("localUri", localUri)
            put("remoteUrl", remoteUrl)
            put("serverId", serverId)
            put("uploadState", uploadState)
            put("transcription", transcription)
            put("createdAt", createdAt)
        }
    }

    private fun Cursor.toAttachmentEntity(): AttachmentEntity {
        return AttachmentEntity(
            id = getString(getColumnIndexOrThrow("id")),
            conversationId = getString(getColumnIndexOrThrow("conversationId")),
            messageId = getString(getColumnIndexOrThrow("messageId")),
            type = getString(getColumnIndexOrThrow("type")),
            fileName = getString(getColumnIndexOrThrow("fileName")),
            mimeType = getString(getColumnIndexOrThrow("mimeType")),
            size = getLong(getColumnIndexOrThrow("size")),
            duration = if (isNull(getColumnIndexOrThrow("duration"))) null else getInt(getColumnIndexOrThrow("duration")),
            localUri = if (isNull(getColumnIndexOrThrow("localUri"))) null else getString(getColumnIndexOrThrow("localUri")),
            remoteUrl = if (isNull(getColumnIndexOrThrow("remoteUrl"))) null else getString(getColumnIndexOrThrow("remoteUrl")),
            serverId = if (isNull(getColumnIndexOrThrow("serverId"))) null else getString(getColumnIndexOrThrow("serverId")),
            uploadState = getString(getColumnIndexOrThrow("uploadState")),
            transcription = if (isNull(getColumnIndexOrThrow("transcription"))) null else getString(getColumnIndexOrThrow("transcription")),
            createdAt = getLong(getColumnIndexOrThrow("createdAt"))
        )
    }
}

class OutboxDaoImpl(private val dbHelper: AppDatabase) : OutboxDao {
    private val notifier = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
        tryEmit(Unit)
    }

    private fun notifyChange() {
        notifier.tryEmit(Unit)
    }

    override suspend fun insert(item: OutboxEntity) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("id", item.id)
            put("conversationId", item.conversationId)
            put("text", item.text)
            put("attachmentIdsJson", item.attachmentIdsJson)
            put("state", item.state)
            put("retryCount", item.retryCount)
            put("lastError", item.lastError)
            put("createdAt", item.createdAt)
        }
        db.insertWithOnConflict("outbox", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        notifyChange()
    }

    override fun getPendingItems(): Flow<List<OutboxEntity>> = notifier.map {
        withContext(Dispatchers.IO) {
            val list = mutableListOf<OutboxEntity>()
            val db = dbHelper.readableDatabase
            db.rawQuery("SELECT * FROM outbox WHERE state != 'SENT' ORDER BY createdAt ASC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursor.toOutboxEntity())
                }
            }
            list
        }
    }.flowOn(Dispatchers.IO)

    override fun getItemsForConversation(conversationId: String): Flow<List<OutboxEntity>> = notifier.map {
        withContext(Dispatchers.IO) {
            val list = mutableListOf<OutboxEntity>()
            val db = dbHelper.readableDatabase
            db.rawQuery("SELECT * FROM outbox WHERE conversationId = ? ORDER BY createdAt ASC", arrayOf(conversationId)).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursor.toOutboxEntity())
                }
            }
            list
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun updateState(id: String, state: String, error: String?) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("state", state)
            if (error != null) put("lastError", error)
        }
        db.update("outbox", cv, "id = ?", arrayOf(id))
        notifyChange()
    }

    override suspend fun incrementRetry(id: String, error: String?) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.execSQL("UPDATE outbox SET retryCount = retryCount + 1, lastError = ?, state = 'FAILED' WHERE id = ?", arrayOf(error ?: "Send failed", id))
        notifyChange()
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete("outbox", "id = ?", arrayOf(id))
        notifyChange()
    }

    private fun Cursor.toOutboxEntity(): OutboxEntity {
        return OutboxEntity(
            id = getString(getColumnIndexOrThrow("id")),
            conversationId = getString(getColumnIndexOrThrow("conversationId")),
            text = getString(getColumnIndexOrThrow("text")),
            attachmentIdsJson = getString(getColumnIndexOrThrow("attachmentIdsJson")),
            state = getString(getColumnIndexOrThrow("state")),
            retryCount = getInt(getColumnIndexOrThrow("retryCount")),
            lastError = if (isNull(getColumnIndexOrThrow("lastError"))) null else getString(getColumnIndexOrThrow("lastError")),
            createdAt = getLong(getColumnIndexOrThrow("createdAt"))
        )
    }
}
