package com.hmgapps.family.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.hmgapps.core.pleos.PleosVehicleHub
import com.hmgapps.core.trip.GeoPt
import com.hmgapps.core.trip.RestAhead
import com.hmgapps.core.trip.RestAreaCatalog
import com.hmgapps.core.trip.RouteLine
import com.hmgapps.core.trip.TripBridge
import com.hmgapps.core.trip.TripMath
import com.hmgapps.core.trip.TripProgress
import com.hmgapps.core.trip.TripUpdate
import com.hmgapps.family.domain.AgeGroup
import com.hmgapps.family.domain.Companions
import com.hmgapps.family.domain.CompanionRest
import com.hmgapps.family.domain.ContentType
import com.hmgapps.family.domain.FamilyCatalog
import com.hmgapps.family.domain.FamilyContent
import com.hmgapps.family.domain.GameKind
import com.hmgapps.family.domain.JourneyMap
import com.hmgapps.family.domain.JourneyToken
import com.hmgapps.family.domain.RegionLocator
import com.hmgapps.family.domain.Region
import com.hmgapps.family.domain.RegionQuizGame
import com.hmgapps.family.domain.RestPick
import com.hmgapps.family.domain.RiddleGame
import com.hmgapps.family.domain.Turn
import com.hmgapps.family.domain.VoiceGame
import com.hmgapps.family.domain.WordChainGame
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Family Care 화면 탭. */
enum class FamilyTab(val label: String) {
    CONTENT("🎧 콘텐츠"),
    JOURNEY("🗺 여정 지도"),
    VOICE("🎤 음성 놀이"),
    REST("☕ 휴식 추천"),
}

/** 음성 놀이 화면 상태. */
data class GameUi(
    val kind: GameKind,
    val say: String = "",
    val prompt: String? = null,
    val finished: Boolean = false,
    val score: Int = 0,
    val history: List<Pair<Boolean, String>> = emptyList(),
)

/** '다음 휴게소에서 쉬어갈까요?' 진행 단계. */
enum class AskPhase { ASKING, LISTENING, WAITING_BUTTON, DONE }

data class RestAsk(val pick: RestPick, val phase: AskPhase, val message: String? = null)

data class FamilyUi(
    val ages: Set<AgeGroup> = setOf(AgeGroup.KID),
    val typeFilter: ContentType? = null,
    val selectedId: String? = null,
    val playing: Boolean = false,
    val lineIndex: Int = 0,
    val quizIndex: Int = 0,
    val showAnswer: Boolean = false,
    val bingoMarked: Set<Int> = emptySet(),
    /** Pleos Vehicle SDK 기어가 주행(D/R/1~9)이면 true → 음성 모드(최소 화면). */
    val driving: Boolean = false,
    /** Pleos Vehicle SDK 현재 트립 주행 시간(분). 실측이 없으면 null. */
    val drivingMinutes: Int? = null,
    val vehicleLive: Boolean = false,
    val ttsReady: Boolean = false,
    /** 사용 중인 음성 엔진 ("Pleos TTS" / "Android TTS"). */
    val ttsEngine: String? = null,

    // ---- v0.6.0 여정 지도 · 음성 놀이 · 동승자 맞춤 휴식
    val tab: FamilyTab = FamilyTab.CONTENT,
    /** 내비가 공유하는 여정 (TripBridge). */
    val trip: TripProgress = TripProgress(),
    /** 목적지 별명 ("할머니 집"). null = 내비 목적지 이름. */
    val nickname: String? = null,
    val token: JourneyToken = JourneyToken.APPLE,
    /** 토큰이 줄 때마다·스탬프를 찍을 때마다 음성으로 알려 줌. */
    val journeyVoice: Boolean = true,
    /** Pleos SpeechToText 사용 가능. false 면 직접 입력으로 대답. */
    val sttReady: Boolean = false,
    val listening: Boolean = false,
    /** 듣는 중 인식된 글자 / 마지막으로 들은 대답. */
    val heard: String? = null,
    val game: GameUi? = null,
    val region: Region? = null,
    val elderly: Boolean = false,
    val motionSick: Boolean = false,
    /** 마지막으로 쉰 시점의 주행 시간(분). '휴식 완료'를 누르면 기록. */
    val lastRestAtMin: Int? = null,
    /** 실측 트립 시간 → 없으면 여정 경과 시간 → (시연 여정이면 시연 주행 시간) + 시연 보정. */
    val effectiveDrivingMin: Int? = null,
    val demoOffsetMin: Int = 0,
    val ahead: List<RestAhead> = emptyList(),
    val restPick: RestPick? = null,
    val restAsk: RestAsk? = null,
    val demoTrip: Boolean = false,
) {
    val selected: FamilyContent? get() = FamilyCatalog.byId(selectedId)
    val list: List<FamilyContent>
        get() = FamilyCatalog.forAges(ages).filter { typeFilter == null || it.type == typeFilter }
    val companions: Companions get() = Companions(ages, elderly, motionSick)
    val minutesUntilRest: Int? get() = CompanionRest.minutesUntilRest(companions, effectiveDrivingMin, lastRestAtMin)
    val unitsLeft: Int? get() = JourneyMap.unitsLeft(trip.remainMeters, trip.totalMeters)
    val placeName: String get() = JourneyMap.placeName(nickname, trip.destination)
}

class FamilyViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("family_care", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadPrefs(FamilyUi()))
    val state: StateFlow<FamilyUi> = _state.asStateFlow()

    /** Pleos TextToSpeech SDK 우선, 없으면 Android TTS. 한 문장씩 읽고 끝나면 다음 문장으로 넘어간다. */
    private val speaker = Speaker(app) { engine ->
        _state.update { it.copy(ttsReady = engine != null, ttsEngine = engine) }
    }

    /** Pleos SpeechToText SDK (ON_DEVICE). 없으면 화면 입력으로 대신한다. */
    private val listener = Listener(app) { ok -> _state.update { it.copy(sttReady = ok) } }

    /** 재생 세대 번호: 멈추거나 다른 콘텐츠를 고르면 올려서, 이전 재생의 '다음 문장' 콜백을 무시한다. */
    private var generation = 0

    /** 안내 음성(게임·휴식 질문·여정 알림)을 읽는 중. 이때는 여정 알림을 끼워 넣지 않는다. */
    private var systemSpeaking = false

    private var game: VoiceGame? = null
    private var lastTurn: Turn? = null

    // 여정 알림 상태
    private var announcedTripStart: Long? = null
    private var announcedUnits: Int? = null
    private var announcedStamps = 0
    /** 이 주행 시간(분) 전에는 휴식을 다시 묻지 않는다 ('아니요' 후 20분). */
    private var restSnoozeUntilMin = -1
    private var askedForSlot: Int? = null

    private var demoJob: Job? = null
    private var demoDrivingMin: Int? = null

    init {
        // 주행 여부·주행 시간: Pleos Vehicle SDK (PleosVehicleHub 는 Activity 의 VehicleEnergy.acquire 로 연결됨)
        viewModelScope.launch {
            PleosVehicleHub.state.collect { v ->
                val driving = when (v.gear) {
                    null, VehicleGear.GEAR_PARK, VehicleGear.GEAR_NEUTRAL, VehicleGear.GEAR_UNKNOWN -> false
                    else -> true
                }
                _state.update { it.copy(driving = driving, drivingMinutes = v.tripDrivingMinutes, vehicleLive = v.live) }
                refresh()
            }
        }
        // 내비가 공유하는 여정 (남은 거리·도착 예정 시간·위치·스탬프)
        viewModelScope.launch {
            TripBridge.state.collect { t ->
                _state.update { it.copy(trip = t, region = t.lat?.let { la -> t.lon?.let { lo -> RegionLocator.locate(la, lo) } } ?: it.region) }
                refresh()
            }
        }
        // 주행 시간은 분 단위로 흐르므로 가끔 다시 계산한다
        viewModelScope.launch {
            while (isActive) { delay(15_000); refresh() }
        }
    }

    // ---------------- 탭 / 탑승자 / 필터
    fun setTab(t: FamilyTab) = _state.update { it.copy(tab = t) }

    fun toggleAge(a: AgeGroup) {
        _state.update { s -> s.copy(ages = if (a in s.ages) s.ages - a else s.ages + a) }
        savePrefs(); refresh()
    }

    fun toggleElderly() { _state.update { it.copy(elderly = !it.elderly) }; savePrefs(); refresh() }
    fun toggleMotionSick() { _state.update { it.copy(motionSick = !it.motionSick) }; savePrefs(); refresh() }

    fun setType(t: ContentType?) = _state.update { it.copy(typeFilter = if (it.typeFilter == t) null else t) }

    // ---------------- 1. 여정 진행 지도
    fun setNickname(n: String?) { _state.update { it.copy(nickname = n) }; savePrefs() }
    fun setToken(t: JourneyToken) { _state.update { it.copy(token = t) }; savePrefs() }
    fun toggleJourneyVoice() { _state.update { it.copy(journeyVoice = !it.journeyVoice) }; savePrefs() }

    /** 지금 남은 길을 한 번 읽어 준다 (아이가 "얼마나 남았어?" 할 때). */
    fun speakJourney() {
        val s = _state.value
        val units = s.unitsLeft
        val text = if (!s.trip.active || units == null) "아직 길안내가 시작되지 않았어요. 내비에서 목적지를 정하면 알려 줄게요."
        else buildString {
            append(JourneyMap.phrase(s.placeName, units, s.token))
            JourneyMap.cartoonText(s.trip.remainSeconds)?.let { append(" "); append(it); append(".") }
        }
        saySystem(text)
    }

    /** 시연 여정: 서울역 → 경부선 휴게소들 → 부산역 을 빠르게 가상 주행 (에뮬레이터·발표용). */
    fun toggleDemoTrip() {
        if (demoJob != null) { stopDemo(); return }
        val cur = TripBridge.state.value
        if (cur.active && cur.source != TripBridge.SOURCE_DEMO && !cur.arrived) {
            saySystem("지금은 실제 길안내 중이라 시연 여정을 켤 수 없어요.")
            return
        }
        val path = DEMO_PATH
        val line = RouteLine(path)
        val totalKm = line.lengthKm
        _state.update { it.copy(demoTrip = true, lastRestAtMin = null) }
        restSnoozeUntilMin = -1; askedForSlot = null
        demoJob = viewModelScope.launch {
            var along = 0.0
            while (isActive) {
                val p = pointAt(line, along)
                val remainKm = (totalKm - along).coerceAtLeast(0.0)
                demoDrivingMin = (along / TripMath.FALLBACK_KMH * 60).toInt()
                TripBridge.update(
                    TripUpdate(
                        TripBridge.SOURCE_DEMO, "부산역", destLat = path.last().lat, destLon = path.last().lon,
                        remainMeters = (remainKm * 1000).toInt(), remainSeconds = (remainKm / TripMath.FALLBACK_KMH * 3600).toInt(),
                        totalMeters = (totalKm * 1000).toInt(), lat = p.lat, lon = p.lon, route = path, arrived = remainKm <= 0.05,
                    )
                )
                if (remainKm <= 0.05) break
                // 휴식을 묻는 동안에는 멈춰서 기다린다 (실제로는 차가 계속 달리지만 시연에서 대답할 시간을 준다)
                val asking = _state.value.restAsk?.phase.let { it == AskPhase.ASKING || it == AskPhase.LISTENING }
                delay(500)
                if (!asking) along = (along + DEMO_KM_PER_TICK).coerceAtMost(totalKm)
            }
            demoJob = null
            _state.update { it.copy(demoTrip = false) }
        }
    }

    private fun stopDemo() {
        demoJob?.cancel(); demoJob = null
        demoDrivingMin = null
        TripBridge.end(TripBridge.SOURCE_DEMO)
        _state.update { it.copy(demoTrip = false) }
        refresh()
    }

    /** 발표용: 주행 시간을 30분 앞당겨 휴식 추천을 바로 확인. */
    fun demoAddMinutes(m: Int = 30) { _state.update { it.copy(demoOffsetMin = it.demoOffsetMin + m) }; refresh() }

    // ---------------- 2. 음성 놀이
    fun startGame(kind: GameKind) {
        stopSpeaking()
        val g: VoiceGame = when (kind) {
            GameKind.WORD_CHAIN -> WordChainGame()
            GameKind.RIDDLE -> RiddleGame()
            GameKind.REGION -> RegionQuizGame(_state.value.region)
        }
        game = g
        _state.update { it.copy(tab = if (it.driving) it.tab else FamilyTab.VOICE, heard = null) }
        runTurn(g.start())
    }

    /** '말하기' 버튼: 지금 듣는다. */
    fun gameListen() { if (game != null) listenForGame() }

    fun gameHint() { game?.let { runTurn(it.skip()) } }

    /** 끝난 뒤 '다음 문제 / 다시 하기'. */
    fun gameAgain() {
        val g = game ?: return
        runTurn(if (g is RegionQuizGame) g.nextQuestion() else g.start())
    }

    /** STT 가 없을 때(휴대폰·일반 에뮬레이터) 직접 입력한 대답. */
    fun submitTyped(text: String) {
        if (text.isBlank()) return
        val ask = _state.value.restAsk
        if (ask != null && ask.phase != AskPhase.DONE) { onRestAnswer(text); return }
        val g = game ?: return
        listener.cancel()
        _state.update { it.copy(listening = false, heard = text) }
        runTurn(g.onHeard(text))
    }

    fun stopGame() {
        stopSpeaking()
        game = null; lastTurn = null
        _state.update { it.copy(game = null, heard = null) }
    }

    private fun runTurn(t: Turn) {
        val g = game ?: return
        lastTurn = t
        _state.update { it.copy(game = GameUi(g.kind, t.say, t.prompt, t.finished, g.score, g.history.toList())) }
        if (t.say.isBlank()) { if (t.listen) listenForGame(); return }
        saySystem(t.say) { if (t.listen && game === g) listenForGame() }
    }

    private fun listenForGame() {
        val g = game ?: return
        if (!_state.value.sttReady) { _state.update { it.copy(listening = false) }; return } // 화면 입력으로 대답
        val gen = generation
        _state.update { it.copy(listening = true, heard = null) }
        listener.listen(partial = { p -> _state.update { it.copy(heard = p) } }) { text ->
            if (gen != generation || game !== g) return@listen
            _state.update { it.copy(listening = false, heard = text) }
            runTurn(if (text.isNullOrBlank()) g.onSilence() else g.onHeard(text))
        }
    }

    // ---------------- 3. 동승자 맞춤 휴식 추천
    /** '지금 추천 받기' 또는 휴식 시각이 가까워지면 자동으로: 음성으로 묻고 대답을 듣는다. */
    fun askRest() {
        val pick = _state.value.restPick ?: run { saySystem("경로 위에서 들를 휴게소를 찾지 못했어요."); return }
        game = null
        stopSpeaking()
        askedForSlot = slotKey()
        _state.update { it.copy(restAsk = RestAsk(pick, AskPhase.ASKING), game = null) }
        saySystem(CompanionRest.question(pick)) { listenRest(retry = true) }
    }

    private fun listenRest(retry: Boolean) {
        if (!_state.value.sttReady) { setAsk(AskPhase.WAITING_BUTTON); return }
        val gen = generation
        setAsk(AskPhase.LISTENING)
        _state.update { it.copy(listening = true, heard = null) }
        listener.listen(timeoutMs = 7_000L, partial = { p -> _state.update { it.copy(heard = p) } }) { text ->
            if (gen != generation) return@listen
            _state.update { it.copy(listening = false, heard = text) }
            val yes = text?.let(CompanionRest::parseYesNo)
            when {
                yes != null -> answerRest(yes)
                retry -> saySystem("잘 못 들었어요. 네, 또는 아니요로 말해 주세요.") { listenRest(retry = false) }
                else -> setAsk(AskPhase.WAITING_BUTTON, "버튼으로 골라 주세요.")
            }
        }
    }

    private fun onRestAnswer(text: String) {
        val yes = CompanionRest.parseYesNo(text)
        if (yes == null) setAsk(AskPhase.WAITING_BUTTON, "'네' 또는 '아니요'로 대답해 주세요.") else answerRest(yes)
    }

    /** 네 → NaviHelper 로 그 휴게소를 경유지에 추가. 아니요 → 20분 뒤에 다시. */
    fun answerRest(yes: Boolean) {
        val ask = _state.value.restAsk ?: return
        listener.cancel()
        _state.update { it.copy(listening = false) }
        if (!yes) {
            restSnoozeUntilMin = (_state.value.effectiveDrivingMin ?: 0) + SNOOZE_MIN
            askedForSlot = null // 20분 뒤에는 다시 물을 수 있게
            setAsk(AskPhase.DONE, "알겠어요. ${SNOOZE_MIN}분 뒤에 다시 여쭤볼게요.")
            saySystem("알겠어요. ${SNOOZE_MIN}분 뒤에 다시 여쭤볼게요.")
            return
        }
        val area = ask.pick.ahead.area
        if (_state.value.trip.source == TripBridge.SOURCE_DEMO) {
            setAsk(AskPhase.DONE, "시연 여정: ${area.name}를 경유지로 넣었어요 (실제 내비 호출 없음).")
            saySystem("좋아요! ${area.name}에서 쉬어 가요.")
            return
        }
        val sent = TripBridge.addRestStop(area) { msg ->
            setAsk(AskPhase.DONE, msg)
            saySystem(msg)
        }
        if (!sent) {
            val msg = "내비 화면이 닫혀 있어 경유지를 넣지 못했어요. 내비 탭을 연 뒤 다시 눌러 주세요."
            setAsk(AskPhase.DONE, msg)
            saySystem(msg)
        } else {
            setAsk(AskPhase.DONE, "${area.name}를 경유지로 추가하는 중…")
        }
    }

    fun dismissRestAsk() { listener.cancel(); _state.update { it.copy(restAsk = null, listening = false) } }

    /** 휴게소에서 쉬었으면 눌러 간격을 다시 센다. */
    fun markRested() {
        _state.update { it.copy(lastRestAtMin = it.effectiveDrivingMin ?: 0, restAsk = null) }
        askedForSlot = null
        refresh()
    }

    private fun setAsk(phase: AskPhase, message: String? = null) =
        _state.update { s -> s.restAsk?.let { s.copy(restAsk = it.copy(phase = phase, message = message ?: it.message)) } ?: s }

    private fun slotKey(): Int = _state.value.lastRestAtMin ?: 0

    // ---------------- 파생 상태 · 자동 알림
    private fun refresh() {
        val now = System.currentTimeMillis()
        _state.update { s ->
            val base = when {
                s.trip.active && s.trip.source == TripBridge.SOURCE_DEMO -> demoDrivingMin
                else -> s.drivingMinutes ?: s.trip.takeIf { it.active }?.elapsedMinutes(now)
            }
            val driving = base?.plus(s.demoOffsetMin) ?: if (s.demoOffsetMin > 0) s.demoOffsetMin else null
            val ahead = TripMath.restAreasAhead(s.trip)
            val left = CompanionRest.minutesUntilRest(s.companions, driving, s.lastRestAtMin)
            s.copy(effectiveDrivingMin = driving, ahead = ahead, restPick = CompanionRest.pick(s.companions, ahead, left))
        }
        autoAnnounce()
    }

    private fun voiceBusy(): Boolean {
        val s = _state.value
        return systemSpeaking || s.playing || s.listening ||
            (s.restAsk?.phase.let { it == AskPhase.ASKING || it == AskPhase.LISTENING })
    }

    private fun autoAnnounce() {
        val s = _state.value
        val t = s.trip
        if (t.startedAtMs != announcedTripStart) {
            announcedTripStart = t.startedAtMs
            announcedUnits = null
            announcedStamps = t.stamps.size
        }
        if (voiceBusy()) return

        // 1) 휴식 시각이 가까우면 먼저 묻는다 (게임 중이 아닐 때)
        val left = s.minutesUntilRest
        val driving = s.effectiveDrivingMin ?: -1
        if (left != null && left <= CompanionRest.ASK_BEFORE_MIN && s.restPick != null && game == null &&
            askedForSlot != slotKey() && driving >= restSnoozeUntilMin && (s.trip.active || s.driving)
        ) {
            askRest(); return
        }
        if (!s.journeyVoice || !t.active) return

        // 2) 새 스탬프
        if (t.stamps.size > announcedStamps) {
            announcedStamps = t.stamps.size
            saySystem(JourneyMap.stampPhrase(t.stamps.last().name, t.stamps.size)); return
        }
        // 3) 남은 사과(토큰)가 줄면
        val units = s.unitsLeft ?: return
        val prev = announcedUnits
        announcedUnits = units
        if (prev != null && units < prev) saySystem(JourneyMap.phrase(s.placeName, units, s.token))
    }

    // ---------------- 콘텐츠 재생 (기존)
    fun select(c: FamilyContent) {
        stopSpeaking()
        _state.update { it.copy(selectedId = c.id, lineIndex = 0, quizIndex = 0, showAnswer = false, bingoMarked = emptySet(), playing = false) }
        play()
    }

    /** 현재 콘텐츠를 처음(또는 현재 문제)부터 읽는다. */
    fun play() {
        val s = _state.value
        val c = s.selected ?: s.list.firstOrNull()?.also { _state.update { st -> st.copy(selectedId = it.id) } } ?: return
        when (c.type) {
            ContentType.QUIZ -> speakQuiz(c, _state.value.quizIndex, _state.value.showAnswer)
            // 끝까지 들었으면 처음부터, 중간에 멈췄으면 그 문장부터 다시
            else -> speakLines(c.lines, from = if (s.lineIndex >= c.lines.lastIndex) 0 else s.lineIndex)
        }
    }

    fun togglePlay() { if (_state.value.playing) stopSpeaking() else play() }

    /** 이야기: 다음 문장 / 퀴즈: 정답 → 다음 문제 / 빙고: 안내 다시 읽기. */
    fun next() {
        val s = _state.value
        val c = s.selected ?: return play()
        when (c.type) {
            ContentType.QUIZ -> {
                if (!s.showAnswer) {
                    _state.update { it.copy(showAnswer = true) }
                } else {
                    _state.update { it.copy(showAnswer = false, quizIndex = (it.quizIndex + 1) % c.quiz.size) }
                }
                speakQuiz(c, _state.value.quizIndex, _state.value.showAnswer)
            }
            else -> {
                val nextLine = s.lineIndex + 1
                if (nextLine < c.lines.size) speakLines(c.lines, nextLine) else nextContent()
            }
        }
    }

    /** 같은 나이대 목록의 다음 콘텐츠로 (주행 중 음성 모드의 '다음 콘텐츠'). */
    fun nextContent() {
        val list = _state.value.list
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == _state.value.selectedId }
        select(list[(i + 1) % list.size])
    }

    /** 모든 음성(콘텐츠·안내)과 듣기를 멈춘다. */
    fun stopSpeaking() {
        generation++
        systemSpeaking = false
        speaker.stop()
        listener.cancel()
        _state.update { it.copy(playing = false, listening = false) }
    }

    fun toggleBingo(i: Int) = _state.update { s ->
        s.copy(bingoMarked = if (i in s.bingoMarked) s.bingoMarked - i else s.bingoMarked + i)
    }

    /** 안내 음성 한 번. 다 읽으면 then. 음성 엔진이 없으면 바로 then. */
    private fun saySystem(text: String, then: () -> Unit = {}) {
        val gen = ++generation
        speaker.stop()
        listener.cancel()
        _state.update { it.copy(playing = false, listening = false) }
        if (!_state.value.ttsReady || text.isBlank()) { systemSpeaking = false; then(); return }
        systemSpeaking = true
        speaker.speak(text) {
            if (gen != generation) return@speak
            systemSpeaking = false
            then()
        }
    }

    private fun speakLines(lines: List<String>, from: Int) {
        if (lines.isEmpty()) return
        val gen = ++generation
        speaker.stop()
        listener.cancel()
        systemSpeaking = false
        // 음성 엔진이 없으면 글자만 보여 주고 '재생 중' 표시는 하지 않는다.
        _state.update { it.copy(playing = it.ttsReady, lineIndex = from, listening = false) }
        if (!_state.value.ttsReady) return
        speakLine(lines, from, gen)
    }

    private fun speakLine(lines: List<String>, idx: Int, gen: Int) {
        if (gen != generation) return
        if (idx > lines.lastIndex) { _state.update { it.copy(playing = false) }; return }
        _state.update { it.copy(lineIndex = idx) }
        speaker.speak(lines[idx]) { speakLine(lines, idx + 1, gen) }
    }

    private fun speakQuiz(c: FamilyContent, index: Int, answer: Boolean) {
        val q = c.quiz.getOrNull(index) ?: return
        val gen = ++generation
        speaker.stop()
        listener.cancel()
        systemSpeaking = false
        _state.update { it.copy(playing = it.ttsReady, listening = false) }
        if (!_state.value.ttsReady) return
        speaker.speak(if (answer) q.answer else q.question) {
            if (gen == generation) _state.update { it.copy(playing = false) }
        }
    }

    // ---------------- 저장
    private fun loadPrefs(s: FamilyUi): FamilyUi {
        val ages = prefs.getString(KEY_AGES, null)?.split(",")
            ?.mapNotNull { n -> AgeGroup.entries.firstOrNull { it.name == n } }?.toSet() ?: s.ages
        return s.copy(
            ages = ages,
            elderly = prefs.getBoolean(KEY_ELDERLY, false),
            motionSick = prefs.getBoolean(KEY_SICK, false),
            nickname = prefs.getString(KEY_NICK, null),
            token = JourneyToken.entries.firstOrNull { it.name == prefs.getString(KEY_TOKEN, null) } ?: JourneyToken.APPLE,
            journeyVoice = prefs.getBoolean(KEY_JVOICE, true),
        )
    }

    private fun savePrefs() {
        val s = _state.value
        prefs.edit()
            .putString(KEY_AGES, s.ages.joinToString(",") { it.name })
            .putBoolean(KEY_ELDERLY, s.elderly)
            .putBoolean(KEY_SICK, s.motionSick)
            .putString(KEY_NICK, s.nickname)
            .putString(KEY_TOKEN, s.token.name)
            .putBoolean(KEY_JVOICE, s.journeyVoice)
            .apply()
    }

    override fun onCleared() {
        generation++
        if (demoJob != null) { demoJob?.cancel(); TripBridge.end(TripBridge.SOURCE_DEMO) }
        listener.release()
        speaker.release()
        super.onCleared()
    }

    private companion object {
        const val KEY_AGES = "ages"
        const val KEY_ELDERLY = "elderly"
        const val KEY_SICK = "motion_sick"
        const val KEY_NICK = "nickname"
        const val KEY_TOKEN = "token"
        const val KEY_JVOICE = "journey_voice"
        const val SNOOZE_MIN = 20
        /** 시연 속도: 0.5초마다 1.2km (약 400km 를 3분 안에). */
        const val DEMO_KM_PER_TICK = 1.2

        val DEMO_PATH: List<GeoPt> = listOf(GeoPt(37.5547, 126.9707)) +
            listOf("죽전휴게소", "기흥휴게소", "오산휴게소", "안성휴게소", "망향휴게소", "천안삼거리휴게소", "옥산휴게소",
                "죽암휴게소", "신탄진휴게소", "금강휴게소", "추풍령휴게소", "칠곡휴게소")
                .mapNotNull(RestAreaCatalog::byName).map { GeoPt(it.lat, it.lon) } +
            listOf(GeoPt(35.8794, 128.6286), GeoPt(35.1152, 129.0422)) // 동대구 → 부산역

        fun pointAt(line: RouteLine, km: Double): GeoPt {
            val pts = line.points
            val c = line.cumKm
            if (km <= 0) return pts.first()
            for (i in 1 until pts.size) {
                if (c[i] >= km) {
                    val seg = (c[i] - c[i - 1]).coerceAtLeast(1e-9)
                    val f = (km - c[i - 1]) / seg
                    return GeoPt(pts[i - 1].lat + (pts[i].lat - pts[i - 1].lat) * f, pts[i - 1].lon + (pts[i].lon - pts[i - 1].lon) * f)
                }
            }
            return pts.last()
        }
    }
}
