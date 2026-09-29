package com.antigravity.client

import android.app.Application
import com.antigravity.client.data.local.AppDatabase
import com.antigravity.client.data.remote.NetworkClient
import com.antigravity.client.data.repository.ChatRepository
import com.antigravity.client.data.repository.FileRepository
import com.antigravity.client.security.TokenStore
import com.antigravity.client.sync.SyncEngine

class AntigravityApp : Application() {

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
        database = AppDatabase.getInstance(this)
        networkClient = NetworkClient(tokenStore)
        chatRepository = ChatRepository(networkClient.getApi(), database)
        fileRepository = FileRepository(networkClient.getApi(), database)
        syncEngine = SyncEngine(database, tokenStore, networkClient.okHttpClient)

        // Automatically start sync if token is configured
        if (tokenStore.hasToken()) {
            syncEngine.start()
        }
    }

    fun restartSyncEngine() {
        syncEngine.stop()
        networkClient = NetworkClient(tokenStore)
        chatRepository = ChatRepository(networkClient.getApi(), database)
        fileRepository = FileRepository(networkClient.getApi(), database)
        syncEngine = SyncEngine(database, tokenStore, networkClient.okHttpClient)
        syncEngine.start()
    }

    companion object {
        lateinit var instance: AntigravityApp
            private set
    }
}
