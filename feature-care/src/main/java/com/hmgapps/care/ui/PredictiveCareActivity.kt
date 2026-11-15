package com.hmgapps.care.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hmgapps.care.domain.CareSimulator
import com.hmgapps.care.domain.DrivePattern
import com.hmgapps.care.domain.Forecast
import com.hmgapps.care.domain.Status
import com.hmgapps.core.energy.VehicleEnergy
import com.hmgapps.core.ui.AppLinks
import com.hmgapps.core.ui.AppScaffold
import com.hmgapps.core.ui.Dest
import com.hmgapps.core.ui.HmgShell
import com.hmgapps.core.ui.HmgColors
import com.hmgapps.core.ui.HmgTheme
import com.hmgapps.core.ui.Metric
import com.hmgapps.core.ui.Panel
import com.hmgapps.core.ui.Pill
import com.hmgapps.core.pleos.label
import androidx.compose.runtime.collectAsState

/** 추가 앱 ② Predictive Care — EV 디지털 트윈: BMS 시각화 + 예측형 정비. */
class PredictiveCareActivity : ComponentActivity() {
    private val vm: CareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HmgTheme {
                val s by vm.state.collectAsStateWithLifecycle()
                HmgShell(current = Dest.CARE, onSelect = { d -> if (d != Dest.CARE) AppLinks.open(this@PredictiveCareActivity, d) }) {
                    CareScreen(s, vm, onBack = if (isTaskRoot) null else ({ finish() }))
                }
            }
        }
    }

    override fun onStart() { super.onStart(); VehicleEnergy.acquire(this) }
    override fun onStop() { VehicleEnergy.release(); super.onStop() }
}

private fun Status.color() = when (this) { Status.GOOD -> HmgColors.Good; Status.WATCH -> HmgColors.Warn; Status.ALERT -> HmgColors.Bad }

private fun probColor(p: Double) = when { p >= 0.6 -> HmgColors.Bad; p >= 0.3 -> HmgColors.Warn; else -> HmgColors.Good }

@Composable
private fun CareScreen(s: CareUi, vm: CareViewModel, onBack: (() -> Unit)?) {
    AppScaffold(
        title = "Predictive Care",
        subtitle = "EV 디지털 트윈 — 고장 나기 전에 알려 주는 차량 생애주기 관리",
        onBack = onBack,
        actions = {
            Pill(if (s.vehicle.live) "Pleos 실측 + BMS 데모" else "데모 데이터", if (s.vehicle.live) HmgColors.Good else HmgColors.Warn)
            FilterChip(selected = s.tab == CareTab.BMS, onClick = { vm.selectTab(CareTab.BMS) },
                label = { Text("BMS 실시간", fontSize = 16.sp, modifier = Modifier.padding(vertical = 10.dp)) })
            FilterChip(selected = s.tab == CareTab.PREDICT, onClick = { vm.selectTab(CareTab.PREDICT) },
                label = { Text("예측 정비 리포트", fontSize = 16.sp, modifier = Modifier.padding(vertical = 10.dp)) })
        },
    ) {
        // 최상위 위험 한 줄은 항상 상단에 노출 (인포테인먼트 알림 배너)
        s.forecasts.firstOrNull()?.takeIf { it.probability >= 0.3 }?.let { top ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp))
                    .background(probColor(top.probability).copy(alpha = 0.16f)).clickable { vm.select(top.component); vm.selectTab(CareTab.PREDICT) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚠ ${top.sentence}", color = probColor(top.probability), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("자세히 ›", color = HmgColors.Sub)
            }
        }
        when (s.tab) {
            CareTab.BMS -> BmsTab(s, vm)
            CareTab.PREDICT -> PredictTab(s, vm)
        }
    }
}

