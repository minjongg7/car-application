package com.hmgapps.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 차량 인포테인먼트용 공용 팔레트 (어두운 배경, 큰 글자, 신호색). */
object HmgColors {
    val Bg = Color(0xFF0E1116)
    val Panel = Color(0xFF171B22)
    val PanelHi = Color(0xFF202632)
    val Line = Color(0xFF2C3340)
    val Text = Color(0xFFE8ECF2)
    val Sub = Color(0xFF9AA4B2)
    val Accent = Color(0xFF3FA9F5)   // 정보/선택
    val Up = Color(0xFFE5484D)       // 상승(국내 금융앱 관례: 빨강)
    val Down = Color(0xFF3D7BF7)     // 하락(파랑)
    val Good = Color(0xFF2FBF71)
    val Warn = Color(0xFFF2A93B)
    val Bad = Color(0xFFE5484D)
    val Energy = Color(0xFF00C2A8)
}

@Composable
fun HmgTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HmgColors.Accent, secondary = HmgColors.Energy,
            background = HmgColors.Bg, surface = HmgColors.Panel, surfaceVariant = HmgColors.PanelHi,
            onBackground = HmgColors.Text, onSurface = HmgColors.Text, onSurfaceVariant = HmgColors.Sub,
            outline = HmgColors.Line, error = HmgColors.Bad,
        ),
    ) { Surface(color = HmgColors.Bg, modifier = Modifier.fillMaxSize(), content = content) }
}

/** 앱 공통 상단 바 + 본문. onBack 이 null 이면 뒤로 버튼 없음. */
@Composable
fun AppScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 52.dp)) { Text("‹ 뒤로", fontSize = 18.sp) }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = HmgColors.Text)
                if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = HmgColors.Sub)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Spacer(Modifier.heightIn(min = 10.dp))
        content()
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, title: String? = null, trailing: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(HmgColors.Panel).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = HmgColors.Text, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

/** 큰 숫자 지표 하나. */
@Composable
fun Metric(label: String, value: String, unit: String = "", color: Color = HmgColors.Text, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 13.sp, color = HmgColors.Sub)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = color)
            if (unit.isNotEmpty()) Text(" $unit", fontSize = 15.sp, color = HmgColors.Sub, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

/** 작은 상태 라벨. */
@Composable
fun Pill(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** 데모/실측 표시 라벨. 심사·시연 때 어떤 값이 실측인지 분명히 보여 준다. */
@Composable
fun SourcePill(live: Boolean) = Pill(if (live) "Pleos 차량 실측" else "데모 데이터", if (live) HmgColors.Good else HmgColors.Warn)
