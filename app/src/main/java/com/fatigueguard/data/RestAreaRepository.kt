package com.fatigueguard.data

import com.fatigueguard.domain.RestAreaCandidate
import com.hmgapps.core.trip.RestAreaCatalog
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 경량 휴게소 데이터(오프라인 폴백). 좌표는 근사값이므로 실서비스에서는 NaviHelper/데이터 소스로 교체. */
class RestAreaRepository(private val items: List<RestAreaCandidate> = DEFAULT) {

    fun nearest(lat: Double, lon: Double, n: Int = 3): List<RestAreaCandidate> =
        items.map { it.copy(distanceKm = (haversineKm(lat, lon, it.lat, it.lon) * 10).toInt() / 10.0) }
            .sortedBy { it.distanceKm }
            .take(n)

    companion object {
        /** 휴게소 목록은 :core RestAreaCatalog 하나로 관리한다 (Family Care 스탬프·동승자 맞춤 추천과 같은 데이터). */
        val DEFAULT: List<RestAreaCandidate> = RestAreaCatalog.ALL.map { RestAreaCandidate(it.name, 0.0, it.lat, it.lon) }

        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }
    }
}
