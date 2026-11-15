package com.fatigueguard.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 한국어 TTS 래퍼. 초기화 실패 시 ready=false 로 우아하게 저하(음성만 생략). */
class TtsSpeaker(context: Context, private val onReady: (Boolean) -> Unit) {
    @Volatile var ready = false
        private set
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS &&
                runCatching { (tts?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE }
                    .getOrDefault(false)
            onReady(ready)
        }
    }

    fun speak(text: String) {
        if (ready) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fg-alert")
    }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null }
}
