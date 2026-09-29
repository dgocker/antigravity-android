package com.antigravity.client.data.remote

import com.antigravity.client.security.TokenStore
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class NetworkClient(private val tokenStore: TokenStore) {

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val token = tokenStore.getToken()
        val requestBuilder = original.newBuilder()

        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        chain.proceed(requestBuilder.build())
    }

    private val safeLoggingInterceptor = HttpLoggingInterceptor { message ->
        // Security rule: NEVER log Authorization header or tokens
        if (!message.contains("Authorization:", ignoreCase = true) &&
            !message.contains("agy_android_", ignoreCase = true)
        ) {
            android.util.Log.d("GatewayNetwork", message)
        }
    }.apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(safeLoggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite read timeout for streaming & WS
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun getApi(): GatewayApi {
        var baseUrl = tokenStore.serverUrl.trim()
        if (baseUrl.isBlank()) {
            baseUrl = TokenStore.DEFAULT_SERVER_URL
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "http://$baseUrl"
        }
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/"
        }
        return try {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GatewayApi::class.java)
        } catch (e: Exception) {
            Retrofit.Builder()
                .baseUrl("${TokenStore.DEFAULT_SERVER_URL}/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GatewayApi::class.java)
        }
    }
}
