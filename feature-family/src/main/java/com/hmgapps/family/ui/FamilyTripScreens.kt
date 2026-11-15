package com.hmgapps.family.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hmgapps.core.trip.RestAhead
import com.hmgapps.core.trip.TripBridge
import com.hmgapps.core.trip.TripMath
import com.hmgapps.core.ui.HmgColors
import com.hmgapps.core.ui.Panel
import com.hmgapps.core.ui.Pill
import com.hmgapps.family.domain.AgeGroup
import com.hmgapps.family.domain.CompanionRest
import com.hmgapps.family.domain.GameKind
import com.hmgapps.family.domain.JourneyMap
import com.hmgapps.family.domain.JourneyToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/*
 * v0.6.0 Family Care 새 탭 3개.
 * 1. 🗺 여정 지도  — NaviHelper 경로 정보(남은 거리·도착 예정 시간)를 아이용 캐릭터 지도로. 휴게소를 지나면 스탬프.
 * 2. 🎤 음성 놀이  — TextToSpeech + SpeechToText 로 끝말잇기·스무고개·지역 퀴즈. 화면을 보지 않고 목소리로.
 * 3. ☕ 휴식 추천  — 동승자(영유아·어르신·멀미) 기준 휴식 간격 → 경로 위 수유실·놀이방 휴게소 추천 → 음성으로 묻고 경유지 추가.
 */

// ------------------------------------------------------------------ 공통

@Composable
internal fun FamilyTabs(s: FamilyUi, vm: FamilyViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FamilyTab.entries.forEach { t ->
            FilterChip(
                selected = s.tab == t, onClick = { vm.setTab(t) },
                label = { Text(t.label, fontSize = if (s.driving) 20.sp else 16.sp, fontWeight = FontWeight.Bold) },
                modifier = Modifier.heightIn(min = if (s.driving) 60.dp else 48.dp),
            )
        }
    }
}

/** '다음 휴게소에서 쉬어갈까요?' — 어느 탭에서든 위에 뜬다. 버튼은 동승자용(크게). */
@Composable
internal fun RestAskBanner(s: FamilyUi, vm: FamilyViewModel) {
    val ask = s.restAsk ?: return
    val a = ask.pick.ahead
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp))
            .background(HmgColors.Warn.copy(alpha = 0.16f)).border(1.dp, HmgColors.Warn, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("☕", fontSize = 34.sp)
        Column(Modifier.weight(1f)) {
            if (ask.phase == AskPhase.DONE) {
                Text(ask.message ?: "", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HmgColors.Text)
            } else {
                Text("다음 휴게소에서 쉬어갈까요?", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = HmgColors.Text)
                Text(
                    "${a.area.name} · ${a.aheadKm} km · 약 ${a.aheadMin}분 앞" + (if (ask.pick.matched.isNotEmpty()) " · ${ask.pick.matched.joinToString(" · ")}" else ""),
                    fontSize = 15.sp, color = HmgColors.Sub,
                )
                val status = when (ask.phase) {
                    AskPhase.ASKING -> "🔊 묻는 중…"
                    AskPhase.LISTENING -> "🎤 대답을 듣고 있어요" + (s.heard?.let { " · \"$it\"" } ?: "")
                    else -> ask.message ?: "버튼으로 골라 주세요."
                }
                Text(status, fontSize = 14.sp, color = HmgColors.Warn)
            }
        }
        if (ask.phase == AskPhase.DONE) {
            OutlinedButton(onClick = vm::dismissRestAsk, modifier = Modifier.heightIn(min = 56.dp)) { Text("닫기", fontSize = 17.sp) }
        } else {
            Button(onClick = { vm.answerRest(true) }, modifier = Modifier.width(150.dp).heightIn(min = 64.dp)) { Text("네, 들를게요", fontSize = 18.sp) }
            OutlinedButton(onClick = { vm.answerRest(false) }, modifier = Modifier.width(120.dp).heightIn(min = 64.dp)) { Text("아니요", fontSize = 18.sp) }
        }
    }
}

// ------------------------------------------------------------------ 1. 여정 지도

