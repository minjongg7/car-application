package com.fatigueguard.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 표준 Android LocationManager 기반 위치 공급자.
 *
 * Pleos FusedLocation SDK 는 문서/커뮤니티 자료에서 "준비 중"으로 보이는 시점이 있어
 * (공개 여부가 불확실), SDK 없이도 동작하는 표준 API 로 먼저 구현했다.
 * FusedLocation SDK 를 쓸 수 있게 되면 이 클래스와 같은 `Flow<Location>` 을 내는 어댑터로 교체하면 된다.
 */
class AndroidLocationSource(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun enabledProviders(): List<String> {
        val m = manager ?: return emptyList()
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /** 권한이 있고 사용 가능한 provider 가 하나 이상 켜져 있는지. */
    fun isAvailable(): Boolean = hasPermission() && enabledProviders().isNotEmpty()

    /** 위치 갱신 스트림. 불가능하면 예외로 종료하므로 호출 측에서 catch 해서 무시/폴백한다. */
    @SuppressLint("MissingPermission") // hasPermission() 확인 후 호출, SecurityException 은 아래에서 처리
    fun updates(minTimeMs: Long = 2_000L): Flow<Location> = callbackFlow {
        val m = manager
        val providers = if (m != null && hasPermission()) enabledProviders() else emptyList()
        if (m == null || providers.isEmpty()) {
            close(IllegalStateException("위치를 사용할 수 없습니다 (권한 없음 또는 위치 provider 꺼짐)"))
            return@callbackFlow
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { trySend(location) }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            providers.forEach { m.requestLocationUpdates(it, minTimeMs, 0f, listener, Looper.getMainLooper()) }
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }
        awaitClose { m.removeUpdates(listener) }
    }
}
