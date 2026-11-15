package com.fatigueguard.nav.osm

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fatigueguard.nav.Poi
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Vector-tile based automotive map.
 * OpenFreeMap supplies the base vector tiles; MapLibre renders them and extrudes
 * OSM building footprints into 3D. Route and live vehicle position remain app-owned.
 */
private const val CAR_STYLE_URL = "https://tiles.openfreemap.org/styles/bright"
private const val ROUTE_SOURCE_ID = "fatigueguard-route-source"
private const val VEHICLE_SOURCE_ID = "fatigueguard-vehicle-source"
private const val DEST_SOURCE_ID = "fatigueguard-destination-source"

@Composable
fun CarMapLibre(
    route: OsmRoute?,
    me: LatLon?,
    heading: Double?,
    dest: LatLon?,
    destName: String?,
    pois: List<Poi>,
    follow: Boolean,
    ringColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapViewState = remember { mutableStateOf<MapView?>(null) }
    val mapState = remember { mutableStateOf<MapLibreMap?>(null) }
    val styleReady = remember { mutableStateOf(false) }
    val mapLoaded = remember { mutableStateOf(false) }
    // 한 번 실패했으면(같은 실행 중) 안내 화면 등에서 다시 기다리지 않고 바로 2D 지도를 쓴다.
    val mapFailed = remember { mutableStateOf(MapDiag.failedOnce) }

    // 3D 지도가 일정 시간 안에 그려지지 않거나(에뮬레이터 GL 문제) 스타일 로드에 실패하면 2D OSM 지도로 전환한다.
    LaunchedEffect(Unit) {
        delay(8_000)
        if (!mapLoaded.value) { MapDiag.failedOnce = true; mapFailed.value = true }
    }
    if (mapFailed.value) {
        androidx.compose.foundation.layout.Box(modifier) {
            OsmMap(route, me, heading, dest, destName, pois, follow, ringColor, Modifier.matchParentSize())
        }
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapLibre.getInstance(ctx.applicationContext)
            // TextureView 모드: SurfaceView(GLSurfaceView)가 Compose/에뮬레이터에서 안 그려지는 문제를 피한다.
            val options = MapLibreMapOptions.createFromAttributes(ctx).textureMode(true)
            MapView(ctx, options).apply {
                onCreate(Bundle())
                mapViewState.value = this
                addOnDidFinishLoadingMapListener { mapLoaded.value = true }
                addOnDidFailLoadingMapListener { msg -> MapDiag.mapError.value = msg; mapFailed.value = true }
                getMapAsync { map ->
                    mapState.value = map
                    map.uiSettings.isCompassEnabled = true
                    map.uiSettings.isAttributionEnabled = true
                    map.uiSettings.isLogoEnabled = true
                    map.uiSettings.isRotateGesturesEnabled = true
                    map.uiSettings.isTiltGesturesEnabled = true
                    map.setStyle(CAR_STYLE_URL) { style ->
                        // OpenFreeMap vector tiles expose OSM building heights in these fields.
                        if (style.getSource("openmaptiles") != null && style.getLayer("fatigueguard-buildings-3d") == null) {
                            val buildings = FillExtrusionLayer("fatigueguard-buildings-3d", "openmaptiles").apply {
                                sourceLayer = "building"
                                minZoom = 14f
                                setFilter(
                                    Expression.all(
                                        Expression.has("render_height"),
                                        Expression.has("render_min_height")
                                    )
                                )
                                setProperties(
                                    PropertyFactory.fillExtrusionColor("#cbd5df"),
                                    PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
                                    PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
                                    PropertyFactory.fillExtrusionOpacity(0.92f)
                                )
                            }
                            val labelLayer = style.getLayer("road-label")
                            if (labelLayer != null) style.addLayerBelow(buildings, labelLayer.id) else style.addLayer(buildings)
                        }

                        if (style.getSource(ROUTE_SOURCE_ID) == null) {
                            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, FeatureCollection.fromFeatures(arrayOf<org.maplibre.geojson.Feature>())))
                            style.addLayer(
                                LineLayer("fatigueguard-route-casing", ROUTE_SOURCE_ID).withProperties(
                                    PropertyFactory.lineColor("#ffffff"),
                                    PropertyFactory.lineWidth(10f),
                                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                                )
                            )
                            style.addLayer(
                                LineLayer("fatigueguard-route-line", ROUTE_SOURCE_ID).withProperties(
                                    PropertyFactory.lineColor("#1688ff"),
                                    PropertyFactory.lineWidth(6f),
                                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                                )
                            )
                        }
                        if (style.getSource(VEHICLE_SOURCE_ID) == null) {
                            style.addSource(GeoJsonSource(VEHICLE_SOURCE_ID, FeatureCollection.fromFeatures(arrayOf<org.maplibre.geojson.Feature>())))
                            style.addLayer(
                                CircleLayer("fatigueguard-vehicle-halo", VEHICLE_SOURCE_ID).withProperties(
                                    PropertyFactory.circleRadius(17f),
                                    PropertyFactory.circleColor("#1688ff"),
                                    PropertyFactory.circleOpacity(0.24f)
                                )
                            )
                            style.addLayer(
                                CircleLayer("fatigueguard-vehicle-dot", VEHICLE_SOURCE_ID).withProperties(
                                    PropertyFactory.circleRadius(8f),
                                    PropertyFactory.circleColor("#087cf0"),
                                    PropertyFactory.circleStrokeColor("#ffffff"),
                                    PropertyFactory.circleStrokeWidth(3f)
                                )
                            )
                        }
                        if (style.getSource(DEST_SOURCE_ID) == null) {
                            style.addSource(GeoJsonSource(DEST_SOURCE_ID, FeatureCollection.fromFeatures(arrayOf<org.maplibre.geojson.Feature>())))
                            style.addLayer(
                                CircleLayer("fatigueguard-destination-dot", DEST_SOURCE_ID).withProperties(
                                    PropertyFactory.circleRadius(9f),
                                    PropertyFactory.circleColor("#e5484d"),
                                    PropertyFactory.circleStrokeColor("#ffffff"),
                                    PropertyFactory.circleStrokeWidth(3f)
                                )
                            )
                        }
                        styleReady.value = true
                    }
                }
            }
        },
        update = { /* state updates are applied below after style load */ }
    )

    DisposableEffect(lifecycleOwner, mapViewState.value) {
        val view = mapViewState.value
        if (view == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.onPause()
            view.onStop()
            view.onDestroy()
            mapViewState.value = null
            mapState.value = null
            styleReady.value = false
        }
    }

    LaunchedEffect(mapState.value, styleReady.value, route, me, dest, heading, follow) {
        val map = mapState.value ?: return@LaunchedEffect
        if (!styleReady.value) return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect

        val routeSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
        val routePoints = route?.points.orEmpty()
        if (routePoints.size >= 2) {
            routeSource?.setGeoJson(
                LineString.fromLngLats(routePoints.map { Point.fromLngLat(it.lon, it.lat) })
            )
        } else {
            routeSource?.setGeoJson(FeatureCollection.fromFeatures(arrayOf<org.maplibre.geojson.Feature>()))
        }

        val vehicleSource = style.getSourceAs<GeoJsonSource>(VEHICLE_SOURCE_ID)
        vehicleSource?.setGeoJson(
            me?.let { FeatureCollection.fromFeature(org.maplibre.geojson.Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))) }
                ?: FeatureCollection.fromFeatures(arrayOf<org.maplibre.geojson.Feature>())
        )
        val destinationSource = style.getSourceAs<GeoJsonSource>(DEST_SOURCE_ID)
        destinationSource?.setGeoJson(
            dest?.let { FeatureCollection.fromFeature(org.maplibre.geojson.Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))) }
                ?: FeatureCollection.fromFeatures(arrayOf<org.maplibre.geojson.Feature>())
        )

        val target = if (follow && me != null) me else {
            when {
                routePoints.isNotEmpty() -> routePoints[routePoints.size / 2]
                dest != null -> dest
                me != null -> me
                else -> LatLon(37.5665, 126.9780)
            }
        }
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(target.lat, target.lon))
            .zoom(if (follow) 16.7 else if (routePoints.isNotEmpty()) 12.5 else 14.5)
            .tilt(if (follow) 58.0 else 48.0)
            .bearing(if (follow) heading ?: 0.0 else 0.0)
            .build()
    }
}

/** 임시 진단용 상태 (지도 실패 원인 화면 표시). */
object MapDiag {
    val mapError = mutableStateOf<String?>(null)
    val tileError = mutableStateOf<String?>(null)
    @Volatile var failedOnce = false
}
