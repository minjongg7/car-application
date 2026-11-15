package com.fatigueguard.data

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import kotlin.math.abs

/**
 * 차량 속도 구독 (AAOS CarPropertyManager, PERF_VEHICLE_SPEED).
 *
 * Pleos Vehicle SDK(2.3.2)에는 속도 도메인이 없다 (샘플 앱이 나열한 19개 도메인에 없음).
 * Pleos 공식 "안전 운전 점수 계산기" 튜토리얼과 다른 참가팀 앱이 속도를 이 경로로 읽고,
 * Pleos API 정책도 AAOS 에 같은 기능이 있으면 AAOS 에 맞추는 것이다.
 *
 * - 권한: android.car.permission.CAR_SPEED (런타임 승인 필요 → MainActivity 에서 요청)
 * - 값: m/s (후진 시 음수) → abs 후 km/h 로 변환
 * - 수명: stop()(콜백 해제) → release()(Car.disconnect) 순서
 */
internal class PleosSpeedBinding(private val context: Context) {

    companion object {
        const val IMPLEMENTED = true
        const val PERMISSION_CAR_SPEED = "android.car.permission.CAR_SPEED"
        private const val MS_TO_KMH = 3.6f

        /** Automotive 기기에서만 실제 소스를 쓴다. 휴대폰/일반 에뮬레이터는 Mock 으로 동작. */
        fun isSupported(context: Context): Boolean =
            IMPLEMENTED && context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    }

    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null

    /** 속도 구독 시작. 연결/권한/지원 문제는 예외로 던지고, 호출자가 Flow 를 오류로 종료한다. */
    fun start(onSpeedKmh: (Float) -> Unit, onError: (Exception) -> Unit) {
        val c = Car.createCar(context)
            ?: throw IllegalStateException("Car 서비스에 연결할 수 없습니다 (Automotive 환경이 아닙니다)")
        car = c
        val m = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            ?: throw IllegalStateException("CarPropertyManager 를 얻을 수 없습니다")
        manager = m

        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (value.propertyId != VehiclePropertyIds.PERF_VEHICLE_SPEED) return
                if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
                val ms = (value.value as? Number)?.toFloat() ?: return
                onSpeedKmh(abs(ms) * MS_TO_KMH)
            }

            override fun onErrorEvent(propertyId: Int, zone: Int) {
                onError(IllegalStateException("속도 신호 오류 (property=$propertyId, zone=$zone)"))
            }
        }
        callback = cb

        val ok = try {
            m.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        } catch (e: SecurityException) {
            throw SecurityException("CAR_SPEED 권한이 승인되지 않았습니다", e)
        }
        if (!ok) throw IllegalStateException("PERF_VEHICLE_SPEED 를 구독할 수 없습니다 (이 기기가 속도를 제공하지 않음)")
    }

    /** 등록한 콜백 해제 (release 보다 먼저 호출됨). */
    fun stop() {
        val m = manager
        val cb = callback
        if (m != null && cb != null) runCatching { m.unregisterCallback(cb) }
        callback = null
        manager = null
    }

    /** Car 연결 해제. */
    fun release() {
        runCatching { car?.disconnect() }
        car = null
    }
}
