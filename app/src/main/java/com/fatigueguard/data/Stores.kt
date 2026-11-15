package com.fatigueguard.data

import android.content.Context
import com.fatigueguard.domain.Consent
import com.fatigueguard.domain.FatigueSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 동의 상태 저장 (기기 로컬). */
class ConsentStore(context: Context) {
    private val prefs = context.getSharedPreferences("consent", Context.MODE_PRIVATE)
    fun load() = Consent(
        telemetry = prefs.getBoolean("telemetry", true),
        location = prefs.getBoolean("location", true),
        storingLogs = prefs.getBoolean("logs", true),
        tts = prefs.getBoolean("tts", true),
    )
    fun save(c: Consent) = prefs.edit()
        .putBoolean("telemetry", c.telemetry).putBoolean("location", c.location)
        .putBoolean("logs", c.storingLogs).putBoolean("tts", c.tts).apply()
}

/** FatigueSession 을 JSON Lines 파일로 로컬 저장 (외부 전송 없음). */
class SessionStore(context: Context) {
    private val file = File(context.filesDir, "fatigue_sessions.jsonl")

    @Synchronized
    fun append(s: FatigueSession) { file.appendText(toJson(s).toString() + "\n") }

    @Synchronized
    fun readAll(limit: Int = 50): List<FatigueSession> {
        if (!file.exists()) return emptyList()
        return file.readLines().takeLast(limit).mapNotNull { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
    }

    @Synchronized
    fun clear() { file.delete() }

    private fun toJson(s: FatigueSession) = JSONObject()
        .put("timestamp", s.timestamp)
        .put("driving_duration_min", s.drivingDurationMin)
        .put("distance_km", s.distanceKm)
        .put("speed_std_dev", s.speedStdDev.toDouble())
        .put("accel_events", s.accelEvents)
        .put("fatigue_level", s.fatigueLevel)
        .put("recommended_rest_dist_km", s.recommendedRestDistKm ?: JSONObject.NULL)
        .put("next_rest_area_candidates", JSONArray(s.nextRestAreaCandidates))

    private fun fromJson(o: JSONObject) = FatigueSession(
        timestamp = o.getString("timestamp"),
        drivingDurationMin = o.getInt("driving_duration_min"),
        distanceKm = o.getDouble("distance_km"),
        speedStdDev = o.getDouble("speed_std_dev").toFloat(),
        accelEvents = o.getInt("accel_events"),
        fatigueLevel = o.getInt("fatigue_level"),
        recommendedRestDistKm = if (o.isNull("recommended_rest_dist_km")) null else o.getDouble("recommended_rest_dist_km"),
        nextRestAreaCandidates = o.getJSONArray("next_rest_area_candidates").let { a -> List(a.length()) { a.getString(it) } },
    )
}
