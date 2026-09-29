package com.antigravity.client.data.repository

import com.antigravity.client.data.local.AppDatabase
import com.antigravity.client.data.local.FileCacheEntity
import com.antigravity.client.data.remote.GatewayApi
import com.antigravity.client.data.remote.dto.FileContentResponseDto
import com.antigravity.client.domain.model.FileItem

class FileRepository(
    private val api: GatewayApi,
    private val database: AppDatabase
) {
    private val fileCacheDao = database.fileCacheDao()

    suspend fun listFiles(path: String): List<FileItem> {
        val resp = api.listFiles(path)
        return resp.entries.map {
            FileItem(
                name = it.name,
                path = it.path,
                isDirectory = it.isDir,
                isSymlink = it.isSymlink,
                size = it.size,
                lastModified = it.lastModified
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    suspend fun getFileContent(path: String): FileContentResponseDto {
        val resp = api.getFileContent(path)
        if (!resp.isBinary && resp.content != null) {
            fileCacheDao.insertOrUpdate(
                FileCacheEntity(
                    path = path,
                    name = path.substringAfterLast('/'),
                    isDirectory = false,
                    isSymlink = false,
                    size = resp.size,
                    lastModified = "",
                    cachedContent = resp.content,
                    isBinary = false
                )
            )
        }
        return resp
    }

    suspend fun getCachedContent(path: String): String? {
        return fileCacheDao.getFile(path)?.cachedContent
    }
}
