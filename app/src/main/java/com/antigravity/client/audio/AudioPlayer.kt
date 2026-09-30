package com.antigravity.client.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val urlOrPath: String? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Int = 0,
    val durationMs: Int = 0
)

class AudioPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null

    fun play(urlOrPath: String) {
        if (_playbackState.value.urlOrPath == urlOrPath && mediaPlayer != null) {
            if (_playbackState.value.isPlaying) {
                pause()
            } else {
                resume()
            }
            return
        }

        stop()

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
                    setDataSource(urlOrPath)
                } else if (urlOrPath.startsWith("content://")) {
                    setDataSource(context, Uri.parse(urlOrPath))
                } else {
                    setDataSource(urlOrPath)
                }

                setOnPreparedListener { mp ->
                    mp.start()
                    val totalDuration = mp.duration
                    _playbackState.value = PlaybackState(
                        urlOrPath = urlOrPath,
                        isPlaying = true,
                        currentPositionMs = 0,
                        durationMs = totalDuration
                    )
                    startProgressTracker()
                }

                setOnCompletionListener {
                    stop()
                }

                setOnErrorListener { _, _, _ ->
                    stop()
                    true
                }

                prepareAsync()
            }
            mediaPlayer = player
        } catch (_: Exception) {
            stop()
        }
    }

    fun pause() {
        try {
            mediaPlayer?.pause()
            progressJob?.cancel()
            _playbackState.value = _playbackState.value.copy(isPlaying = false)
        } catch (_: Exception) {}
    }

    fun resume() {
        try {
            mediaPlayer?.start()
            _playbackState.value = _playbackState.value.copy(isPlaying = true)
            startProgressTracker()
        } catch (_: Exception) {}
    }

    fun seekTo(positionMs: Int) {
        try {
            mediaPlayer?.seekTo(positionMs)
            _playbackState.value = _playbackState.value.copy(currentPositionMs = positionMs)
        } catch (_: Exception) {}
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        _playbackState.value = PlaybackState()
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(100)
                val player = mediaPlayer ?: break
                if (player.isPlaying) {
                    val pos = try { player.currentPosition } catch (_: Exception) { 0 }
                    val dur = try { player.duration } catch (_: Exception) { 0 }
                    _playbackState.value = _playbackState.value.copy(
                        currentPositionMs = pos,
                        durationMs = dur
                    )
                }
            }
        }
    }
}
