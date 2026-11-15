package com.fatigueguard.data

import android.util.Log
import java.lang.reflect.Method

/**
 * 디버그용 SDK 탐색기. Vehicle SDK 클래스를 리플렉션으로 훑어서 "속도" 관련 API 이름을 찾는다.
 * 문서 본문을 읽을 수 없을 때 에뮬레이터에서 바로 정확한 이름을 얻기 위한 도구이며, 제출 전에는 제거해도 된다.
 * 전체 도메인/메서드 목록은 Logcat 태그 `PleosProbe` 로 출력된다.
 */
object VehicleApiProbe {
    private const val TAG = "PleosProbe"

    /** Vehicle 클래스 후보 (패키지 경로는 추정). 안 맞으면 여기에 실제 경로를 추가. */
    private val VEHICLE_CLASS_CANDIDATES = listOf("ai.pleos.playground.vehicle.Vehicle")
    private val KEYWORDS = listOf("speed", "velocity")

    fun run(): String {
        val vehicleClass = VEHICLE_CLASS_CANDIDATES.firstNotNullOfOrNull {
            runCatching { Class.forName(it) }.getOrNull()
        } ?: return "Vehicle 클래스를 찾지 못했습니다. 의존성과 패키지 경로를 확인하세요. 후보: ${VEHICLE_CLASS_CANDIDATES.joinToString()}"

        // 도메인 getter: 인자 없는 getXxx() 중 기본형/문자열/Class 가 아닌 것
        val domainGetters = vehicleClass.methods.filter {
            it.name.startsWith("get") && it.parameterCount == 0 &&
                !it.returnType.isPrimitive && it.returnType != String::class.java && it.returnType != Class::class.java
        }.sortedBy { it.name }

        val speedLines = mutableListOf<String>()
        Log.i(TAG, "Vehicle 도메인 ${domainGetters.size}개")
        for (getter in domainGetters) {
            val domain = getter.returnType
            val methods = domain.methods.filter { it.declaringClass != Any::class.java }.sortedBy { it.name }
            Log.i(TAG, "Vehicle.${getter.name}(): ${domain.simpleName} (메서드 ${methods.size}개)")
            for (m in methods) {
                val line = "  ${domain.simpleName}.${signature(m)}"
                Log.i(TAG, line)
                if (KEYWORDS.any { m.name.contains(it, ignoreCase = true) }) {
                    speedLines += "Vehicle.${getter.name}() -> $line".trim()
                    // 리스너 인터페이스의 콜백 이름도 함께 보여 준다
                    m.parameterTypes.filter { it.isInterface }.forEach { iface ->
                        speedLines += "    리스너 ${iface.simpleName}: " +
                            iface.methods.joinToString { signature(it) }
                    }
                }
            }
        }

        return buildString {
            append("도메인 ${domainGetters.size}개 · 속도 관련 메서드 ${speedLines.count { !it.startsWith("    ") }}건\n")
            if (speedLines.isEmpty()) {
                append("속도 관련 메서드가 없습니다. Vehicle SDK 가 속도를 제공하지 않을 수 있으니 ")
                append("Logcat(PleosProbe) 전체 목록을 확인하거나 기술지원에 문의하세요.")
            } else {
                append(speedLines.take(20).joinToString("\n"))
            }
        }
    }

    private fun signature(m: Method): String =
        "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}"
}
