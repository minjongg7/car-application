package com.hmgapps.core.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 내비·Energy Hub·Predictive Care·Family Care 가 공유하는 이동 대상. */
enum class Dest(val icon: String, val label: String, val className: String, val tab: String?) {
    NAV("🧭", "내비", "com.fatigueguard.ui.MainActivity", "NAV"),
    FATIGUE("🛡", "피로도", "com.fatigueguard.ui.MainActivity", "FATIGUE"),
    ENERGY("⚡", "Energy\nHub", "com.hmgapps.energy.ui.EnergyHubActivity", null),
    CARE("🩺", "Predictive\nCare", "com.hmgapps.care.ui.PredictiveCareActivity", null),
    FAMILY("👨‍👩‍👧", "Family\nCare", "com.hmgapps.family.ui.FamilyCareActivity", null),
}

/** 다른 앱 화면 열기. 같은 APK 안이라 클래스 이름 문자열로 연결한다(모듈 간 의존 없음). */
object AppLinks {
    const val EXTRA_TAB = "hmg.tab"

    fun open(context: Context, dest: Dest) {
        val intent = Intent().setClassName(context.packageName, dest.className)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        dest.tab?.let { intent.putExtra(EXTRA_TAB, it) }
        context.startActivity(intent)
    }
}

/**
 * 공용 셸: 왼쪽 레일(내비 · 피로도 | 추가 앱) + 본문.
 * 세 앱 화면이 모두 같은 레일을 써서 어디서든 같은 방식으로 이동한다.
 */
@Composable
fun HmgShell(current: Dest, onSelect: (Dest) -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        NavigationRail(
            containerColor = HmgColors.Panel,
            modifier = Modifier.fillMaxHeight().padding(WindowInsets.systemBars.asPaddingValues()),
        ) {
            val colors = NavigationRailItemDefaults.colors(indicatorColor = HmgColors.PanelHi)
            Spacer(Modifier.height(8.dp))
            RailEntry(Dest.NAV, current, onSelect, colors)
            RailEntry(Dest.FATIGUE, current, onSelect, colors)
            HorizontalDivider(Modifier.padding(vertical = 12.dp).width(48.dp), color = HmgColors.Line)
            Text("추가 앱", fontSize = 11.sp, color = HmgColors.Sub)
            RailEntry(Dest.ENERGY, current, onSelect, colors)
            RailEntry(Dest.CARE, current, onSelect, colors)
            RailEntry(Dest.FAMILY, current, onSelect, colors)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

@Composable
private fun RailEntry(
    dest: Dest, current: Dest, onSelect: (Dest) -> Unit,
    colors: androidx.compose.material3.NavigationRailItemColors,
) {
    NavigationRailItem(
        selected = dest == current,
        onClick = { onSelect(dest) },
        colors = colors,
        icon = { Text(dest.icon, fontSize = 26.sp) },
        label = { Text(dest.label, textAlign = TextAlign.Center) },
    )
}
