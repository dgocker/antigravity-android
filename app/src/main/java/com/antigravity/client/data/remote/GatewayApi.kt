package com.antigravity.client.data.remote

import com.antigravity.client.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface GatewayApi {

    @GET("v1/health")
    suspend fun getHealth(): HealthResponseDto

    @GET("v1/models")
    suspend fun getModels(): List<ModelInfoDto>

    @GET("v1/chats")
    suspend fun getChats(): List<ChatSummaryDto>

    @POST("v1/chats")
    suspend fun createChat(
        @Body request: CreateChatRequestDto
    ): CreateChatResponseDto

    @POST("v1/chats/{id}/messages")
    suspend fun sendMessage(
        @Path("id") chatId: String,
        @Body request: SendMessageRequestDto
    ): SendMessageResponseDto

    @POST("v1/chats/{id}/cancel")
    suspend fun cancelRun(
        @Path("id") chatId: String
    ): CancelResponseDto

    @GET("v1/chats/{id}/steps")
    suspend fun getChatSteps(
        @Path("id") chatId: String,
        @Query("after_step") afterStep: Int? = null,
        @Query("limit") limit: Int = 50
    ): List<NormalizedStepDto>

    @GET("v1/events")
    suspend fun getEvents(
        @Query("after") afterSeq: Long = 0,
        @Query("conversation_id") conversationId: String? = null,
        @Query("limit") limit: Int = 100
    ): List<EventItemDto>

    @GET("v1/files")
    suspend fun listFiles(
        @Query("path") path: String
    ): DirectoryListResponseDto

    @GET("v1/files/content")
    suspend fun getFileContent(
        @Query("path") path: String
    ): FileContentResponseDto

    @POST("v1/auth/device-tokens")
    suspend fun createDeviceToken(
        @Body request: DeviceTokenCreateRequestDto
    ): DeviceTokenCreateResponseDto

    @GET("v1/auth/device-tokens")
    suspend fun listDeviceTokens(): List<DeviceTokenInfoDto>

    @DELETE("v1/auth/device-tokens/{id}")
    suspend fun revokeDeviceToken(
        @Path("id") tokenId: Int
    ): Response<Unit>
}
