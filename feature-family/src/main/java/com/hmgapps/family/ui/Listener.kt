package com.hmgapps.family.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import java.util.Locale

/**
 * 한 번 듣고 결과 문장을 돌려주는 음성 인식기 (Pleos SpeechToText SDK, ai.pleos.playground:SpeechToText:3.3.1).
 *
 * - ON_DEVICE 모드: 차량 온디바이스 엔진 → 네트워크·앱 등록(registerApp) 불필요. 권한은 pleos.car.permission.STT_SERVICE 하나.
 * - 마이크 제어는 Pleos Speech Service 가 맡으므로 RECORD_AUDIO 권한을 선언하지 않는다.
 * - Pleos 음성 서비스가 없는 휴대폰·일반 에뮬레이터에서는 available=false → 화면의 '직접 입력'으로 대신한다.
 *
 * 문서(Result Listeners / Starting and Stopping) 순서: initialize → addListener → setLocale → request() … stop() → removeListener → release.
 * 결과·완료 콜백은 메인 스레드에서 한 번만 전달한다. Logcat 태그: FamilyListener.
 */
class Listener(context: Context, private val onAvailable: (Boolean) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var stt: SpeechToText? = null
    private var ready = false
    private var pending: ((String?) -> Unit)? = null
    private var onPartial: ((String) -> Unit)? = null
    private var lastText = ""
    private val timeout = Runnable { Log.i(TAG, "인식 시간 초과"); runCatching { stt?.stop() }; deliver(lastText.ifBlank { null }) }

    val available: Boolean get() = ready

    private val results = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) {
            main.post {
                if (stt.isNotBlank()) { lastText = stt; onPartial?.invoke(stt) }
                if (completed) deliver(stt.ifBlank { lastText }.ifBlank { null })
            }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() { main.post { deliver(lastText.ifBlank { null }) } }
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "STT error $errorType")
            main.post { deliver(null) }
        }
    }

    init { start() }

    private fun start() {
        val s = try {
            SpeechToText(app, Mode.ON_DEVICE)
        } catch (t: Throwable) {
            Log.w(TAG, "Pleos STT 생성 실패 (음성 서비스 없음)", t); onAvailable(false); return
        }
        stt = s
        try {
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        runCatching {
                            s.addListener(results) // request() 전에 등록해야 이벤트를 놓치지 않는다 (문서)
                            s.setLocale(Locale.KOREAN)
                        }.onFailure { Log.w(TAG, "Pleos STT 설정 실패", it) }
                        ready = true
                        onAvailable(true)
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "Pleos STT 초기화 실패 $errorType")
                    main.post { releaseSdk(); onAvailable(false) }
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "Pleos STT initialize 예외", t)
            releaseSdk(); onAvailable(false)
        }
    }

    /**
     * 한 번 듣는다. 결과(문장) 또는 null(못 알아들음·오류·시간 초과)을 onResult 로 한 번 준다.
     * 듣는 중 다시 부르면 이전 요청은 null 로 끝난다.
     */
    fun listen(timeoutMs: Long = 8_000L, partial: (String) -> Unit = {}, onResult: (String?) -> Unit) {
        val s = stt
        if (s == null || !ready) { onResult(null); return }
        cancel()
        pending = onResult
        onPartial = partial
        lastText = ""
        main.postDelayed(timeout, timeoutMs)
        runCatching { s.request() }.onFailure { Log.w(TAG, "request 실패", it); deliver(null) }
    }

    /** 듣기를 멈춘다. 결과 콜백은 부르지 않는다. */
    fun cancel() {
        main.removeCallbacks(timeout)
        val had = pending != null
        pending = null
        onPartial = null
        if (had) runCatching { stt?.stop() }
    }

    private fun deliver(text: String?) {
        main.removeCallbacks(timeout)
        val cb = pending ?: return
        pending = null
        onPartial = null
        cb(text)
    }

    private fun releaseSdk() {
        val s = stt ?: return
        stt = null
        ready = false
        runCatching { s.removeListener(results) }
        runCatching { s.release() }
    }

    fun release() {
        cancel()
        releaseSdk()
    }

    private companion object { const val TAG = "FamilyListener" }
}
