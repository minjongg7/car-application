package com.fatigueguard.data

import android.content.Context
import android.location.Location
import com.fatigueguard.domain.Capabilities
import com.fatigueguard.domain.TelemetryEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** 텔레메트리 공급자 추상화. Pleos SDK/모의 데이터를 같은 인터페이스로 교체 가능. */
interface TelemetrySource {
    val name: String
    fun events(): Flow<TelemetryEvent>
    fun close() {}
}

/** 데모 시나리오 (문서 7장: 90/120/150분). */
enum class DriveProfile(val label: String, val warmupMin: Int, val sigma: Float, val spikeProb: Float) {
    GREEN("90분 🟢", 90, 2f, 0.003f),
    YELLOW("120분 🟡", 120, 2.5f, 0.005f),
    RED("150분 🔴", 150, 6f, 0.017f),
}

/**
 * 에뮬레이터/SDK 없이 동작하는 모의 소스.
 * 시뮬레이션 시간 2초 간격으로 warmupMin 분을 즉시 재생한 뒤, 1초마다 실시간 샘플을 낸다.
 */
class MockTelemetrySource(
    private val profile: DriveProfile,
    private val random: Random = Random.Default,
) : TelemetrySource {
    override val name = "Mock(${profile.label})"

    override fun events(): Flow<TelemetryEvent> = flow {
        var t = 0L
        var lat = 37.40
        val lon = 127.10
        suspend fun sample(warmup: Boolean) {
            var v = 100f + gaussian() * profile.sigma
            if (random.nextFloat() < profile.spikeProb) v += if (random.nextBoolean()) 12f else -12f
            v = max(0f, v)
            lat -= v / 3600.0 * 2 / 111.0 // 남쪽으로 이동 (2초 * 속도)
            emit(TelemetryEvent(t, v, lat, lon, warmup))
            t += 2_000L
        }
        repeat(profile.warmupMin * 30) { sample(warmup = true) }
        while (true) {
            sample(warmup = false)
            delay(1_000L)
        }
    }

    private fun gaussian(): Float { // Box-Muller
        val u1 = random.nextDouble().coerceAtLeast(1e-9)
        val u2 = random.nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2 * Math.PI * u2)).toFloat()
    }
}

/**
 * 차량 속도(AAOS CarPropertyManager) + 표준 Android 위치를 결합한 실제 텔레메트리 소스.
 *
 * 구현된 것: 수명 관리(구독 해제 후 release 순서), 속도 이벤트 스로틀(500ms),
 * 변화가 없을 때 2초 하트비트 재전송(엔진은 샘플 간격이 10초를 넘으면 거리/급가감속 계산에서 제외하므로 필요),
 * 최신 위치 결합, 오류 시 Flow 종료.
 * 속도 구독 자체는 [PleosSpeedBinding] 에 격리. Automotive 기기가 아니면 Factory 가 Mock 을 선택한다.
 */
class PleosTelemetrySource(context: Context) : TelemetrySource {
    override val name = "Pleos Vehicle SDK"

    private val appContext = context.applicationContext
    private val locations = AndroidLocationSource(appContext)

    /** Automotive 기기에서만 true. 휴대폰/일반 에뮬레이터에서는 Mock 으로 동작한다. */
    fun isAvailable(): Boolean = PleosSpeedBinding.isSupported(appContext)

    /** 위치 권한/provider 사용 가능 여부 (UI 의 Capabilities 표시용). */
    val locationAvailable: Boolean get() = locations.isAvailable()

    override fun events(): Flow<TelemetryEvent> = callbackFlow<TelemetryEvent> {
        val latest = AtomicReference<Location?>(null)
        val lastSpeed = AtomicReference<Float?>(null)
        val lastSentMs = AtomicLong(0L)

        fun push(kmh: Float, force: Boolean = false) {
            val now = System.currentTimeMillis()
            lastSpeed.set(kmh)
            if (!force && now - lastSentMs.get() < MIN_INTERVAL_MS) return
            lastSentMs.set(now)
            val loc = latest.get()
            trySend(TelemetryEvent(now, kmh, loc?.latitude, loc?.longitude))
        }

        // 위치: 실패(권한 없음 등)해도 피로도 계산은 계속한다 (위치 없이 속도만 사용).
        val locationJob = launch { locations.updates().catch { }.collect { latest.set(it) } }

        // 하트비트: 속도가 일정해서 콜백이 뜸할 때도 2초마다 마지막 값을 재전송
        val heartbeatJob = launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                val v = lastSpeed.get()
                if (v != null && System.currentTimeMillis() - lastSentMs.get() >= HEARTBEAT_MS) push(v, force = true)
            }
        }

        val binding = PleosSpeedBinding(appContext)
        try {
            // Car.createCar 는 차량 서비스에 동기 연결하므로 메인 스레드를 막지 않게 IO 에서 호출한다.
            try {
                withContext(Dispatchers.IO) { binding.start(onSpeedKmh = { push(it) }, onError = { close(it) }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                close(e)
            }
            awaitClose()
        } finally {
            locationJob.cancel()
            heartbeatJob.cancel()
            binding.stop()    // 리스너 해제를 먼저
            binding.release() // 그 다음 Car 연결 해제
        }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 500L
        const val HEARTBEAT_MS = 2_000L
    }
}

object TelemetrySourceFactory {
    /** Automotive(Pleos 에뮬레이터/실차)이면 실제 소스, 아니면 Mock. */
    fun create(context: Context, profile: DriveProfile): Pair<TelemetrySource, Capabilities> {
        val pleos = PleosTelemetrySource(context)
        return if (pleos.isAvailable()) {
            pleos to Capabilities(vehicleSdk = true, androidLocation = pleos.locationAvailable,
                naviHelper = PleosNavigator.isSupported(context))
        } else {
            MockTelemetrySource(profile) to Capabilities(vehicleSdk = false,
                naviHelper = PleosNavigator.isSupported(context))
        }
    }
}