@Composable
private fun BmsTab(s: CareUi, vm: CareViewModel) {
    val f = s.frame
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight(), title = "차량 건강 점수") {
            ScoreGauge(s.health.score, s.health.grade, s.health.status.color(), Modifier.fillMaxWidth().aspectRatio(1.5f))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.health.items) { h ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(h.name, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(h.value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = h.status.color())
                        }
                        LinearProgressIndicator(
                            progress = { (1 - h.penalty / h.maxPenalty).toFloat() },
                            modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 3.dp),
                            color = h.status.color(), trackColor = HmgColors.PanelHi,
                        )
                    }
                }
            }
        }
        Column(Modifier.weight(1.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth().weight(1f), title = "고전압 팩 셀 맵 (${CareSimulator.MODULES}모듈 × ${CareSimulator.CELLS_PER_MODULE}셀)", trailing = {
                FilterChip(selected = s.heat == HeatMode.VOLTAGE, onClick = { vm.setHeat(HeatMode.VOLTAGE) }, label = { Text("전압") })
                FilterChip(selected = s.heat == HeatMode.TEMP, onClick = { vm.setHeat(HeatMode.TEMP) }, label = { Text("온도") }, modifier = Modifier.padding(start = 8.dp))
            }) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Metric("팩 전압", "%.0f".format(f.packVoltage), "V")
                    Metric("셀 편차", "%.0f".format(f.cellSpreadMv), "mV")
                    Metric("온도 편차", "%.1f".format(f.tempSpreadC), "℃")
                    Metric("최고 온도", "%.1f".format(f.maxTempC), "℃")
                    Metric("SOH", "%.1f".format(f.sohPercent), "%")
                }
                CellHeatmap(f.cells, CareSimulator.MODULES, CareSimulator.CELLS_PER_MODULE, s.heat == HeatMode.TEMP, Modifier.fillMaxSize())
            }
            Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Panel(Modifier.weight(1f).fillMaxHeight(), title = "12V 보조 배터리 ${"%.2f".format(f.aux12vVolt)} V") {
                    LiveLine(s.aux12v, 12.4, 14.8, HmgColors.Accent, Modifier.fillMaxSize())
                }
                Panel(Modifier.weight(1f).fillMaxHeight(), title = "LDC 전환 리플 ${"%.0f".format(f.ldcRippleMv)} mV · 이상 ${f.switchFaults}회") {
                    LiveLine(s.ripple, 60.0, 150.0, HmgColors.Energy, Modifier.fillMaxSize())
                }
                VehicleSignalsPanel(s, Modifier.weight(1.1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun SignalRow(k: String, value: String, c: Color = HmgColors.Text) {
    Row {
        Text(k, color = HmgColors.Sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = c, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Pleos Vehicle SDK 로 받은 실측 신호. 데모 환경이면 안내만 보인다. */
@Composable
private fun VehicleSignalsPanel(s: CareUi, modifier: Modifier) {
    val v = s.vehicle
    val energy by VehicleEnergy.state.collectAsState()
    Panel(modifier, title = "Pleos 차량 신호") {
        if (!v.live) {
            Text("Pleos Connect 에서 실행하면 차량 실측값이 표시됩니다.", color = HmgColors.Sub, fontSize = 13.sp)
            v.lastError?.let { Text(it, color = HmgColors.Warn, fontSize = 11.sp) }
        } else {
            SignalRow("배터리 SoC", v.socPercent?.let { "$it %" } ?: "-")
            SignalRow("실측 전비", v.efficiencyKmPerKwh?.let { "%.1f km/kWh".format(it) } ?: "-")
            SignalRow("주행가능거리", (v.rangeKm?.toDouble() ?: energy.vehicleRangeKm)?.let { "%.0f km".format(it) } ?: "-")
            SignalRow("기어 · 트립", "${v.gear?.label() ?: "-"} · ${v.tripDrivingMinutes?.let { "${it}분" } ?: "-"}")
            SignalRow("브레이크액", v.brakeFluid?.label() ?: "-",
                when (v.brakeFluid?.label()) { "정상", "높음" -> HmgColors.Good; null -> HmgColors.Text; else -> HmgColors.Bad })
        }
    }
}

@Composable
private fun PredictTab(s: CareUi, vm: CareViewModel) {
    val sel = s.forecasts.firstOrNull { it.component == s.selected } ?: s.forecasts.first()
    val series = s.history.first { it.component == sel.component }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel(Modifier.fillMaxWidth(), title = "시나리오") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(90 to "3개월", 180 to "6개월", 365 to "1년").forEach { (d, l) ->
                        FilterChip(selected = s.horizonDays == d, onClick = { vm.setHorizon(d) }, label = { Text(l, fontSize = 15.sp) })
                    }
                }
                DrivePattern.entries.forEach { p ->
                    FilterChip(selected = s.pattern == p, onClick = { vm.setPattern(p) },
                        label = { Text(p.label, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp)) }, modifier = Modifier.fillMaxWidth())
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(s.forecasts) { fc -> ForecastRow(fc, fc.component == sel.component) { vm.select(fc.component) } }
            }
        }
        Panel(Modifier.weight(1.7f).fillMaxHeight(), title = "${sel.component.label} · ${sel.component.signal} (${sel.component.unit})") {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Metric("다운타임 확률", "${(sel.probability * 100).toInt()}", "%", probColor(sel.probability))
                Metric("현재값", "%.2f".format(sel.current), sel.component.unit)
                Metric("임계 도달 예상", sel.daysToThreshold?.let { "D+$it" } ?: "추세 없음", "")
            }
            Text(sel.sentence, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = probColor(sel.probability))
            Text("조치: ${sel.component.downtime}", color = HmgColors.Sub, fontSize = 15.sp)
            TrendChart(series, sel, Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp))
            Text(
                "모델: 최근 ${series.daily.size}일 일별 대표값에 선형 추세를 맞추고, 주행 패턴 배율(${sel.pattern.factor})로 외삽한 뒤 " +
                    "추세·잔차 불확실성을 정규분포로 반영해 임계 초과 확률을 계산합니다. 데모 데이터 기준 수치입니다.",
                color = HmgColors.Sub, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ForecastRow(fc: Forecast, selected: Boolean, onClick: () -> Unit) {
    val c = probColor(fc.probability)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) HmgColors.PanelHi else HmgColors.Panel)
            .border(if (selected) 2.dp else 0.dp, if (selected) c else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(fc.component.label, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(fc.daysToThreshold?.let { "추세대로면 D+$it 임계 도달" } ?: "악화 추세 없음", color = HmgColors.Sub, fontSize = 13.sp)
        }
        Text("${(fc.probability * 100).toInt()}%", color = c, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    }
}
