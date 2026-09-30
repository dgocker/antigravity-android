package com.antigravity.client.sync

import com.antigravity.client.data.local.*
import com.antigravity.client.domain.model.*
import com.antigravity.client.security.TokenStore
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject

class SyncEngine(
    private val database: AppDatabase,
    private val tokenStore: TokenStore,
    private val okHttpClient: OkHttpClient,
    var onSyncRequired: (suspend (String) -> Unit)? = null
) {
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val eventDao = database.eventDao()
    private val stepDao = database.stepDao()
    private val conversationDao = database.conversationDao()
    private val runDao = database.runDao()
    private val syncStateDao = database.syncStateDao()

    // In-memory accumulator for live text_delta per conversation
    private val _liveDeltas = MutableStateFlow<Map<String, String>>(emptyMap())
    val liveDeltas: StateFlow<Map<String, String>> = _liveDeltas.asStateFlow()

    // In-memory state for live agent activity (thinking, tool execution, generation) per conversation
    private val _liveActivity = MutableStateFlow<Map<String, LiveActivity>>(emptyMap())
    val liveActivity: StateFlow<Map<String, LiveActivity>> = _liveActivity.asStateFlow()

    private val webSocketManager = WebSocketManager(
        client = okHttpClient,
        tokenStore = tokenStore,
        onEventReceived = { json -> processEvent(json) }
    )

    val connectionState: StateFlow<ConnectionStatus> = webSocketManager.connectionState

    fun start() {
        webSocketManager.start()
    }

    fun stop() {
        webSocketManager.stop()
    }

    fun reconnect() {
        webSocketManager.reconnect()
    }

    suspend fun processEvent(json: JSONObject) {
        val type = json.optString("type")
        val seq = json.optLong("seq", -1L)
        val conversationId = json.optString("conversation_id")
        val runId = json.optString("run_id")
        val ts = json.optString("ts", "")

        // 1. If seq is present, persist event with deduplication & update seq pointer
        if (seq > 0L) {
            val eventEntity = EventEntity(
                seq = seq,
                conversationId = conversationId,
                runId = runId,
                type = type,
                timestamp = ts,
                payloadJson = json.opt("payload")?.toString() ?: ""
            )

            // Room insert returns -1 if ignored by UNIQUE seq conflict
            val rowId = eventDao.insert(eventEntity)
            if (rowId != -1L) {
                if (seq > tokenStore.lastReceivedSeq) {
                    tokenStore.lastReceivedSeq = seq
                    syncStateDao.updateLastSeq(seq)
                }
            }
        }

        // 2. Process event semantics
        when (type) {
            "agent_activity" -> {
                val act = json.optString("activity", "idle")
                val toolName = json.optString("tool_name").takeIf { it.isNotBlank() }
                val detail = json.optString("detail", "")
                val paramsObj = json.optJSONObject("parameters")
                val paramsMap = mutableMapOf<String, Any?>()
                if (paramsObj != null) {
                    val keys = paramsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        paramsMap[k] = paramsObj.opt(k)
                    }
                }
                val output = json.optString("output").takeIf { it.isNotBlank() }
                val dur = if (json.has("duration_seconds")) json.optDouble("duration_seconds") else null

                if (conversationId.isNotBlank()) {
                    if (act == "idle") {
                        _liveActivity.value = _liveActivity.value - conversationId
                    } else {
                        val activityInfo = LiveActivity(
                            activity = act,
                            toolName = toolName,
                            detail = detail,
                            parameters = paramsMap,
                            output = output,
                            durationSeconds = dur
                        )
                        _liveActivity.value = _liveActivity.value + (conversationId to activityInfo)
                    }
                }
            }

            "text_delta" -> {
                val delta = json.optString("text_delta")
                if (conversationId.isNotBlank() && delta.isNotEmpty()) {
                    val current = _liveDeltas.value[conversationId] ?: ""
                    _liveDeltas.value = _liveDeltas.value + (conversationId to (current + delta))
                }
            }

            "step" -> {
                val payloadObj = json.optJSONObject("payload")
                if (payloadObj != null) {
                    val stepIndex = payloadObj.optInt("step_index", 0)
                    val attsArray = payloadObj.optJSONArray("attachments")
                    val attachmentsJsonStr = if (attsArray != null && attsArray.length() > 0) {
                        val list = mutableListOf<Attachment>()
                        for (i in 0 until attsArray.length()) {
                            val aObj = attsArray.optJSONObject(i) ?: continue
                            val aid = aObj.optString("id")
                            val atypeStr = aObj.optString("type", "other")
                            val atype = when (atypeStr.lowercase()) {
                                "image" -> AttachmentType.IMAGE
                                "video" -> AttachmentType.VIDEO
                                "audio" -> AttachmentType.AUDIO
                                "document" -> AttachmentType.DOCUMENT
                                else -> AttachmentType.OTHER
                            }
                            list.add(
                                Attachment(
                                    id = aid,
                                    conversationId = conversationId,
                                    type = atype,
                                    fileName = aObj.optString("file_name", aid.substringAfterLast('/')),
                                    mimeType = aObj.optString("mime_type", "application/octet-stream"),
                                    size = aObj.optLong("size", 0L),
                                    duration = if (aObj.has("duration") && !aObj.isNull("duration")) aObj.optInt("duration") else null,
                                    remoteUrl = aObj.optString("server_path").takeIf { it.isNotBlank() },
                                    serverId = aid,
                                    transcription = aObj.optString("transcription").takeIf { it.isNotBlank() && it != "null" },
                                    uploadState = AttachmentUploadState.COMPLETED
                                )
                            )
                        }
                        gson.toJson(list)
                    } else {
                        null
                    }

                    val step = StepEntity(
                        conversationId = conversationId,
                        runId = runId,
                        stepIndex = stepIndex,
                        source = payloadObj.optString("source", "MODEL"),
                        type = payloadObj.optString("type", "GENERIC"),
                        status = payloadObj.optString("status", "DONE"),
                        createdAt = payloadObj.optString("created_at", ts),
                        thinking = payloadObj.optString("thinking").takeIf { it.isNotBlank() && it != "null" },
                        content = payloadObj.optString("content").takeIf { it.isNotBlank() && it != "null" },
                        userPrompt = payloadObj.optString("user_prompt").takeIf { it.isNotBlank() && it != "null" },
                        toolCallsJson = payloadObj.optJSONArray("tool_calls")?.toString(),
                        diffsJson = payloadObj.optJSONArray("diffs")?.toString(),
                        attachmentsJson = attachmentsJsonStr,
                        error = payloadObj.optString("error").takeIf { it.isNotBlank() && it != "null" }
                    )
                    stepDao.insertOrUpdate(step)
                }
            }

            "run_started" -> {
                if (runId.isNotBlank()) {
                    runDao.insertOrUpdate(
                        RunEntity(
                            runId = runId,
                            conversationId = conversationId,
                            workspace = "",
                            prompt = "",
                            status = "running",
                            startedAt = ts
                        )
                    )
                }
                if (conversationId.isNotBlank()) {
                    conversationDao.updateStatus(conversationId, "CASCADE_RUN_STATUS_RUNNING")
                }
            }

            "run_finished" -> {
                if (runId.isNotBlank()) {
                    runDao.updateRunStatus(runId, "completed", finishedAt = ts)
                }
                if (conversationId.isNotBlank()) {
                    conversationDao.updateStatus(conversationId, "CASCADE_RUN_STATUS_IDLE")
                    _liveDeltas.value = _liveDeltas.value - conversationId
                    _liveActivity.value = _liveActivity.value - conversationId
                    scope.launch { onSyncRequired?.invoke(conversationId) }
                }
            }

            "run_cancelled" -> {
                if (runId.isNotBlank()) {
                    runDao.updateRunStatus(runId, "cancelled", finishedAt = ts)
                }
                if (conversationId.isNotBlank()) {
                    conversationDao.updateStatus(conversationId, "CASCADE_RUN_STATUS_IDLE")
                    _liveDeltas.value = _liveDeltas.value - conversationId
                    _liveActivity.value = _liveActivity.value - conversationId
                    scope.launch { onSyncRequired?.invoke(conversationId) }
                }
            }

            "run_error" -> {
                val errorMsg = json.optJSONObject("payload")?.optString("error") ?: "Run error"
                if (runId.isNotBlank()) {
                    runDao.updateRunStatus(runId, "error", finishedAt = ts, error = errorMsg)
                }
                if (conversationId.isNotBlank()) {
                    conversationDao.updateStatus(conversationId, "CASCADE_RUN_STATUS_IDLE")
                    _liveDeltas.value = _liveDeltas.value - conversationId
                    _liveActivity.value = _liveActivity.value - conversationId
                    scope.launch { onSyncRequired?.invoke(conversationId) }
                }
            }
        }
    }
}