@Composable
internal fun JourneyTab(s: FamilyUi, vm: FamilyViewModel) {
    if (s.driving) {
        // 주행 중: 그림 + 한 줄 + 큰 버튼 하나만
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            JourneyCard(s, Modifier.fillMaxWidth().weight(1f))
            Button(onClick = vm::speakJourney, modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp)) {
                Text("🔊 얼마나 남았어?", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        JourneyCard(s, Modifier.weight(2f).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth(), title = "목적지 이름") {
                JourneyMap.NICKNAMES.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { n ->
                            FilterChip(
                                selected = s.nickname == n, onClick = { vm.setNickname(n) },
                                label = { Text(n ?: "내비 목적지 (${s.trip.destination ?: "-"})", fontSize = 14.sp) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }
            Panel(Modifier.fillMaxWidth(), title = "남은 길 세기") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JourneyToken.entries.forEach { t ->
                        FilterChip(selected = s.token == t, onClick = { vm.setToken(t) }, label = { Text("${t.emoji} ${t.label}", fontSize = 15.sp) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("줄어들 때마다·스탬프 찍을 때 음성 안내", fontSize = 14.sp, color = HmgColors.Text, modifier = Modifier.weight(1f))
                    Switch(checked = s.journeyVoice, onCheckedChange = { vm.toggleJourneyVoice() })
                }
            }
            Button(onClick = vm::speakJourney, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text("🔊 얼마나 남았는지 듣기", fontSize = 18.sp) }
            OutlinedButton(onClick = vm::toggleDemoTrip, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(if (s.demoTrip) "⏹ 시연 여정 끄기" else "🎬 시연 여정 (서울역 → 부산역)", fontSize = 16.sp)
            }
            Text(
                "정보 출처: ${s.trip.source ?: "없음"}. Pleos 내비로 안내 중이면 NaviHelper 의 남은 거리·도착 예정 시간을, 내장 안내면 OSM 경로를 씁니다.",
                fontSize = 12.sp, color = HmgColors.Sub,
            )
        }
    }
}

@Composable
private fun JourneyCard(s: FamilyUi, modifier: Modifier) {
    val t = s.trip
    val units = s.unitsLeft
    Panel(modifier, title = if (t.active) "${s.placeName} 가는 길" else "여정 지도", trailing = {
        if (t.active) Pill("스탬프 ${t.stamps.size}개", HmgColors.Energy)
    }) {
        if (!t.active || units == null) {
            Spacer(Modifier.height(20.dp))
            Text("아직 길안내 중이 아니에요.", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = HmgColors.Text)
            Text("내비에서 목적지를 정하고 '안내 시작'을 누르면, 남은 길을 ${s.token.label} 개수로 보여 줘요.", fontSize = 16.sp, color = HmgColors.Sub)
            if (t.stamps.isNotEmpty()) Text("지난 여정 스탬프: " + t.stamps.joinToString(" · ") { it.name }, fontSize = 14.sp, color = HmgColors.Energy)
            return@Panel
        }
        Text(JourneyMap.phrase(s.placeName, units, s.token), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = HmgColors.Text)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(JourneyMap.UNITS) { i ->
                Text(s.token.emoji, fontSize = 30.sp, modifier = Modifier.alpha(if (i < units) 1f else 0.18f))
            }
        }
        RoadMap(s, Modifier.fillMaxWidth().weight(1f).heightIn(min = 150.dp))
        val eta = t.remainSeconds?.let { SimpleDateFormat("a h:mm", Locale.KOREAN).format(Date(System.currentTimeMillis() + it * 1000L)) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("남은 ${((t.remainMeters ?: 0) / 1000.0).let { if (it < 10) String.format(Locale.US, "%.1f", it) else it.toInt().toString() }} km", fontSize = 18.sp, color = HmgColors.Text, fontWeight = FontWeight.Bold)
            JourneyMap.durationText(t.remainSeconds)?.let { Text("· $it 남음", fontSize = 18.sp, color = HmgColors.Text) }
            eta?.let { Text("· 도착 예정 $it", fontSize = 18.sp, color = HmgColors.Accent) }
            JourneyMap.cartoonText(t.remainSeconds)?.let { Text("· 📺 $it", fontSize = 16.sp, color = HmgColors.Sub) }
        }
    }
}

/** 구불구불한 길 위에 🚗(지금) · 💮(지난 휴게소 스탬프) · 🅿(앞으로 만날 휴게소) · 🏠(목적지). */
@Composable
private fun RoadMap(s: FamilyUi, modifier: Modifier) {
    val t = s.trip
    val frac = (t.fraction ?: 0.0).toFloat()
    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp)).background(HmgColors.Bg)) {
        val w = maxWidth
        val h = maxHeight
        val pad = 34.dp
        fun x(f: Float): Dp = pad + (w - pad * 2) * f
        fun y(f: Float): Dp = h * 0.55f + h * 0.2f * sin(f * 2f * PI.toFloat() * 1.25f)

        Canvas(Modifier.fillMaxSize()) {
            val padPx = pad.toPx()
            val road = Path()
            val n = 80
            for (i in 0..n) {
                val f = i / n.toFloat()
                val px = padPx + (size.width - padPx * 2) * f
                val py = size.height * 0.55f + size.height * 0.2f * sin(f * 2f * PI.toFloat() * 1.25f)
                if (i == 0) road.moveTo(px, py) else road.lineTo(px, py)
            }
            drawPath(road, color = HmgColors.Line, style = Stroke(width = 26.dp.toPx(), cap = StrokeCap.Round))
            drawPath(road, color = HmgColors.Sub.copy(alpha = 0.6f), style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 16f))))
            // 지나온 길
            val done = Path()
            val m = (n * frac).toInt().coerceAtLeast(1)
            for (i in 0..m) {
                val f = (i / n.toFloat()).coerceAtMost(frac)
                val px = padPx + (size.width - padPx * 2) * f
                val py = size.height * 0.55f + size.height * 0.2f * sin(f * 2f * PI.toFloat() * 1.25f)
                if (i == 0) done.moveTo(px, py) else done.lineTo(px, py)
            }
            drawPath(done, color = HmgColors.Energy.copy(alpha = 0.55f), style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(HmgColors.Sub, radius = 6.dp.toPx(), center = Offset(padPx, size.height * 0.55f))
        }

        // 앞으로 만날 휴게소
        s.ahead.take(6).forEach { a ->
            val f = TripMath.fractionOf(t, a.aheadKm)?.toFloat() ?: return@forEach
            Marker("🅿", x(f), y(f) - 30.dp, label = a.area.name.removeSuffix("휴게소"))
        }
        // 지난 휴게소 스탬프
        t.stamps.forEach { st ->
            val f = st.fraction.toFloat()
            Marker("💮", x(f), y(f) - 30.dp, label = st.name.removeSuffix("휴게소"))
        }
        Marker("🏠", x(1f), y(1f) - 6.dp, label = s.placeName, big = true)
        Marker("🚗", x(frac), y(frac), big = true)
    }
}

