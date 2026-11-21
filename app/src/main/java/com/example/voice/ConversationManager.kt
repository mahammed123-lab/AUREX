package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

enum class ConversationState { IDLE, LISTENING, THINKING, SPEAKING }

class ConversationManager(
    private val context: Context,
    private val onUtterance: (String) -> Unit,
    private val onBargeIn: () -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var state: ConversationState = ConversationState.IDLE
    private var listeningForBargeInOnly = false

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900)
    }

    fun start() {
        if (recognizer != null) return
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
            }
            enterListeningMode()
        } catch (e: Exception) {
            android.util.Log.e("ConversationManager", "Failed to initialize SpeechRecognizer", e)
        }
    }

    fun stop() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            android.util.Log.e("ConversationManager", "Error stopping SpeechRecognizer", e)
        }
        recognizer = null
        state = ConversationState.IDLE
    }

    /** Call right before TTS playback starts — mic stays open, listening only
     *  for whether the user is talking, so they can interrupt JARVIS. */
    fun enterSpeakingMode() {
        state = ConversationState.SPEAKING
        listeningForBargeInOnly = true
        listenOnce()
    }

    fun enterListeningMode() {
        state = ConversationState.LISTENING
        listeningForBargeInOnly = false
        listenOnce()
    }

    private fun listenOnce() {
        try {
            recognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            android.util.Log.e("ConversationManager", "Error in startListening", e)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                if (listeningForBargeInOnly) {
                    onBargeIn()
                    listeningForBargeInOnly = false
                    state = ConversationState.LISTENING
                }
                state = ConversationState.THINKING
                onUtterance(text)
            } else {
                if (state != ConversationState.IDLE) listenOnce()
            }
        }

        override fun onPartialResults(partialResults: Bundle) {
            if (listeningForBargeInOnly) {
                val partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    onBargeIn()
                    listeningForBargeInOnly = false
                }
            }
        }

        override fun onError(error: Int) {
            // SpeechRecognizer errors out on silence timeouts — expected, just restart the loop
            if (state != ConversationState.IDLE) listenOnce()
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
