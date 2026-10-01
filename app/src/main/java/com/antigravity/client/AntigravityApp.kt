package com.antigravity.client

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.antigravity.client.data.local.AppDatabase
import com.antigravity.client.data.remote.NetworkClient
import com.antigravity.client.data.repository.ChatRepository
import com.antigravity.client.data.repository.FileRepository
import com.antigravity.client.security.TokenStore
import com.antigravity.client.sync.SyncEngine

class AntigravityApp : Application(), ImageLoaderFactory {

    lateinit var tokenStore: TokenStore
        private set

    lateinit var database: AppDatabase
        private set

    lateinit var networkClient: NetworkClient
        private set

    lateinit var chatRepository: ChatRepository
        private set

    lateinit var fileRepository: FileRepository
        private set

    lateinit var syncEngine: SyncEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        tokenStore = TokenStore(this)
        com.antigravity.client.util.LocaleHelper.applyLocale(this, tokenStore.appLanguage)
        networkClient = NetworkClient(tokenStore)

        try {
            database = AppDatabase.getInstance(this)
            chatRepository = ChatRepository(networkClient.getApi(), database)
            fileRepository = FileRepository(networkClient.getApi(), database)
            syncEngine = SyncEngine(database, tokenStore, networkClient.okHttpClient) { convId ->
                try {
                    chatRepository.fetchStepsHistory(convId)
                } catch (e: Exception) {
                    Log.w("AntigravityApp", "Sync error on turn finish: ${e.message}")
                }
            }

            if (tokenStore.hasToken()) {
                syncEngine.start()
            }
        } catch (e: Throwable) {
            Log.e("AntigravityApp", "Error during app initialization: ${e.message}", e)
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient { networkClient.okHttpClient }
            .crossfade(true)
            .build()
    }

    fun recreateNetworkClient(): NetworkClient {
        networkClient = NetworkClient(tokenStore)
        return networkClient
    }

    fun restartSyncEngine() {
        try {
            if (::syncEngine.isInitialized) {
                syncEngine.stop()
            }
            networkClient = NetworkClient(tokenStore)
            if (::database.isInitialized) {
                chatRepository = ChatRepository(networkClient.getApi(), database)
                fileRepository = FileRepository(networkClient.getApi(), database)
                syncEngine = SyncEngine(database, tokenStore, networkClient.okHttpClient) { convId ->
                    try {
                        chatRepository.fetchStepsHistory(convId)
                    } catch (e: Exception) {
                        Log.w("AntigravityApp", "Sync error on turn finish: ${e.message}")
                    }
                }
                syncEngine.start()
            }
        } catch (e: Throwable) {
            Log.e("AntigravityApp", "Error restarting sync engine: ${e.message}", e)
        }
    }

    companion object {
        lateinit var instance: AntigravityApp
            private set
    }
}