@Composable
private fun Marker(emoji: String, cx: Dp, cy: Dp, label: String? = null, big: Boolean = false) {
    val box = if (big) 44.dp else 34.dp
    Column(
        Modifier.offset(x = cx - 40.dp, y = cy - box / 2).width(80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(box), contentAlignment = Alignment.Center) { Text(emoji, fontSize = if (big) 34.sp else 24.sp) }
        if (label != null) Text(label, fontSize = 11.sp, color = HmgColors.Sub, textAlign = TextAlign.Center, maxLines = 1)
    }
}

// ------------------------------------------------------------------ 2. 음성 놀이

@Composable
internal fun VoiceTab(s: FamilyUi, vm: FamilyViewModel) {
    val g = s.game
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // 게임 고르기
        Column(Modifier.weight(if (s.driving) 1f else 0.9f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GameKind.entries.forEach { k ->
                val on = g?.kind == k
                Row(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp))
                        .background(if (on) HmgColors.PanelHi else HmgColors.Panel)
                        .border(1.dp, if (on) HmgColors.Accent else HmgColors.Line, RoundedCornerShape(16.dp))
                        .clickable { vm.startGame(k) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(k.emoji, fontSize = 38.sp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(k.label, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = HmgColors.Text)
                        if (!s.driving) Text(
                            if (k == GameKind.REGION) "${k.blurb}" + (s.region?.let { " · 지금: ${it.label}" } ?: "") else k.blurb,
                            fontSize = 14.sp, color = HmgColors.Sub,
                        )
                    }
                    Text(if (on) "진행 중" else "▶ 시작", fontSize = 16.sp, color = HmgColors.Accent)
                }
            }
        }

        // 진행 중인 게임
        Panel(Modifier.weight(1.6f).fillMaxHeight(), title = g?.let { "${it.kind.emoji} ${it.kind.label}" } ?: "음성 놀이", trailing = {
            Pill(if (s.sttReady) "Pleos STT" else "음성 인식 없음 · 직접 입력", if (s.sttReady) HmgColors.Good else HmgColors.Warn)
            if (g != null) { Spacer(Modifier.width(6.dp)); Pill("점수 ${g.score}", HmgColors.Energy) }
        }) {
            if (g == null) {
                Text("왼쪽에서 놀이를 고르면 앱이 먼저 말하고, 아이가 대답하면 마이크로 들어요.", fontSize = 17.sp, color = HmgColors.Sub)
                Text("화면을 보지 않고 목소리로만 하니 운전자 주의 분산이 적어요.", fontSize = 15.sp, color = HmgColors.Sub)
                return@Panel
            }
            g.prompt?.let { Text(it, fontSize = if (s.driving) 34.sp else 28.sp, fontWeight = FontWeight.ExtraBold, color = HmgColors.Accent) }
            Text(g.say, fontSize = if (s.driving) 20.sp else 18.sp, color = HmgColors.Text)
            when {
                s.listening -> Text("🎤 듣는 중… " + (s.heard ?: ""), fontSize = 20.sp, color = HmgColors.Energy, fontWeight = FontWeight.Bold)
                s.heard != null -> Text("들은 말: \"${s.heard}\"", fontSize = 16.sp, color = HmgColors.Sub)
            }
            if (!s.driving) {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(g.history.takeLast(12).reversed()) { (mine, text) ->
                        Text((if (mine) "🧒 " else "🤖 ") + text, fontSize = 16.sp, color = if (mine) HmgColors.Text else HmgColors.Sub)
                    }
                }
                if (!s.sttReady) TypedAnswer(vm)
            } else Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val h = if (s.driving) 84.dp else 56.dp
                val fs = if (s.driving) 22.sp else 17.sp
                if (g.finished) {
                    Button(onClick = vm::gameAgain, modifier = Modifier.weight(1f).heightIn(min = h)) { Text(if (g.kind == GameKind.WORD_CHAIN) "다시 하기" else "다음 문제", fontSize = fs) }
                } else {
                    Button(onClick = vm::gameListen, enabled = s.sttReady && !s.listening, modifier = Modifier.weight(1f).heightIn(min = h)) { Text("🎤 말하기", fontSize = fs) }
                    OutlinedButton(onClick = vm::gameHint, modifier = Modifier.weight(1f).heightIn(min = h)) { Text(if (g.kind == GameKind.REGION) "정답 듣기" else "힌트", fontSize = fs) }
                }
                OutlinedButton(onClick = vm::stopGame, modifier = Modifier.weight(0.7f).heightIn(min = h)) { Text("그만", fontSize = fs) }
            }
        }
    }
}

