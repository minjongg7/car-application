package com.hmgapps.core.energy

import android.content.Context
import com.hmgapps.core.pleos.PleosVehicleHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * 프로세스 공용 배터리 상태. 세 앱이 같은 APK(같은 프로세스)라서 값이 자동으로 공유된다.
 *
 * - Pleos Connect(Automotive) → **Pleos Vehicle SDK 실측** (SoC·용량·충전구·전비·주행가능거리, live=true).
 *   시뮬레이션 방전은 실측값을 덮어쓰지 않는다.
 * - 그 외(휴대폰·일반 에뮬레이터) 또는 값이 아직 안 들어온 동안 → 데모 배터리 (77.4kWh, 82%).
 *
 * acquire()/release() 는 참조 카운트 (Activity onStart/onStop 에서 호출).
 */
object VehicleEnergy {
    const val DEMO_CAPACITY_KWH = 77.4

    private val _state = MutableStateFlow(EnergySnapshot(socPercent = 82.0, capacityKwh = DEMO_CAPACITY_KWH))
    val state: StateFlow<EnergySnapshot> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var refs = 0

    @Synchronized
    fun acquire(context: Context) {
        refs++
        PleosVehicleHub.acquire(context)
        if (job != null) return
        job = scope.launch {
            PleosVehicleHub.state.collect { p ->
                if (p.socPercent == null) return@collect // SoC 가 들어와야 실측으로 전환
                _state.update { s ->
                    val cap = p.capacityWh?.let { it / 1000.0 } ?: s.capacityKwh
                    s.copy(
                        socPercent = p.socPercent.toDouble().coerceIn(0.0, 100.0),
                        capacityKwh = cap,
                        chargePortConnected = p.chargePortOpen ?: s.chargePortConnected,
                        measuredEfficiency = p.efficiencyKmPerKwh?.toDouble(),
                        vehicleRangeKm = p.rangeKm?.toDouble(),
                        live = true,
                    )
                }
            }
        }
    }

    @Synchronized
    fun release() {
        refs = max(0, refs - 1)
        PleosVehicleHub.release()
        if (refs == 0) { job?.cancel(); job = null }
    }

    /** 이전 버전 호환 (Pleos 권한은 normal 이라 재시도가 필요 없다). */
    fun retry(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    /** 데모 모드에서만 잔량을 바꾼다. kWh: +충전 / -방전. 실측 모드면 false. */
    fun applyDemoEnergy(deltaKwh: Double, powerKw: Double = 0.0): Boolean {
        if (_state.value.live) return false
        _state.update { s ->
            val soc = min(100.0, max(0.0, s.socPercent + deltaKwh / s.capacityKwh * 100.0))
            s.copy(socPercent = soc, powerKw = powerKw)
        }
        return true
    }

    /** 데모: 충전구 연결 상태 토글 (V2G 는 연결돼야 동작). */
    fun setDemoPlugged(plugged: Boolean) {
        if (_state.value.live) return
        _state.update { it.copy(chargePortConnected = plugged) }
    }

    /** 데모 잔량 직접 설정 (발표 시연용). */
    fun setDemoSoc(soc: Double) {
        if (_state.value.live) return
        _state.update { it.copy(socPercent = min(100.0, max(0.0, soc))) }
    }
}
