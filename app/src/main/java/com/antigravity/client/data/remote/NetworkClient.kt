package com.antigravity.client.data.remote

import android.annotation.SuppressLint
import android.util.Log
import com.antigravity.client.security.TokenStore
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
            Log.d("GatewayNetwork", message)
        }
    }.apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    val okHttpClient: OkHttpClient = buildOkHttpClient()

    private fun buildOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(safeLoggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite read timeout for streaming & WS
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS) // Persistent WS keepalive
            .retryOnConnectionFailure(true)

        if (tokenStore.trustSelfSigned) {
            try {
                val trustManager = createTrustAllTrustManager()
                val sslSocketFactory = createUnsafeSslSocketFactory(trustManager)
                builder.sslSocketFactory(sslSocketFactory, trustManager)
                builder.hostnameVerifier { _, _ -> true }
                Log.w("NetworkClient", "SSL verification relaxed (trustSelfSigned = true)")
            } catch (e: Exception) {
                Log.e("NetworkClient", "Failed to configure relaxed SSL: ${e.message}", e)
            }
        }

        return builder.build()
    }

    fun getApi(): GatewayApi {
        val baseUrl = "${normalizeBaseUrl(tokenStore.serverUrl)}/"
        return try {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GatewayApi::class.java)
        } catch (e: Exception) {
            Log.e("NetworkClient", "Failed to create Retrofit for '$baseUrl': ${e.message}")
            Retrofit.Builder()
                .baseUrl("${TokenStore.DEFAULT_SERVER_URL}/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GatewayApi::class.java)
        }
    }

    companion object {
        fun normalizeBaseUrl(input: String): String {
            var url = input.trim()
            if (url.isBlank()) {
                return TokenStore.DEFAULT_SERVER_URL
            }
            val lower = url.lowercase()
            url = when {
                lower.startsWith("https://") -> "https://" + url.substring(8).trimStart('/')
                lower.startsWith("http://") -> "http://" + url.substring(7).trimStart('/')
                else -> "http://$url"
            }
            return url.trimEnd('/')
        }

        @SuppressLint("CustomX509TrustManager")
        private fun createTrustAllTrustManager(): X509TrustManager {
            return object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        }

        private fun createUnsafeSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            return sslContext.socketFactory
        }
    }
}
