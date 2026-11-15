package com.fatigueguard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fatigueguard.data.ConsentStore
import com.fatigueguard.data.DriveProfile
import com.fatigueguard.data.RestAreaRepository
import com.fatigueguard.data.SessionStore
import com.fatigueguard.data.TelemetrySource
import com.fatigueguard.data.TelemetrySourceFactory
import com.fatigueguard.data.VehicleApiProbe
import com.fatigueguard.domain.Capabilities
import com.fatigueguard.domain.Consent
import com.fatigueguard.domain.FatigueAssessment
import com.fatigueguard.domain.FatigueEngine
import com.fatigueguard.domain.FatigueSession
import com.fatigueguard.domain.Messages
import com.fatigueguard.domain.RestAreaCandidate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class UiState(
    val assessment: FatigueAssessment? = null,
    val rests: List<RestAreaCandidate> = emptyList(),
    val consent: Consent = Consent(),
    val running: Boolean = false,
    val sourceName: String = "-",
    val caps: Capabilities = Capabilities(),
    val logs: List<FatigueSession> = emptyList(),
    val notice: String? = null,
    /** 마지막으로 받은 위치 (텔레메트리 위치 또는 데모 시작점). 내비 홈의 출발점으로 쓴다. */
    val lat: Double = DEMO_START_LAT,
    val lon: Double = DEMO_START_LON,
    val hasFix: Boolean = false,
)

// 위치를 아직 못 받았을 때의 출발점 = Mock 텔레메트리 시작점(판교 부근).
const val DEMO_START_LAT = 37.40
const val DEMO_START_LON = 127.10

class FatigueViewModel(app: Application) : AndroidViewModel(app) {
    private val consentStore = ConsentStore(app)
    private val sessions = SessionStore(app)
    private val restRepo = RestAreaRepository()
    private val _state = MutableStateFlow(UiState(consent = consentStore.load(), logs = sessions.readAll().reversed()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val tts = TtsSpeaker(app) { ok -> _state.update { it.copy(caps = it.caps.copy(tts = ok)) } }

    private var job: Job? = null
    private var source: TelemetrySource? = null
    private var engine = FatigueEngine()
    private var lastLevel = -1

    fun start(profile: DriveProfile) {
        stop()
        engine = FatigueEngine(); lastLevel = -1
        val (src, caps) = TelemetrySourceFactory.create(getApplication(), profile)
        source = src
        _state.update {
            it.copy(running = true, sourceName = src.name, assessment = null, rests = emptyList(),
                caps = caps.copy(tts = it.caps.tts), notice = null)
        }
        job = viewModelScope.launch {
            src.events()
                .catch { e -> _state.update { it.copy(running = false, notice = "텔레메트리 오류: ${e.message}") } }
                .collect { e ->
                    val consent = _state.value.consent
                    if (!consent.telemetry) return@collect
                    val a = engine.update(e)
                    val lat = e.lat
                    val lon = e.lon
                    val nearby = if (a.level >= 2 && consent.location && lat != null && lon != null)
                        restRepo.nearest(lat, lon) else emptyList()
                    // warmup(과거 재생) 구간은 상태만 갱신하고 알림/로그는 생략
                    if (e.warmup) lastLevel = a.level
                    else if (a.level != lastLevel) onLevelChange(a, nearby)
                    _state.update {
                        if (lat != null && lon != null) it.copy(assessment = a, rests = nearby, lat = lat, lon = lon, hasFix = true)
                        else it.copy(assessment = a, rests = nearby)
                    }
                }
        }
    }

    private fun onLevelChange(a: FatigueAssessment, nearby: List<RestAreaCandidate>) {
        lastLevel = a.level
        val c = _state.value.consent
        if (c.tts) tts.speak(Messages.byLevel[a.level])
        if (c.storingLogs) {
            sessions.append(
                FatigueSession(
                    Instant.now().toString(), a.drivingMinutes, a.distanceKm, a.speedStdDev, a.accelEvents, a.level,
                    nearby.firstOrNull()?.distanceKm, nearby.map { it.name },
                )
            )
            _state.update { it.copy(logs = sessions.readAll().reversed()) }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        source?.close(); source = null
        _state.update { it.copy(running = false) }
    }

    fun setConsent(c: Consent) { consentStore.save(c); _state.update { it.copy(consent = c) } }

    fun speakRestGuide(r: RestAreaCandidate) {
        if (_state.value.consent.tts) tts.speak("${r.name}까지 ${r.distanceKm}킬로미터 남았습니다.")
    }

    /** 디버그: Vehicle SDK 의 속도 관련 API 이름을 탐색해 알림 카드에 표시 (전체 목록은 Logcat "PleosProbe"). */
    fun probeSdk() { showNotice(VehicleApiProbe.run()) }

    fun clearLogs() { sessions.clear(); _state.update { it.copy(logs = emptyList()) } }
    fun dismissNotice() = _state.update { it.copy(notice = null) }
    fun showNotice(m: String) = _state.update { it.copy(notice = m) }

    override fun onCleared() { stop(); tts.shutdown() }
}
