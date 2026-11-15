package com.hmgapps.energy.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hmgapps.core.energy.EnergyMath
import com.hmgapps.core.energy.VehicleEnergy
import com.hmgapps.core.ui.AppLinks
import com.hmgapps.core.ui.AppScaffold
import com.hmgapps.core.ui.Dest
import com.hmgapps.core.ui.HmgShell
import com.hmgapps.core.ui.CarPermissions
import com.hmgapps.core.ui.HmgColors
import com.hmgapps.core.ui.HmgTheme
import com.hmgapps.core.ui.Metric
import com.hmgapps.core.ui.Panel
import com.hmgapps.core.ui.Pill
import com.hmgapps.core.ui.SourcePill
import com.hmgapps.core.pleos.label
import com.hmgapps.energy.domain.GuardState
import com.hmgapps.energy.domain.ShedReason
import com.hmgapps.energy.domain.TouSchedule
import com.hmgapps.energy.domain.TouTier
import com.hmgapps.energy.domain.TradeAction
import com.hmgapps.energy.domain.V2lPlanner
import java.text.NumberFormat
import java.util.Locale

/** 추가 앱 ① Energy Trading Hub — V2G/V2L 기반 스마트 에너지 프로슈머. */
class EnergyHubActivity : ComponentActivity() {
    private val vm: EnergyViewModel by viewModels()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        VehicleEnergy.retry(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarPermissions.missingEnergy(this).takeIf { it.isNotEmpty() }?.let { permission.launch(it) }
        setContent {
            HmgTheme {
                val s by vm.state.collectAsStateWithLifecycle()
                HmgShell(current = Dest.ENERGY, onSelect = { d -> if (d != Dest.ENERGY) AppLinks.open(this@EnergyHubActivity, d) }) {
                    EnergyScreen(s, vm, onBack = if (isTaskRoot) null else ({ finish() }))
                }
            }
        }
    }

    override fun onStart() { super.onStart(); VehicleEnergy.acquire(this) }
    override fun onStop() { VehicleEnergy.release(); super.onStop() }
}

private val won = NumberFormat.getIntegerInstance(Locale.KOREA)

private fun guardColor(g: GuardState) = when (g) {
    GuardState.OK -> HmgColors.Good; GuardState.LOW -> HmgColors.Warn; GuardState.LOCKED -> HmgColors.Bad
}

@Composable
private fun EnergyScreen(s: EnergyUi, vm: EnergyViewModel, onBack: (() -> Unit)?) {
    AppScaffold(
        title = "Energy Trading Hub",
        subtitle = "돌아다니는 ESS — 내 차 배터리로 아끼고, 팔고, 지킨다",
        onBack = onBack,
        actions = {
            SourcePill(s.energy.live)
            FilterChip(selected = s.tab == EnergyTab.V2L, onClick = { vm.selectTab(EnergyTab.V2L) },
                label = { Text("스마트 방전 (V2L)", fontSize = 16.sp, modifier = Modifier.padding(vertical = 10.dp)) })
            FilterChip(selected = s.tab == EnergyTab.V2G, onClick = { vm.selectTab(EnergyTab.V2G) },
                label = { Text("전력 재판매 (V2G)", fontSize = 16.sp, modifier = Modifier.padding(vertical = 10.dp)) })
        },
    ) {
        if (!s.parked) Panel(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text("기어 ${s.vehicle.gear?.label()} — 주행 중에는 V2L/V2G 를 쓸 수 없습니다. P단에서 다시 시도하세요.", color = HmgColors.Bad, fontSize = 16.sp)
        }
        s.notice?.let { n ->
            Panel(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n, color = HmgColors.Warn, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = vm::dismissNotice) { Text("닫기") }
                }
            }
        }
        when (s.tab) {
            EnergyTab.V2L -> V2lTab(s, vm)
            EnergyTab.V2G -> V2gTab(s, vm)
        }
    }
}

// ------------------------------------------------------------------ V2L

