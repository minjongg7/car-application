package com.hmgapps.family.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hmgapps.core.energy.VehicleEnergy
import com.hmgapps.core.ui.AppLinks
import com.hmgapps.core.ui.AppScaffold
import com.hmgapps.core.ui.Dest
import com.hmgapps.core.ui.HmgColors
import com.hmgapps.core.ui.HmgShell
import com.hmgapps.core.ui.HmgTheme
import com.hmgapps.core.ui.Panel
import com.hmgapps.core.ui.Pill
import com.hmgapps.core.ui.SourcePill
import com.hmgapps.family.domain.AgeGroup
import com.hmgapps.family.domain.Bingo
import com.hmgapps.family.domain.ContentType
import com.hmgapps.family.domain.FamilyContent
import com.hmgapps.family.domain.CompanionRest

/**
 * 추가 화면 ③ Family Care — 탑승자 나이대별 콘텐츠(음성 동화·퀴즈·창밖 놀이·진정)
 * + v0.6.0: 여정 진행 지도 · 음성 놀이(STT) · 동승자 맞춤 휴식 추천 (FamilyTripScreens.kt).
 */
class FamilyCareActivity : ComponentActivity() {
    private val vm: FamilyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HmgTheme {
                val s by vm.state.collectAsStateWithLifecycle()
                HmgShell(current = Dest.FAMILY, onSelect = { d -> if (d != Dest.FAMILY) AppLinks.open(this@FamilyCareActivity, d) }) {
                    FamilyScreen(s, vm)
                }
            }
        }
    }

    override fun onStart() { super.onStart(); VehicleEnergy.acquire(this) }
    override fun onStop() { vm.stopSpeaking(); VehicleEnergy.release(); super.onStop() }
}

@Composable
private fun FamilyScreen(s: FamilyUi, vm: FamilyViewModel) {
    AppScaffold(
        title = "Family Care",
        subtitle = "나이별 콘텐츠 · 여정 지도 · 음성 놀이 · 동승자 맞춤 휴식",
        actions = {
            if (s.listening) Pill("🎤 듣는 중", HmgColors.Energy)
            if (s.driving) Pill("주행 중 · 음성 모드", HmgColors.Warn)
            SourcePill(s.vehicleLive)
        },
    ) {
        FamilyTabs(s, vm)
        RestAskBanner(s, vm)
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp)) {
            when (s.tab) {
                FamilyTab.CONTENT -> if (s.driving) DrivingMode(s, vm) else ParkedMode(s, vm)
                FamilyTab.JOURNEY -> JourneyTab(s, vm)
                FamilyTab.VOICE -> VoiceTab(s, vm)
                FamilyTab.REST -> RestTab(s, vm)
            }
        }
    }
}

/**
 * 주행 중: 운전자 주의분산을 줄이기 위해 목록·본문 글자를 숨기고 큰 버튼 3개만 남긴다.
 * 콘텐츠는 TTS 로 들려주므로 화면을 볼 필요가 없다. (Pleos Driving Restriction 가이드 취지)
 */
@Composable
private fun DrivingMode(s: FamilyUi, vm: FamilyViewModel) {
    val c = s.selected
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(c?.let { "${it.type.emoji} ${it.title}" } ?: "콘텐츠를 고르려면 '다음 콘텐츠'", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HmgColors.Text, textAlign = TextAlign.Center)
        Text("운전자는 화면 대신 도로를 봐 주세요. 조작은 동승자가 해 주세요.", fontSize = 16.sp, color = HmgColors.Sub, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Button(onClick = vm::togglePlay, modifier = Modifier.width(220.dp).heightIn(min = 96.dp)) {
                Text(if (s.playing) "⏸ 멈춤" else "▶ 재생", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = vm::next, modifier = Modifier.width(220.dp).heightIn(min = 96.dp)) {
                Text(if (c?.type == ContentType.QUIZ) "정답 / 다음" else "다음 문장", fontSize = 22.sp)
            }
            OutlinedButton(onClick = vm::nextContent, modifier = Modifier.width(220.dp).heightIn(min = 96.dp)) {
                Text("다음 콘텐츠", fontSize = 22.sp)
            }
        }
        RestLine(s, Modifier.padding(top = 28.dp))
    }
}