/** 음성 인식이 없는 기기(휴대폰·일반 에뮬레이터)용 대답 입력. 정차 중에만 보인다. */
@Composable
private fun TypedAnswer(vm: FamilyViewModel) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("대답 입력 (예: 사과)") }, modifier = Modifier.weight(1f))
        Button(onClick = { vm.submitTyped(text); text = "" }, modifier = Modifier.heightIn(min = 56.dp)) { Text("보내기") }
    }
}

// ------------------------------------------------------------------ 3. 동승자 맞춤 휴식 추천

@Composable
internal fun RestTab(s: FamilyUi, vm: FamilyViewModel) {
    val pick = s.restPick
    if (s.driving) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            RestLine(s)
            pick?.let { PickCard(it.ahead, it.matched, it.onTime, big = true) } ?: Text("경로 위 휴게소 정보를 기다리는 중", fontSize = 20.sp, color = HmgColors.Sub)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(onClick = vm::askRest, enabled = pick != null, modifier = Modifier.weight(1f).heightIn(min = 84.dp)) { Text("🔊 쉬어갈지 물어보기", fontSize = 22.sp) }
                OutlinedButton(onClick = vm::markRested, modifier = Modifier.weight(1f).heightIn(min = 84.dp)) { Text("✅ 쉬었어요", fontSize = 22.sp) }
            }
        }
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth(), title = "함께 탄 사람") {
                AgeGroup.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { a ->
                            FilterChip(selected = a in s.ages, onClick = { vm.toggleAge(a) }, label = { Text("${a.emoji} ${a.label}", fontSize = 14.sp) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = s.elderly, onClick = vm::toggleElderly, label = { Text("👵 어르신 동승", fontSize = 14.sp) }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(selected = s.motionSick, onClick = vm::toggleMotionSick, label = { Text("🤢 멀미가 잦은 아이", fontSize = 14.sp) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Panel(Modifier.fillMaxWidth(), title = "휴식 간격 (가장 짧은 것을 따름)") {
                val strict = CompanionRest.strictest(s.companions)
                CompanionRest.reasons(s.companions).forEach { r ->
                    val on = r == strict
                    Text(
                        (if (on) "▶ " else "· ") + "${r.who}: ${r.why} · ${r.intervalMin}분",
                        fontSize = 15.sp, color = if (on) HmgColors.Text else HmgColors.Sub,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Text("간격은 앱 기본값(가정치)이며 의학적 기준이 아니에요. 운전자 피로도 경고가 먼저 나오면 그것을 따르세요.", fontSize = 12.sp, color = HmgColors.Sub)
            }
            Panel(Modifier.fillMaxWidth(), title = "다음 휴식") {
                RestLine(s)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::markRested, modifier = Modifier.heightIn(min = 52.dp)) { Text("✅ 휴식 완료") }
                    OutlinedButton(onClick = { vm.demoAddMinutes() }, modifier = Modifier.heightIn(min = 52.dp)) { Text("시연: 주행 +30분") }
                }
            }
        }
        Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth(), title = "추천 휴게소", trailing = {
                val want = CompanionRest.wanted(s.companions)
                if (want.isNotEmpty()) Pill("필요: " + want.joinToString(" · "), HmgColors.Accent)
            }) {
                if (pick == null) {
                    Text("경로 위(또는 가까운) 휴게소가 없어요. 길안내를 시작하거나 '🗺 여정 지도'의 시연 여정을 켜 보세요.", fontSize = 15.sp, color = HmgColors.Sub)
                } else {
                    PickCard(pick.ahead, pick.matched, pick.onTime, big = false)
                    Button(onClick = vm::askRest, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("🔊 \"다음 휴게소에서 쉬어갈까요?\" 물어보기", fontSize = 17.sp)
                    }
                    Text(
                        "'네'라고 대답하면 " + if (s.trip.source == TripBridge.SOURCE_DEMO) "시연 여정이라 경유지를 넣은 것처럼만 보여 줘요." else "NaviHelper(addWaypoint)로 이 휴게소를 경유지에 넣어요.",
                        fontSize = 12.sp, color = HmgColors.Sub,
                    )
                }
            }
            Panel(Modifier.fillMaxWidth().weight(1f), title = "경로 위 휴게소 ${s.ahead.size}곳") {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(s.ahead, key = { it.area.name }) { a -> AheadRow(a, highlight = a.area.name == pick?.ahead?.area?.name) }
                }
                Text("편의시설(수유실·놀이방)은 시연용 예시 데이터예요. 실제 정보로 바꿔야 해요.", fontSize = 12.sp, color = HmgColors.Sub)
            }
        }
    }
}

