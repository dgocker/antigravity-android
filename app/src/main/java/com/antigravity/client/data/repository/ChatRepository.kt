package com.antigravity.client.data.repository

import com.antigravity.client.data.local.*
import com.antigravity.client.data.remote.GatewayApi
import com.antigravity.client.data.remote.dto.*
import com.antigravity.client.domain.model.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepository(
    private val api: GatewayApi,
    private val database: AppDatabase,
    private val gson: Gson = Gson()
) {
    private val conversationDao = database.conversationDao()
    private val stepDao = database.stepDao()
    private val runDao = database.runDao()
    private val eventDao = database.eventDao()

    fun getConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllConversations().map { list ->
            list.map { it.toDomain() }
        }
    }

    fun getConversation(id: String): Flow<Conversation?> {
        return conversationDao.getConversation(id).map { it?.toDomain() }
    }

    fun searchConversations(query: String): Flow<List<Conversation>> {
        return conversationDao.searchConversations(query).map { list ->
            list.map { it.toDomain() }
        }
    }

    fun getSteps(conversationId: String): Flow<List<Step>> {
        return stepDao.getStepsForConversation(conversationId).map { list ->
            list.map { it.toDomain(gson) }
        }
    }

    fun getRun(runId: String): Flow<RunEntity?> {
        return runDao.getRun(runId)
    }

    suspend fun refreshConversations() {
        val dtos = api.getChats()
        val entities = dtos.map { dto ->
            ConversationEntity(
                conversationId = dto.id,
                title = dto.title,
                preview = dto.preview,
                status = dto.status,
                stepCount = dto.stepCount,
                lastModified = dto.lastModified,
                workspace = dto.workspace,
                parentConversationId = dto.parentConversationId
            )
        }
        conversationDao.insertAll(entities)
    }

    suspend fun createChat(
        workspace: String,
        message: String,
        model: String? = null,
        effort: String? = null,
        mode: String? = null
    ): CreateChatResponseDto {
        val req = CreateChatRequestDto(
            workspace = workspace,
            message = message,
            model = model,
            effort = effort,
            mode = mode
        )
        val resp = api.createChat(req)

        // Pre-insert conversation entity & run entity
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

        conversationDao.insertOrUpdate(
            ConversationEntity(
                conversationId = resp.conversationId,
                title = message.take(40),
                preview = message.take(80),
                status = "CASCADE_RUN_STATUS_RUNNING",
                stepCount = 1,
                lastModified = now,
                workspace = workspace
            )
        )

        // Pre-insert user prompt as step 0 so chat UI displays it immediately on opening
        stepDao.insertOrUpdate(
            StepEntity(
                conversationId = resp.conversationId,
                runId = resp.runId,
                stepIndex = 0,
                source = "USER_EXPLICIT",
                type = "USER_INPUT",
                status = "completed",
                createdAt = now,
                content = message,
                userPrompt = message
            )
        )

        runDao.insertOrUpdate(
            RunEntity(
                runId = resp.runId,
                conversationId = resp.conversationId,
                workspace = workspace,
                prompt = message,
                status = "running",
                startedAt = now
            )
        )

        return resp
    }

    private val pendingMessagesFlow = kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<PendingUserMessage>>>(emptyMap())

    fun observePendingMessages(conversationId: String): Flow<List<PendingUserMessage>> {
        return pendingMessagesFlow.map { it[conversationId] ?: emptyList() }
    }

    fun addPendingMessage(conversationId: String, message: PendingUserMessage) {
        val map = pendingMessagesFlow.value.toMutableMap()
        val list = (map[conversationId] ?: emptyList()) + message
        map[conversationId] = list
        pendingMessagesFlow.value = map
    }

    fun updatePendingMessageStatus(conversationId: String, id: String, status: MessageDeliveryStatus) {
        val map = pendingMessagesFlow.value.toMutableMap()
        val list = (map[conversationId] ?: emptyList()).map {
            if (it.id == id) it.copy(status = status) else it
        }
        map[conversationId] = list
        pendingMessagesFlow.value = map
    }

    fun reconcilePendingMessages(conversationId: String, currentSteps: List<Step>) {
        val map = pendingMessagesFlow.value.toMutableMap()
        val list = map[conversationId] ?: return
        val filtered = list.filter { pending ->
            val matching = currentSteps.any { step ->
                step.stepIndex > pending.baseStepIndex &&
                (step.source == "USER_EXPLICIT" || step.type == "USER_INPUT") &&
                (step.userPrompt?.trim() == pending.text.trim() || step.content?.trim() == pending.text.trim())
            }
            !matching
        }
        if (filtered.size != list.size) {
            map[conversationId] = filtered
            pendingMessagesFlow.value = map
        }
    }

    suspend fun sendMessage(
        conversationId: String,
        workspace: String,
        text: String,
        model: String? = null,
        effort: String? = null,
        mode: String? = null
    ): SendMessageResponseDto {
        val req = SendMessageRequestDto(
            text = text,
            model = model,
            effort = effort,
            mode = mode
        )
        val resp = api.sendMessage(conversationId, req)

        runDao.insertOrUpdate(
            RunEntity(
                runId = resp.runId,
                conversationId = conversationId,
                workspace = workspace,
                prompt = text,
                status = resp.status
            )
        )

        return resp
    }

    suspend fun cancelRun(conversationId: String): CancelResponseDto {
        return api.cancelRun(conversationId)
    }

    suspend fun fetchStepsHistory(conversationId: String, afterStep: Int? = null, limit: Int = 500) {
        val stepDtos = api.getChatSteps(conversationId, afterStep, limit)
        val entities = stepDtos.map { dto ->
            StepEntity(
                conversationId = conversationId,
                runId = "",
                stepIndex = dto.stepIndex,
                source = dto.source,
                type = dto.type,
                status = dto.status,
                createdAt = dto.createdAt,
                thinking = dto.thinking,
                content = dto.content,
                userPrompt = dto.userPrompt,
                toolCallsJson = dto.toolCalls?.let { gson.toJson(it) },
                diffsJson = dto.diffs?.let { gson.toJson(it) },
                error = dto.error
            )
        }
        stepDao.insertAll(entities)
    }

    suspend fun getAvailableModels(): List<ModelOption> {
        return api.getModels().map {
            ModelOption(id = it.id, name = it.name, reasoningLevel = it.reasoningLevel)
        }
    }

    private fun ConversationEntity.toDomain() = Conversation(
        id = conversationId,
        title = title,
        preview = preview,
        status = status,
        stepCount = stepCount,
        lastModified = lastModified,
        workspace = workspace,
        parentConversationId = parentConversationId
    )

    private fun StepEntity.toDomain(gson: Gson): Step {
        val tools: List<ToolCall> = toolCallsJson?.let {
            try {
                val listType = object : com.google.gson.reflect.TypeToken<List<ToolCallDto>>() {}.type
                val dtos: List<ToolCallDto> = gson.fromJson(it, listType) ?: emptyList()
                dtos.map { t -> ToolCall(name = t.name, args = t.args ?: emptyMap()) }
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()

        val parsedDiffs: List<CodeDiff> = diffsJson?.let {
            try {
                val listType = object : com.google.gson.reflect.TypeToken<List<CodeDiffDto>>() {}.type
                val dtos: List<CodeDiffDto> = gson.fromJson(it, listType) ?: emptyList()
                dtos.map { d ->
                    CodeDiff(
                        file = d.file,
                        action = d.action,
                        startLine = d.startLine,
                        endLine = d.endLine,
                        targetContent = d.targetContent,
                        replacementContent = d.replacementContent,
                        instruction = d.instruction
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()

        return Step(
            conversationId = conversationId,
            runId = runId,
            stepIndex = stepIndex,
            source = source,
            type = type,
            status = status,
            createdAt = createdAt,
            thinking = thinking,
            content = content,
            userPrompt = userPrompt,
            toolCalls = tools,
            diffs = parsedDiffs,
            error = error
        )
    }
}