@Composable
private fun ParkedMode(s: FamilyUi, vm: FamilyViewModel) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- 왼쪽: 탑승자 · 휴식 · 종류
        Column(Modifier.weight(0.95f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth(), title = "탑승자 나이대 (여러 명 선택)") {
                AgeGroup.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { a ->
                            FilterChip(
                                selected = a in s.ages, onClick = { vm.toggleAge(a) },
                                label = { Text("${a.emoji} ${a.label} · ${a.range}", fontSize = 15.sp) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }
            Panel(Modifier.fillMaxWidth(), title = "휴식 제안") {
                RestLine(s)
                Text(
                    CompanionRest.strictest(s.companions).let { r -> "${r.who} 기준 ${r.intervalMin}분마다 휴식을 권해요 (${r.why}, 앱 기본값). 자세히는 '☕ 휴식 추천' 탭." },
                    fontSize = 13.sp, color = HmgColors.Sub,
                )
            }
            Panel(Modifier.fillMaxWidth(), title = "콘텐츠 종류") {
                ContentType.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t ->
                            FilterChip(
                                selected = s.typeFilter == t, onClick = { vm.setType(t) },
                                label = { Text("${t.emoji} ${t.label}", fontSize = 14.sp) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }
        }

        // ---- 가운데: 나이대에 맞는 콘텐츠 목록
        Panel(Modifier.weight(1.15f).fillMaxHeight(), title = "추천 콘텐츠 ${s.list.size}개") {
            if (s.ages.isEmpty()) Text("탑승자 나이대를 하나 이상 골라 주세요. 지금은 전체를 보여줘요.", color = HmgColors.Warn, fontSize = 13.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.list, key = { it.id }) { c -> ContentCard(c, selected = c.id == s.selectedId, ages = s.ages) { vm.select(c) } }
            }
        }

        // ---- 오른쪽: 재생 중
        Panel(Modifier.weight(1.4f).fillMaxHeight(), title = "재생", trailing = {
            if (!s.ttsReady) Pill("음성 준비 중…", HmgColors.Warn) else Pill((if (s.playing) "읽는 중 · " else "") + (s.ttsEngine ?: "TTS"), if (s.ttsEngine == "Pleos TTS") HmgColors.Good else HmgColors.Accent)
        }) {
            val c = s.selected
            if (c == null) {
                Text("왼쪽 목록에서 콘텐츠를 고르면 음성으로 읽어 줘요.", color = HmgColors.Sub, fontSize = 15.sp)
                return@Panel
            }
            Text("${c.type.emoji} ${c.title}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = HmgColors.Text)
            Text("${c.type.label} · 약 ${c.minutes}분 · " + c.ages.sortedBy { it.ordinal }.joinToString(", ") { it.label }, fontSize = 13.sp, color = HmgColors.Sub)
            Spacer(Modifier.height(4.dp))
            when (c.type) {
                ContentType.QUIZ -> QuizView(c, s)
                ContentType.GAME -> BingoView(c, s, vm)
                else -> LinesView(c, s)
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = vm::togglePlay, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(if (s.playing) "⏸ 멈춤" else "▶ 재생", fontSize = 18.sp) }
                OutlinedButton(onClick = vm::next, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(when {
                        c.type == ContentType.QUIZ && !s.showAnswer -> "정답 듣기"
                        c.type == ContentType.QUIZ -> "다음 문제"
                        else -> "다음 문장"
                    }, fontSize = 17.sp)
                }
                OutlinedButton(onClick = vm::nextContent, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("다음 콘텐츠", fontSize = 17.sp) }
            }
        }
    }
}

@Composable
internal fun RestLine(s: FamilyUi, modifier: Modifier = Modifier) {
    val left = s.minutesUntilRest
    val (text, color) = when {
        left == null -> "주행 시간 정보 없음 (Pleos 차량 연결 또는 길안내 시작 시 표시)" to HmgColors.Sub
        left <= 0 -> "지금 휴게소에 들러 쉬어 가요! (주행 ${s.effectiveDrivingMin}분)" to HmgColors.Bad
        left <= 15 -> "곧 쉴 시간이에요 · ${left}분 남음" to HmgColors.Warn
        else -> "다음 휴식까지 ${left}분 · 주행 ${s.effectiveDrivingMin}분" to HmgColors.Good
    }
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color, modifier = modifier)
}

@Composable
private fun ContentCard(c: FamilyContent, selected: Boolean, ages: Set<AgeGroup>, onClick: () -> Unit) {
    val shared = c.ages.count(ages::contains)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) HmgColors.PanelHi else HmgColors.Bg)
            .border(1.dp, if (selected) HmgColors.Accent else HmgColors.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c.type.emoji, fontSize = 26.sp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(c.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = HmgColors.Text)
            Text("${c.type.label} · ${c.minutes}분", fontSize = 13.sp, color = HmgColors.Sub)
        }
        if (shared >= 2) Pill("함께 즐기기", HmgColors.Energy)
    }
}

@Composable
private fun LinesView(c: FamilyContent, s: FamilyUi) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        c.lines.forEachIndexed { i, line ->
            val now = i == s.lineIndex && s.playing
            Text(
                line, fontSize = if (now) 20.sp else 16.sp,
                fontWeight = if (now) FontWeight.Bold else FontWeight.Normal,
                color = when { now -> HmgColors.Accent; i < s.lineIndex -> HmgColors.Sub; else -> HmgColors.Text },
            )
        }
    }
}

@Composable
private fun QuizView(c: FamilyContent, s: FamilyUi) {
    val q = c.quiz.getOrNull(s.quizIndex) ?: return
    Text("문제 ${s.quizIndex + 1} / ${c.quiz.size}", fontSize = 13.sp, color = HmgColors.Sub)
    Text(q.question, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = HmgColors.Text)
    if (s.showAnswer) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HmgColors.Good.copy(alpha = 0.15f)).padding(14.dp)) {
            Text(q.answer, fontSize = 18.sp, color = HmgColors.Good)
        }
    } else {
        Text("다 같이 생각해 보고 '정답 듣기'를 눌러요.", fontSize = 14.sp, color = HmgColors.Sub)
    }
}

@Composable
private fun BingoView(c: FamilyContent, s: FamilyUi, vm: FamilyViewModel) {
    c.lines.firstOrNull()?.let { Text(it, fontSize = 15.sp, color = HmgColors.Sub) }
    val lines = Bingo.lines(s.bingoMarked)
    c.bingo.take(9).chunked(3).forEachIndexed { r, row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEachIndexed { k, label ->
                val i = r * 3 + k
                val on = i in s.bingoMarked
                Box(
                    Modifier.weight(1f).heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) HmgColors.Good.copy(alpha = 0.25f) else HmgColors.Bg)
                        .border(1.dp, if (on) HmgColors.Good else HmgColors.Line, RoundedCornerShape(12.dp))
                        .clickable { vm.toggleBingo(i) }.padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text((if (on) "✔ " else "") + label, fontSize = 15.sp, color = HmgColors.Text, textAlign = TextAlign.Center) }
            }
        }
    }
    if (lines > 0) Text("🎉 빙고 $lines 줄!", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = HmgColors.Good)
}