@Composable
private fun PickCard(a: RestAhead, matched: List<String>, onTime: Boolean, big: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(HmgColors.PanelHi).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("🅿 ${a.area.name}", fontSize = if (big) 32.sp else 24.sp, fontWeight = FontWeight.ExtraBold, color = HmgColors.Text)
        Text("${a.aheadKm} km 앞 · 약 ${a.aheadMin}분 뒤 도착", fontSize = if (big) 22.sp else 17.sp, color = HmgColors.Text)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (a.area.nursingRoom) Pill("🍼 수유실", if ("수유실" in matched) HmgColors.Good else HmgColors.Sub)
            if (a.area.kidsZone) Pill("🧸 놀이방", if ("놀이방" in matched) HmgColors.Good else HmgColors.Sub)
            if (!onTime) Pill("휴식 시각보다 늦게 도착", HmgColors.Warn)
        }
    }
}

@Composable
private fun AheadRow(a: RestAhead, highlight: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (highlight) HmgColors.PanelHi else HmgColors.Bg)
            .border(1.dp, if (highlight) HmgColors.Accent else HmgColors.Line, RoundedCornerShape(10.dp))
            .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(a.area.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HmgColors.Text, modifier = Modifier.weight(1f))
        if (a.area.amenityText.isNotEmpty()) Text(a.area.amenityText, fontSize = 13.sp, color = HmgColors.Energy, modifier = Modifier.padding(end = 10.dp))
        Text("${a.aheadKm} km · ${a.aheadMin}분", fontSize = 14.sp, color = HmgColors.Sub)
    }
}
