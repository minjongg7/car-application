package com.fatigueguard.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fatigueguard.BuildConfig
import com.fatigueguard.data.DriveProfile
import com.fatigueguard.domain.Capabilities
import com.fatigueguard.domain.Consent
import com.fatigueguard.domain.Messages
import com.fatigueguard.domain.RestAreaCandidate
import com.fatigueguard.domain.Urgency
import com.hmgapps.core.ui.AppScaffold
import com.hmgapps.core.ui.HmgColors
import com.hmgapps.core.ui.Metric
import com.hmgapps.core.ui.Panel
import com.hmgapps.core.ui.Pill

private fun Urgency.color() = when (this) {
    Urgency.GREEN -> HmgColors.Good
    Urgency.YELLOW -> HmgColors.Warn
    Urgency.RED -> HmgColors.Bad
}

/**
 * 피로도 가드 화면. 내비 홈·Energy Hub·Predictive Care 와 같은 공용 컴포넌트(AppScaffold · Panel · Metric · Pill)와
 * 팔레트(HmgColors)만 쓴다. 가로 화면 2단: 왼쪽 = 현재 상태·시나리오, 오른쪽 = 휴게소·동의·로그.
 */
@Composable
fun FatigueScreen(s: UiState, vm: FatigueViewModel, onNavigate: (RestAreaCandidate) -> Unit) {
    val a = s.assessment
    val accent = a?.urgency?.color() ?: HmgColors.Sub
    val level = a?.level ?: 0

    AppScaffold(
        title = "피로도 가드",
        subtitle = "속도 편차 · 급가감속 · 주행 시간으로 졸음 위험을 추정하고 휴게소를 안내합니다",
        actions = { Pill(if (s.running) "모니터링 중" else "대기", if (s.running) HmgColors.Good else HmgColors.Sub) },
    ) {
        s.notice?.let { n ->
            Panel(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n, Modifier.weight(1f), fontSize = 16.sp)
                    OutlinedButton(onClick = vm::dismissNotice) { Text("닫기") }
                }
            }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // ---------- 왼쪽: 현재 상태 · 시나리오 · 연동 상태
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Panel(Modifier.fillMaxWidth().border(3.dp, accent, RoundedCornerShape(16.dp))) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${a?.score ?: 0}", fontSize = 72.sp, fontWeight = FontWeight.ExtraBold, color = accent)
                        Text("Level $level · ${Messages.levelName[level]}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent)
                        Text(
                            Messages.byLevel[level], fontSize = 17.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (a != null) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        Metric("주행", "${a.drivingMinutes}", "분")
                        Metric("거리", "%.1f".format(a.distanceKm), "km")
                        Metric("속도 편차", "%.1f".format(a.speedStdDev))
                        Metric("급가감속", "${a.accelEvents}", "회")
                    }
                }
                Panel(Modifier.fillMaxWidth(), title = "데모 시나리오") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DriveProfile.entries.forEach { p ->
                            Button(onClick = { vm.start(p) }, Modifier.weight(1f).heightIn(min = 64.dp)) { Text(p.label) }
                        }
                    }
                    if (s.running) {
                        OutlinedButton(onClick = vm::stop, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("중지") }
                    }
                    if (BuildConfig.DEBUG) {
                        OutlinedButton(onClick = vm::probeSdk, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("SDK API 탐색 (디버그)")
                        }
                    }
                }
                Panel(Modifier.fillMaxWidth(), title = "연동 상태") { CapsRow(s.caps, s.sourceName) }
            }

            // ---------- 오른쪽: 휴게소 · 동의 · 로그
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Panel(Modifier.fillMaxWidth(), title = "추천 휴게소 TOP 3") {
                    if (s.rests.isEmpty()) {
                        Text("피로도 Level 2 이상이 되면 가까운 휴게소를 추천합니다.", color = HmgColors.Sub, fontSize = 14.sp)
                    }
                    s.rests.forEach { r ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text("${r.distanceKm} km", color = HmgColors.Sub, fontSize = 13.sp)
                            }
                            Button(onClick = { onNavigate(r) }, Modifier.heightIn(min = 56.dp)) { Text("안내 시작") }
                        }
                    }
                }
                ConsentPanel(s.consent, vm::setConsent)
                Panel(
                    Modifier.fillMaxWidth(), title = "세션 로그 (${s.logs.size})",
                    trailing = { OutlinedButton(onClick = vm::clearLogs) { Text("삭제") } },
                ) {
                    if (s.logs.isEmpty()) Text("저장된 로그가 없습니다.", color = HmgColors.Sub, fontSize = 13.sp)
                    s.logs.take(20).forEach { l ->
                        Text(
                            "${l.timestamp.substring(11, 19)} · ${l.drivingDurationMin}분 · Level ${l.fatigueLevel} · ${l.nextRestAreaCandidates.firstOrNull() ?: "-"}",
                            fontSize = 13.sp,
                        )
                    }
                }
                Text(
                    "데이터는 이 기기 안에서만 처리되며 외부로 전송되지 않습니다.",
                    fontSize = 12.sp, color = HmgColors.Sub, modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun CapsRow(c: Capabilities, source: String) {
    fun m(ok: Boolean) = if (ok) "✓" else "✕"
    Text("소스: $source", fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Text(
        "차량속도 ${m(c.vehicleSdk)}  TTS ${m(c.tts)}  위치(Android) ${m(c.androidLocation)}  " +
            "FusedLocation ${m(c.fusedLocation)}  NaviHelper ${m(c.naviHelper)}",
        fontSize = 13.sp, color = HmgColors.Sub,
    )
}

@Composable
private fun ConsentPanel(c: Consent, onChange: (Consent) -> Unit) {
    Panel(Modifier.fillMaxWidth(), title = "개인정보 동의") {
        ConsentRow("텔레메트리 처리 (필수)", c.telemetry) { onChange(c.copy(telemetry = it)) }
        ConsentRow("위치 사용 (휴게소 안내)", c.location) { onChange(c.copy(location = it)) }
        ConsentRow("세션 로그 로컬 저장", c.storingLogs) { onChange(c.copy(storingLogs = it)) }
        ConsentRow("음성 알림(TTS)", c.tts) { onChange(c.copy(tts = it)) }
    }
}

@Composable
private fun ConsentRow(label: String, v: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked = v, onCheckedChange = onChange)
    }
}
