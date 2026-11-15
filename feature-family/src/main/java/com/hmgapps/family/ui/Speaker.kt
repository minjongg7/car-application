package com.hmgapps.family.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.tts.UtteranceProgressListener
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale
import android.speech.tts.TextToSpeech as AndroidTts
import ai.pleos.playground.tts.TextToSpeech as PleosTts

/**
 * 한 문장씩 읽는 음성 엔진. 문장이 끝나면 onDone 을 메인 스레드에서 한 번 부른다.
 *
 * 1순위: Pleos TextToSpeech SDK (ai.pleos.playground:TextToSpeech:3.3.1, ON_DEVICE 모드 → 계정·네트워크 불필요)
 *        Pleos Connect 에는 Android 기본 TTS 엔진이 없어서 차량에서는 이 SDK 로 차량 Speech Service 를 쓴다.
 * 2순위: Android 기본 TextToSpeech (Pleos 음성 서비스가 없는 휴대폰·일반 에뮬레이터용)
 */
class Speaker(context: Context, private val onEngine: (String?) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var pleos: PleosTts? = null
    private var android: AndroidTts? = null
    private var pendingDone: (() -> Unit)? = null

    /** 현재 쓰는 엔진 이름 ("Pleos TTS" / "Android TTS"), 없으면 null. */
    var engine: String? = null
        private set

    private val pleosEvents = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() { main.post { finish() } }
        override fun onStop() = Unit // stop() 은 우리가 부른 것이므로 다음 문장으로 넘어가지 않는다
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "Pleos TTS error $errorType")
            main.post { finish() }
        }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    init { startPleos() }

    private fun startPleos() {
        val tts = try {
            PleosTts(app, Mode.ON_DEVICE)
        } catch (t: Throwable) {
            Log.w(TAG, "Pleos TTS 생성 실패 → Android TTS", t); startAndroid(); return
        }
        pleos = tts
        try {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        runCatching {
                            tts.addEventListener(pleosEvents)
                            tts.setLocale(Locale.KOREAN)
                            tts.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .setUsage(AudioAttributes.USAGE_MEDIA) // 동승자 콘텐츠 = 미디어 음량
                                    .build()
                            )
                        }.onFailure { Log.w(TAG, "Pleos TTS 설정 실패", it) }
                        engine = "Pleos TTS"
                        onEngine(engine)
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "Pleos TTS 초기화 실패 $errorType → Android TTS")
                    main.post { releasePleos(); startAndroid() }
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "Pleos TTS initialize 예외 → Android TTS", t)
            releasePleos(); startAndroid()
        }
    }

    private fun startAndroid() {
        val created = AndroidTts(app) { status ->
            val ok = status == AndroidTts.SUCCESS &&
                runCatching { (android?.setLanguage(Locale.KOREAN) ?: AndroidTts.LANG_NOT_SUPPORTED) >= AndroidTts.LANG_AVAILABLE }.getOrDefault(false)
            main.post {
                engine = if (ok) "Android TTS" else null
                onEngine(engine)
            }
        }
        android = created
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { main.post { finish() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { finish() } }
        })
    }

    /** 문장 하나를 읽는다. 다 읽으면(또는 오류면) onDone. 읽는 중이던 문장은 끊는다. */
    fun speak(text: String, onDone: () -> Unit) {
        pendingDone = onDone
        val p = pleos
        if (p != null && engine == "Pleos TTS") {
            runCatching { p.speak(text) }.onFailure { Log.w(TAG, "Pleos speak 실패", it); finish() }
            return
        }
        val a = android
        if (a != null && engine == "Android TTS") {
            a.speak(text, AndroidTts.QUEUE_FLUSH, null, "fc-${System.nanoTime()}")
            return
        }
        finish()
    }

    /** 읽기를 멈춘다. onDone 은 부르지 않는다. */
    fun stop() {
        pendingDone = null
        runCatching { pleos?.stop() }
        runCatching { android?.stop() }
    }

    private fun finish() {
        val cb = pendingDone ?: return
        pendingDone = null
        cb()
    }

    private fun releasePleos() {
        val p = pleos ?: return
        pleos = null
        runCatching { p.removeEventListener(pleosEvents) }
        runCatching { p.release() }
    }

    fun release() {
        stop()
        releasePleos()
        android?.shutdown(); android = null
        engine = null
    }

    private companion object { const val TAG = "FamilySpeaker" }
}