@Composable
private fun V2lTab(s: EnergyUi, vm: EnergyViewModel) {
    val p = s.plan
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth(), title = "배터리 & 방전 락", trailing = {
                Pill(when (p.state) { GuardState.OK -> "정상 분배"; GuardState.LOW -> "절전 분배"; GuardState.LOCKED -> "락 · 출력 차단" }, guardColor(p.state))
            }) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Metric("현재 잔량", "%.1f".format(s.energy.socPercent), "%", guardColor(p.state))
                    Metric("락 위 사용 가능", "%.1f".format(p.usableKwh), "kWh")
                    Metric("현재 부하로", p.runtimeMinutes?.let { "${it / 60}시간 ${it % 60}분" } ?: "-", "")
                }
                BatteryBar(s.energy.socPercent, s.lockSoc, guardColor(p.state), Modifier.fillMaxWidth().height(46.dp).padding(vertical = 6.dp))
                val commuteSoc = EnergyMath.socForDistance(s.settings.commuteKm, s.efficiencyInUse, s.energy.capacityKwh)
                Text(
                    "적용 락 ${s.lockSoc}% = max(직접 설정 ${s.settings.lockSocPercent}%, 내일 출근 ${s.settings.commuteKm.toInt()}km 필요 ${commuteSoc}% + 여유 ${s.settings.reservePercent}%)",
                    color = HmgColors.Sub, fontSize = 14.sp,
                )
            }
            Panel(Modifier.fillMaxWidth(), title = "락 설정") {
                Text("최소 보호 잔량: ${s.settings.lockSocPercent}%", fontSize = 16.sp)
                Slider(value = s.settings.lockSocPercent.toFloat(), onValueChange = { vm.updateSettings(s.settings.copy(lockSocPercent = it.toInt())) },
                    valueRange = 10f..80f, steps = 13)
                Text("내일 출근 거리: ${s.settings.commuteKm.toInt()} km", fontSize = 16.sp)
                Slider(value = s.settings.commuteKm.toFloat(), onValueChange = { vm.updateSettings(s.settings.copy(commuteKm = it.toDouble())) },
                    valueRange = 0f..200f, steps = 39)
                if (s.energy.measuredEfficiency != null) {
                    Text("전비: ${"%.1f".format(s.efficiencyInUse)} km/kWh — Pleos 실측값 적용 중", fontSize = 16.sp, color = HmgColors.Good)
                } else {
                    Text("전비: ${"%.1f".format(s.settings.efficiencyKmPerKwh)} km/kWh (설정)", fontSize = 16.sp)
                    Slider(value = s.settings.efficiencyKmPerKwh.toFloat(), onValueChange = { vm.updateSettings(s.settings.copy(efficiencyKmPerKwh = it.toDouble())) },
                        valueRange = 3f..8f, steps = 9)
                }
            }
        }
        Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth(), title = "V2L 출력 ${p.totalWatts} / ${V2lPlanner.DEFAULT_MAX_WATTS} W") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = vm::toggleV2l, modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                        colors = if (s.v2lRunning) ButtonDefaults.buttonColors(containerColor = HmgColors.Bad) else ButtonDefaults.buttonColors()) {
                        Text(if (s.v2lRunning) "V2L 중지 (경과 ${s.v2lElapsedMin}분)" else "V2L 시작 (1초=1분 재생)", fontSize = 17.sp)
                    }
                    if (!s.energy.live) {
                        OutlinedButton(onClick = { vm.setDemoSoc(s.lockSoc + 7.0) }, modifier = Modifier.heightIn(min = 60.dp)) { Text("시연: 락 근처로") }
                        OutlinedButton(onClick = { vm.setDemoSoc(82.0) }, modifier = Modifier.heightIn(min = 60.dp)) { Text("82%로") }
                    }
                }
            }
            Panel(Modifier.fillMaxWidth().weight(1f), title = "연결 기기 (우선순위 순으로 분배)") {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(s.loads.sortedBy { it.priority }) { l ->
                        val shed = p.shed[l.id]
                        val on = p.active.any { it.id == l.id }
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text("${l.watts} W · 우선순위 ${l.priority}${if (l.priority == 1) " (필수)" else ""}", color = HmgColors.Sub, fontSize = 13.sp)
                            }
                            when {
                                !l.requested -> Pill("꺼짐", HmgColors.Sub)
                                on -> Pill("공급 중", HmgColors.Good)
                                shed == ShedReason.OVER_CAPACITY -> Pill("용량 초과 대기", HmgColors.Warn)
                                shed == ShedReason.LOW_ENERGY -> Pill("절전 차단", HmgColors.Warn)
                                shed == ShedReason.LOCKED -> Pill("락 차단", HmgColors.Bad)
                            }
                            Spacer(Modifier.padding(6.dp))
                            Switch(checked = l.requested, onCheckedChange = { vm.toggleLoad(l.id) })
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ V2G

@Composable
private fun V2gTab(s: EnergyUi, vm: EnergyViewModel) {
    val g = s.v2g
    val last = g.candles.lastOrNull()
    val lastTrade = g.trades.lastOrNull()
    val rsi = g.rsi.lastOrNull()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(2.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
                    val net = g.ledger.netWon
                    Metric("오늘 순수익", (if (net >= 0) "+" else "") + won.format(net.toLong()), "원", if (net >= 0) HmgColors.Up else HmgColors.Down)
                    Metric("현재가", last?.let { "%.1f".format(it.close) } ?: "-", "원/kWh",
                        if (last != null && last.close >= last.open) HmgColors.Up else HmgColors.Down)
                    Metric("RSI(14)", rsi?.let { "%.0f".format(it) } ?: "-", "",
                        when { rsi == null -> HmgColors.Text; rsi >= 70 -> HmgColors.Up; rsi <= 30 -> HmgColors.Down; else -> HmgColors.Text })
                    Metric("판매 / 충전", "%.1f / %.1f".format(g.ledger.soldKwh, g.ledger.boughtKwh), "kWh")
                    Metric("시각", "%02d:%02d".format(g.clockMin / 60, g.clockMin % 60), "")
                }
            }
            Panel(Modifier.fillMaxWidth().weight(1f), title = "전력 시세 · 거래량 · RSI (15분 봉)", trailing = {
                last?.let { Pill(it.tier.label, when (it.tier) { TouTier.PEAK -> HmgColors.Up; TouTier.MID -> HmgColors.Sub; TouTier.OFF_PEAK -> HmgColors.Down }) }
            }) {
                TradingChart(g.candles, g.trades, g.rsi, Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth(), title = "자동 거래") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.energy.live) "충전구 열림 (Pleos 실측)" else "충전구(양방향 충전기) 연결", Modifier.weight(1f), fontSize = 15.sp)
                    Switch(checked = s.energy.chargePortConnected, onCheckedChange = vm::setPlugged, enabled = !s.energy.live)
                }
                Text("잔량 ${"%.1f".format(s.energy.socPercent)}% · 락 ${s.lockSoc}% (락 아래는 절대 판매하지 않음)", color = HmgColors.Sub, fontSize = 14.sp)
                Button(onClick = vm::toggleV2g, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    colors = if (g.running) ButtonDefaults.buttonColors(containerColor = HmgColors.Bad) else ButtonDefaults.buttonColors()) {
                    Text(if (g.running) "거래 중지" else "거래 시작 (모의 시장)", fontSize = 17.sp)
                }
                OutlinedButton(onClick = vm::resetV2g, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("새 하루로 초기화") }
                lastTrade?.let {
                    Pill("${it.action.label} · ${it.reason}", when (it.action) { TradeAction.SELL -> HmgColors.Up; TradeAction.BUY -> HmgColors.Down; TradeAction.HOLD -> HmgColors.Sub })
                }
            }
            Panel(Modifier.fillMaxWidth().weight(1f), title = "체결 내역") {
                val rows = g.candles.zip(g.trades).filter { it.second.action != TradeAction.HOLD }.takeLast(30).reversed()
                if (rows.isEmpty()) Text("아직 체결이 없습니다.", color = HmgColors.Sub)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(rows) { (c, t) ->
                        val col = if (t.action == TradeAction.SELL) HmgColors.Up else HmgColors.Down
                        Row(Modifier.fillMaxWidth()) {
                            Text("%02d:%02d".format(c.minuteOfDay / 60, c.minuteOfDay % 60), color = HmgColors.Sub, modifier = Modifier.weight(0.8f))
                            Text(t.action.label, color = col, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.1f))
                            Text("%.2fkWh".format(t.kWh), modifier = Modifier.weight(1f))
                            Text((if (t.pnlWon >= 0) "+" else "") + won.format(t.pnlWon.toLong()) + "원", color = col, modifier = Modifier.weight(1.1f))
                        }
                    }
                }
            }
            val sch = TouSchedule()
            Text("요금(예시): 경부하 ${sch.offPeakWon.toInt()} · 중간 ${sch.midWon.toInt()} · 최대 ${sch.peakWon.toInt()} 원/kWh, 마모비 35원/kWh 차감. " +
                "실제 한전 요금·V2G 정산 단가가 아닌 시연용 가정치입니다.", color = HmgColors.Sub, fontSize = 12.sp)
        }
    }
}
