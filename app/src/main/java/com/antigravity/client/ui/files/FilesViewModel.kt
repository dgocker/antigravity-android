package com.antigravity.client.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.client.AntigravityApp
import com.antigravity.client.domain.model.FileItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FilesViewModel(
    private val app: AntigravityApp = AntigravityApp.instance
) : ViewModel() {

    private val repository = app.fileRepository
    val tokenStore = app.tokenStore

    val currentPath = MutableStateFlow(tokenStore.defaultWorkspace)

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadDirectory(currentPath.value)
    }

    fun loadDirectory(path: String) {
        currentPath.value = path
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                _files.value = repository.listFiles(path)
            } catch (e: Exception) {
                _error.value = "Failed to list files: ${e.localizedMessage ?: e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun navigateUp() {
        val path = currentPath.value.trimEnd('/')
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            loadDirectory(parent)
        }
    }
}
