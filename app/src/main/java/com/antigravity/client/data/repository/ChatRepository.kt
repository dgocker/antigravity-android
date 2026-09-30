package com.antigravity.client.data.repository

import com.antigravity.client.data.local.*
import com.antigravity.client.data.remote.GatewayApi
import com.antigravity.client.data.remote.dto.*
import com.antigravity.client.domain.model.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class ChatRepository(
    private val api: GatewayApi,
    private val database: AppDatabase,
    private val gson: Gson = Gson()
) {
    private val conversationDao = database.conversationDao()
    private val stepDao = database.stepDao()
    private val runDao = database.runDao()
    private val eventDao = database.eventDao()
    val attachmentDao = database.attachmentDao()
    val outboxDao = database.outboxDao()

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
        attachments: List<Attachment> = emptyList(),
        model: String? = null,
        effort: String? = null,
        mode: String? = null
    ): CreateChatResponseDto {
        val attDtos = attachments.map { att ->
            AttachmentRefDto(
                id = att.serverId ?: att.id,
                type = att.type.name.lowercase(),
                fileName = att.fileName,
                mimeType = att.mimeType,
                size = att.size,
                duration = att.duration,
                serverPath = att.remoteUrl,
                transcription = att.transcription
            )
        }
        val req = CreateChatRequestDto(
            workspace = workspace,
            message = message,
            attachments = attDtos,
            model = model,
            effort = effort,
            mode = mode
        )
        val resp = api.createChat(req)

        // Pre-insert conversation entity & run entity
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

        val displayTitle = if (message.isNotBlank()) message.take(40) else (attachments.firstOrNull()?.fileName ?: "New Chat")
        val displayPreview = if (message.isNotBlank()) message.take(80) else (attachments.firstOrNull()?.fileName ?: "")

        conversationDao.insertOrUpdate(
            ConversationEntity(
                conversationId = resp.conversationId,
                title = displayTitle,
                preview = displayPreview,
                status = "CASCADE_RUN_STATUS_RUNNING",
                stepCount = 1,
                lastModified = now,
                workspace = workspace
            )
        )

        val attachmentsJsonStr = if (attachments.isNotEmpty()) gson.toJson(attachments) else null

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
                userPrompt = message,
                attachmentsJson = attachmentsJsonStr
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
        if (list.isEmpty()) return

        // Mark pending messages as DELIVERED if server has started processing
        val updatedList = list.map { pending ->
            if (pending.status == MessageDeliveryStatus.SENDING || pending.status == MessageDeliveryStatus.SENT) {
                val hasSubsequentSteps = currentSteps.any { it.stepIndex > pending.baseStepIndex }
                if (hasSubsequentSteps) pending.copy(status = MessageDeliveryStatus.DELIVERED) else pending
            } else {
                pending
            }
        }

        val filtered = updatedList.filter { pending ->
            val isMatched = currentSteps.any { step ->
                if (step.stepIndex <= pending.baseStepIndex) return@any false

                // Direct match with a server user input step only
                if (step.source == "USER_EXPLICIT" || step.type == "USER_INPUT") {
                    // Match by attachments if present
                    if (pending.attachments.isNotEmpty()) {
                        val attMatch = step.attachments.any { sa ->
                            pending.attachments.any { pa ->
                                sa.id == pa.id ||
                                (pa.serverId != null && (pa.serverId == sa.id || pa.serverId == sa.remoteUrl)) ||
                                (sa.remoteUrl != null && (sa.remoteUrl == pa.remoteUrl || sa.remoteUrl == pa.localUri)) ||
                                (sa.fileName == pa.fileName && (pa.size == 0L || sa.size == 0L || sa.size == pa.size))
                            }
                        }
                        if (attMatch) return@any true
                        // If pending only had attachments and user step has attachments
                        if (pending.text.isBlank() && step.attachments.isNotEmpty()) return@any true
                    }

                    // Match by text
                    val stepUserPrompt = (step.userPrompt ?: "").trim()
                    val stepContent = (step.content ?: "").trim()
                    val pendingText = pending.text.trim()
                    if (pendingText.isNotBlank()) {
                        if (stepUserPrompt == pendingText ||
                            stepContent == pendingText ||
                            stepContent.contains(pendingText) ||
                            stepUserPrompt.contains(pendingText)
                        ) return@any true
                    } else if (pending.attachments.isEmpty() && stepUserPrompt.isBlank()) {
                        return@any true
                    }
                }

                false
            }
            !isMatched
        }

        if (filtered != list) {
            map[conversationId] = filtered
            pendingMessagesFlow.value = map
        }
    }

    suspend fun sendMessage(
        conversationId: String,
        workspace: String,
        text: String,
        attachments: List<Attachment> = emptyList(),
        model: String? = null,
        effort: String? = null,
        mode: String? = null
    ): SendMessageResponseDto {
        val attDtos = attachments.map { att ->
            AttachmentRefDto(
                id = att.serverId ?: att.id,
                type = att.type.name.lowercase(),
                fileName = att.fileName,
                mimeType = att.mimeType,
                size = att.size,
                duration = att.duration,
                serverPath = att.remoteUrl,
                transcription = att.transcription
            )
        }
        val req = SendMessageRequestDto(
            text = text,
            attachments = attDtos,
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

    suspend fun uploadAttachment(
        file: java.io.File,
        mimeType: String,
        conversationId: String? = null,
        transcription: String? = null,
        duration: Int? = null,
        onProgress: (Float) -> Unit = {}
    ): AttachmentUploadResponseDto {
        val mediaType = mimeType.toMediaTypeOrNull()
        val fileReqBody = file.asRequestBody(mediaType)
        val countingBody = com.antigravity.client.data.remote.CountingRequestBody(fileReqBody, onProgress)
        val filePart = okhttp3.MultipartBody.Part.createFormData("file", file.name, countingBody)

        val plainType = "text/plain".toMediaTypeOrNull()
        val convPart = conversationId?.let { it.toRequestBody(plainType) }
        val transPart = transcription?.let { it.toRequestBody(plainType) }
        val durPart = duration?.let { it.toString().toRequestBody(plainType) }

        return api.uploadAttachment(
            file = filePart,
            conversationId = convPart,
            transcription = transPart,
            duration = durPart
        )
    }

    fun getOutboxItems(conversationId: String): Flow<List<OutboxEntity>> = outboxDao.getItemsForConversation(conversationId)
    suspend fun saveOutboxItem(item: OutboxEntity) = outboxDao.insert(item)
    suspend fun updateOutboxState(id: String, state: String, error: String? = null) = outboxDao.updateState(id, state, error)
    suspend fun deleteOutboxItem(id: String) = outboxDao.delete(id)
    fun getPendingOutboxItems(): Flow<List<OutboxEntity>> = outboxDao.getPendingItems()

    fun getAttachmentsForConversation(conversationId: String): Flow<List<AttachmentEntity>> = attachmentDao.getAttachmentsForConversation(conversationId)
    fun getAttachmentsForMessage(messageId: String): Flow<List<AttachmentEntity>> = attachmentDao.getAttachmentsForMessage(messageId)
    suspend fun saveAttachment(entity: AttachmentEntity) = attachmentDao.insertOrUpdate(entity)
    suspend fun updateAttachmentState(id: String, state: String, remoteUrl: String? = null, serverId: String? = null) = attachmentDao.updateUploadState(id, state, remoteUrl, serverId)
    suspend fun updateAttachmentTranscription(id: String, text: String) = attachmentDao.updateTranscription(id, text)

    suspend fun cancelRun(conversationId: String): CancelResponseDto {
        return api.cancelRun(conversationId)
    }

    suspend fun deleteCheckpointSteps(conversationId: String) {
        stepDao.deleteCheckpointSteps(conversationId)
    }

    suspend fun fetchStepsHistory(conversationId: String, afterStep: Int? = null, limit: Int = 100) {
        stepDao.deleteCheckpointSteps(conversationId)
        val actualAfterStep = afterStep ?: stepDao.getMaxStepIndex(conversationId)
        val stepDtos = api.getChatSteps(conversationId, actualAfterStep, limit)
        if (stepDtos.isEmpty()) return

        val entities = stepDtos.mapNotNull { dto ->
            if (dto.type == "CHECKPOINT" ||
                dto.content?.contains("<CONTEXT_SUMMARY>") == true ||
                dto.content?.startsWith("# Resuming from a compaction") == true
            ) {
                return@mapNotNull null
            }
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
                attachmentsJson = dto.attachments?.let { dtos ->
                    val domainAttachments = dtos.map { a ->
                        Attachment(
                            id = a.id,
                            conversationId = conversationId,
                            type = when (a.type.lowercase()) {
                                "image" -> AttachmentType.IMAGE
                                "video" -> AttachmentType.VIDEO
                                "audio" -> AttachmentType.AUDIO
                                "document" -> AttachmentType.DOCUMENT
                                else -> AttachmentType.OTHER
                            },
                            fileName = a.fileName,
                            mimeType = a.mimeType,
                            size = a.size,
                            duration = a.duration,
                            remoteUrl = a.serverPath,
                            serverId = a.id,
                            transcription = a.transcription,
                            uploadState = AttachmentUploadState.COMPLETED
                        )
                    }
                    gson.toJson(domainAttachments)
                },
                error = dto.error
            )
        }
        if (entities.isNotEmpty()) {
            stepDao.insertAll(entities)
        }
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

        val parsedAttachments: List<Attachment> = attachmentsJson?.let {
            try {
                val listType = object : com.google.gson.reflect.TypeToken<List<Attachment>>() {}.type
                gson.fromJson(it, listType) ?: emptyList()
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
            attachments = parsedAttachments,
            error = error
        )
    }

    suspend fun getArtifacts(conversationId: String): List<ArtifactDto> = withContext(Dispatchers.IO) {
        try {
            networkClient.getApi().getArtifacts(conversationId)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getTasks(conversationId: String): TasksResponseDto = withContext(Dispatchers.IO) {
        try {
            networkClient.getApi().getTasks(conversationId)
        } catch (e: Exception) {
            TasksResponseDto()
        }
    }

    suspend fun killTask(conversationId: String, taskId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            networkClient.getApi().killTask(conversationId, taskId).isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getSlashCommands(): List<SlashCommandDto> = withContext(Dispatchers.IO) {
        try {
            networkClient.getApi().getSlashCommands()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getFileContent(path: String): String = withContext(Dispatchers.IO) {
        try {
            networkClient.getApi().getFileContent(path).content
        } catch (e: Exception) {
            "Не удалось загрузить содержимое файла: ${e.message}"
        }
    }
}
