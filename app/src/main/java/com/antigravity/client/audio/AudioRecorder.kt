package com.antigravity.client.audio

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

data class AudioRecordingResult(
    val file: File,
    val durationSeconds: Int,
    val mimeType: String = "audio/m4a",
    val sizeBytes: Long = 0L,
    val transcription: String? = null
)

class AudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTimeMs: Long = 0L
    private var durationSeconds: Int = 0
    private var timerJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizedText: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var isRecording: Boolean = false
        private set

    fun startRecording(
        onDurationTick: (Int) -> Unit = {},
        onTranscription: (String) -> Unit = {}
    ) {
        if (isRecording) return

        val audioDir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
        val file = File(audioDir, "voice_${System.currentTimeMillis()}.m4a")
        currentFile = file
        recognizedText = null
        durationSeconds = 0

        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true
            startTimeMs = System.currentTimeMillis()

            // Timer loop
            timerJob = scope.launch {
                while (isRecording) {
                    delay(500)
                    val elapsed = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
                    durationSeconds = elapsed
                    onDurationTick(elapsed)
                }
            }

            // Start on-device speech recognition if supported
            startSpeechRecognition(onTranscription)

        } catch (e: Exception) {
            cancelRecording()
            throw e
        }
    }

    private fun startSpeechRecognition(onTranscription: (String) -> Unit) {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {}
                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                recognizedText = matches[0]
                                onTranscription(matches[0])
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                recognizedText = matches[0]
                                onTranscription(matches[0])
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })

                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    }
                    startListening(intent)
                }
            }
        } catch (_: Exception) {
            // Speech recognition is an enhancement; failure to start should not block recording
        }
    }

    fun stopRecording(): AudioRecordingResult? {
        if (!isRecording) return null

        timerJob?.cancel()
        timerJob = null
        isRecording = false

        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
            mediaRecorder?.release()
        }
        mediaRecorder = null

        val file = currentFile ?: return null
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            return null
        }

        val elapsed = maxOf(1, ((System.currentTimeMillis() - startTimeMs) / 1000).toInt())
        return AudioRecordingResult(
            file = file,
            durationSeconds = elapsed,
            mimeType = "audio/m4a",
            sizeBytes = file.length(),
            transcription = recognizedText
        )
    }

    fun cancelRecording() {
        isRecording = false
        timerJob?.cancel()
        timerJob = null

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
            try { mediaRecorder?.release() } catch (_: Exception) {}
        }
        mediaRecorder = null

        currentFile?.delete()
        currentFile = null
    }
}
