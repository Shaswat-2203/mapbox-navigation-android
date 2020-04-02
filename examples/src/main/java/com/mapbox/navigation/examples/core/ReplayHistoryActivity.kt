package com.mapbox.navigation.examples.core

import android.annotation.SuppressLint
import android.location.Location
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.gson.internal.LinkedTreeMap
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineCallback
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.android.core.location.LocationEngineResult
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.location.LocationComponent
import com.mapbox.mapboxsdk.location.LocationComponentActivationOptions
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.location.modes.RenderMode
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.navigation.base.extensions.applyDefaultParams
import com.mapbox.navigation.base.extensions.coordinates
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.directions.session.RoutesRequestCallback
import com.mapbox.navigation.core.replay.history.CustomEventMapper
import com.mapbox.navigation.core.replay.history.ReplayEventBase
import com.mapbox.navigation.core.replay.history.ReplayHistoryLocationEngine
import com.mapbox.navigation.core.replay.history.ReplayHistoryMapper
import com.mapbox.navigation.core.replay.history.ReplayHistoryPlayer
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.examples.R
import com.mapbox.navigation.examples.utils.Utils
import com.mapbox.navigation.examples.utils.extensions.toPoint
import com.mapbox.navigation.ui.camera.NavigationCamera
import com.mapbox.navigation.ui.map.NavigationMapboxMap
import com.mapbox.navigation.ui.map.NavigationMapboxMapInstanceState
import kotlinx.android.synthetic.main.activity_trip_service.mapView
import kotlinx.android.synthetic.main.replay_engine_example_activity_layout.*
import timber.log.Timber

/**
 * To ensure proper functioning of this example make sure your Location is turned on.
 */
class ReplayHistoryActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        const val DEFAULT_INTERVAL_IN_MILLISECONDS = 1000L
        const val DEFAULT_MAX_WAIT_TIME = DEFAULT_INTERVAL_IN_MILLISECONDS * 5
    }

    private var locationEngine: LocationEngine? = null
    private var mapboxNavigation: MapboxNavigation? = null
    private var navigationMapboxMap: NavigationMapboxMap? = null
    private var mapInstanceState: NavigationMapboxMapInstanceState? = null
    private var locationComponent: LocationComponent? = null
    private var replayHistoryPlayer: ReplayHistoryPlayer? = null

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.replay_engine_example_activity_layout)
        mapView.onCreate(savedInstanceState)

        val mapboxNavigationOptions = MapboxNavigation.defaultNavigationOptions(
            this,
            Utils.getMapboxAccessToken(this)
        )

        val replayHistoryMapper = ReplayHistoryMapper(
            customEventMapper = ReplayCustomEventMapper())
        val replayEvents = replayHistoryMapper.mapToReplayEvents(rideHistoryExample)
        val replayHistoryPlayer = ReplayHistoryPlayer(replayEvents)
        locationEngine = ReplayHistoryLocationEngine(replayHistoryPlayer)
        this.replayHistoryPlayer = replayHistoryPlayer

        mapboxNavigation = MapboxNavigation(
            applicationContext,
            Utils.getMapboxAccessToken(this),
            mapboxNavigationOptions,
            locationEngine = locationEngine!!
        )
        startNavigation.setOnClickListener {
            startNavigation()
        }
        mapView.getMapAsync(this)
    }

    override fun onMapReady(mapboxMap: MapboxMap) {
        mapboxMap.setStyle(Style.MAPBOX_STREETS) { style ->
            initLocationComponent(style, mapboxMap)
            navigationMapboxMap = NavigationMapboxMap(mapView, mapboxMap).also {
                it.addProgressChangeListener(mapboxNavigation!!)
                mapInstanceState?.let { state ->
                    it.restoreFrom(state)
                }
            }
        }
        mapboxMap.addOnMapLongClickListener { latLng ->
            selectMapLocation(latLng)
            true
        }
        locationComponent = mapboxMap.locationComponent

        replayHistoryPlayer?.observeReplayEvents {
            it.events.forEach { event ->
                when (event) {
                    is ReplayEventInitialRoute -> {
                        event.coordinates.lastOrNull()?.let {
                            selectMapLocation(it)
                        }
                    }
                }
            }
        }
        replayHistoryPlayer?.play(this)
    }

    private fun selectMapLocation(latLng: LatLng) {
        navigationMapboxMap?.retrieveMap()?.locationComponent?.lastKnownLocation?.let { originLocation ->
            mapboxNavigation?.requestRoutes(
                RouteOptions.builder().applyDefaultParams()
                    .accessToken(Utils.getMapboxAccessToken(applicationContext))
                    .coordinates(originLocation.toPoint(), null, latLng.toPoint())
                    .alternatives(true)
                    .profile(DirectionsCriteria.PROFILE_DRIVING_TRAFFIC)
                    .build(),
                routesReqCallback
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNavigation() {
        if (mapboxNavigation?.getRoutes()?.isNotEmpty() == true) {
            navigationMapboxMap?.updateLocationLayerRenderMode(RenderMode.GPS)
            navigationMapboxMap?.updateCameraTrackingMode(NavigationCamera.NAVIGATION_TRACKING_MODE_GPS)
            navigationMapboxMap?.startCamera(mapboxNavigation?.getRoutes()!![0])
        }
        mapboxNavigation?.startTripSession()
        startNavigation.visibility = View.GONE
    }

    @SuppressLint("RestrictedApi")
    fun initLocationComponent(loadedMapStyle: Style, mapboxMap: MapboxMap) {
        initLocationEngine()

        mapboxMap.moveCamera(CameraUpdateFactory.zoomTo(15.0))
        mapboxMap.locationComponent.let { locationComponent ->
            val locationComponentActivationOptions =
                LocationComponentActivationOptions.builder(this, loadedMapStyle)
                    .locationEngine(locationEngine)
                    .build()

            locationComponent.activateLocationComponent(locationComponentActivationOptions)
            locationComponent.isLocationComponentEnabled = true
            locationComponent.cameraMode = CameraMode.TRACKING
            locationComponent.renderMode = RenderMode.COMPASS
        }
    }

    fun initLocationEngine() {
        val requestLocationUpdateRequest =
            LocationEngineRequest.Builder(DEFAULT_INTERVAL_IN_MILLISECONDS)
                .setPriority(LocationEngineRequest.PRIORITY_NO_POWER)
                .setMaxWaitTime(DEFAULT_MAX_WAIT_TIME)
                .build()

        locationEngine?.requestLocationUpdates(
            requestLocationUpdateRequest,
            locationListenerCallback,
            mainLooper
        )
        locationEngine?.getLastLocation(locationListenerCallback)
    }

    private val routesReqCallback = object : RoutesRequestCallback {
        override fun onRoutesReady(routes: List<DirectionsRoute>) {
            Timber.d("route request success %s", routes.toString())
            if (routes.isNotEmpty()) {
                navigationMapboxMap?.drawRoute(routes[0])
                startNavigation.visibility = View.VISIBLE
            } else {
                startNavigation.visibility = View.GONE
            }
        }

        override fun onRoutesRequestFailure(throwable: Throwable, routeOptions: RouteOptions) {
            Timber.e("route request failure %s", throwable.toString())
        }

        override fun onRoutesRequestCanceled(routeOptions: RouteOptions) {
            Timber.d("route request canceled")
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        mapboxNavigation?.registerLocationObserver(locationObserver)
        Snackbar.make(container, R.string.msg_long_press_for_destination, Snackbar.LENGTH_SHORT)
            .show()
    }

    public override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    public override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapboxNavigation?.unregisterLocationObserver(locationObserver)
        stopLocationUpdates()
        mapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        replayHistoryPlayer?.finish()
        mapboxNavigation?.stopTripSession()
        mapboxNavigation?.onDestroy()
        mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    private val locationListenerCallback: LocationEngineCallback<LocationEngineResult> =
        object : LocationEngineCallback<LocationEngineResult> {
            override fun onSuccess(result: LocationEngineResult) {
                result.lastLocation?.let {
                    locationComponent?.forceLocationUpdate(it)
                }
            }

            override fun onFailure(exception: Exception) {
                Timber.i(exception)
            }
        }

    private fun stopLocationUpdates() {
        locationEngine?.removeLocationUpdates(locationListenerCallback)
    }

    private val locationObserver = object : LocationObserver {
        override fun onRawLocationChanged(rawLocation: Location) {
            Timber.d("raw location %s", rawLocation.toString())
        }

        override fun onEnhancedLocationChanged(
            enhancedLocation: Location,
            keyPoints: List<Location>
        ) {
            if (keyPoints.isNotEmpty()) {
                locationComponent?.forceLocationUpdate(keyPoints, true)
            } else {
                locationComponent?.forceLocationUpdate(enhancedLocation)
            }
        }
    }
}

/**
 * Note that ride history can be quite large. In order support larger files they should
 * be downloaded or loaded from external sources.
 */
private val rideHistoryExample: String = """
    {"events":[{"type":"start_transit","properties":1580744133.13,"event_timestamp":1580744133.130429},{"type":"initial_route","properties":{"routeIndex":"0","distance":2001.4,"duration":431.0,"geometry":"w~gr~A_j_nO_NfI`B~MVdD?nAWlDkA~C}EhEgCpAsPfCkW`Ao]nAcQg@iLwBgJqCeEy@kAiEbBoKdOaBnDaD`l@}x@cVyl@oI_X_Nwj@WeCvSc`@mEyDqGyHmEyGoDsFcB_DaBoFqCsIaBwGmAoDeEsK{EqIcGoI}EyGoHyJiHiJ_NqNgY{YgYaZ_N_NoI{JiG_IiCgEyBsDoDeHuD}GyG}KsVie@wI{OqQk\\_IcLcGeHqHmJaGeHgJuKyLgOqLiPuKeN_IoK_DaEqGgJqMuPeEoFoD{EsFwGyL`AqBwBqCiBoDoA_Dg@{EQ_DUcGf@iC`AyB|A{@vBe@vBm@~COxBUpD?pC?vBT|Ad@xBbB`B`Bf@bB?xQOhH?~CWbBg@xBy@dEf@l@dTLxI?da@","weight":431.0,"weight_name":"routability","legs":[{"distance":2001.4,"duration":431.0,"summary":"K809, Ginnheimer Landstraße","steps":[{"distance":29.4,"duration":3.0,"geometry":"w~gr~A_j_nO_NfI","name":"Salvador-Allende-Straße","mode":"driving","maneuver":{"location":[8.634544,50.12326],"bearing_before":0.0,"bearing_after":336.0,"instruction":"Salvador-Allende-Straßeを北西に進む","type":"depart"},"voiceInstructions":[{"distanceAlongGeometry":29.4,"announcement":"salvador-Allende-Straßeを北西に進む その先 左折してRödelheimer Straßeを進む","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">salvador-Allende-Straßeを北西に進む その先 左折してRödelheimer Straßeを進む</prosody></amazon:effect></speak>"}],"bannerInstructions":[{"distanceAlongGeometry":29.4,"primary":{"text":"Rödelheimer Straße","components":[{"text":"Rödelheimer Straße","type":"text"}],"type":"turn","modifier":"left"},"sub":{"text":"An den Bangerten","components":[{"text":"An den Bangerten","type":"text"}],"type":"turn","modifier":"slight right"}}],"driving_side":"right","weight":3.0,"intersections":[{"location":[8.634544,50.12326],"bearings":[336],"entry":[true],"out":0}]},{"distance":27.0,"duration":17.0,"geometry":"wmhr~Aw__nO`B~MVdD?nA","name":"Rödelheimer Straße","mode":"driving","maneuver":{"location":[8.63438,50.123501],"bearing_before":336.0,"bearing_after":252.0,"instruction":"左折してRödelheimer Straßeを進む","type":"turn","modifier":"left"},"voiceInstructions":[{"distanceAlongGeometry":23.8,"announcement":"斜め右に向かい、An den Bangertenを進む","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">斜め右に向かい、An den Bangertenを進む</prosody></amazon:effect></speak>"}],"bannerInstructions":[{"distanceAlongGeometry":27.0,"primary":{"text":"An den Bangerten","components":[{"text":"An den Bangerten","type":"text"}],"type":"turn","modifier":"slight right"}}],"driving_side":"right","weight":17.0,"intersections":[{"location":[8.63438,50.123501],"bearings":[70,156,252],"entry":[true,false,true],"in":1,"out":2}]},{"distance":262.0,"duration":45.0,"geometry":"}ihr~Aai~mOWlDkA~C}EhEgCpAsPfCkW`Ao]nAcQg@iLwBgJqCeEy@kAiE","name":"An den Bangerten","mode":"driving","maneuver":{"location":[8.634018,50.12344],"bearing_before":258.0,"bearing_after":298.0,"instruction":"斜め右に向かい、An den Bangertenを進む","type":"turn","modifier":"slight right"},"voiceInstructions":[{"distanceAlongGeometry":242.0,"announcement":"800 フィート先、右折してHäuser Gasseを進む","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">800 フィート先、右折してHäuser Gasseを進む</prosody></amazon:effect></speak>"},{"distanceAlongGeometry":87.3,"announcement":"右折してHäuser Gasseを進む","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">右折してHäuser Gasseを進む</prosody></amazon:effect></speak>"}],"bannerInstructions":[{"distanceAlongGeometry":262.0,"primary":{"text":"Häuser Gasse","components":[{"text":"Häuser Gasse","type":"text"}],"type":"turn","modifier":"right"}}],"driving_side":"right","weight":45.0,"intersections":[{"location":[8.634018,50.12344],"bearings":[78,298,313,353],"entry":[false,true,false,false],"in":0,"out":1},{"location":[8.633708,50.123669],"bearings":[155,351],"entry":[false,true],"in":0,"out":1},{"location":[8.63364,50.123951],"bearings":[171,357],"entry":[false,true],"in":0,"out":1},{"location":[8.633648,50.125332],"bearings":[15,190],"entry":[true,false],"in":1,"out":0}]},{"distance":160.0,"duration":24.0,"geometry":"_tlr~As~}mObBoKdOaBnDaD`l@}x@","name":"Häuser Gasse","mode":"driving","maneuver":{"location":[8.63385,50.125648],"bearing_before":38.0,"bearing_after":111.0,"instruction":"右折してHäuser Gasseを進む","type":"turn","modifier":"right"},"voiceInstructions":[{"distanceAlongGeometry":140.0,"announcement":"500 フィート先、左折してFritzlarer Straßeを進む","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">500 フィート先、左折してFritzlarer Straßeを進む</prosody></amazon:effect></speak>"},{"distanceAlongGeometry":100.0,"announcement":"左折してFritzlarer Straßeを進む","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">左折してFritzlarer Straßeを進む</prosody></amazon:effect></speak>"}],"bannerInstructions":[{"distanceAlongGeometry":160.0,"primary":{"text":"Fritzlarer Straße","components":[{"text":"Fritzlarer Straße","type":"text"}],"type":"turn","modifier":"left"}}],"driving_side":"right","weight":24.0,"intersections":[{"location":[8.63385,50.125648],"bearings":[111,218,358],"entry":[true,false,true],"in":1,"out":0}]},{"distance":162.0,"duration":37.0,"geometry":"cnjr~Aem`nOcVyl@oI_X_Nwj@WeC","name":"Fritzlarer Straße","mode":"driving","maneuver":{"location":[8.635108,50.124531],"bearing_before":140.0,"bearing_after":52.0,"instruction":"左折してFritzlarer Straßeを進む","type":"turn","modifier":"left"},"voiceInstructions":[{"distanceAlongGeometry":142.0,"announcement":"500 フィート先、右に曲がり、Fritzlarer Straßeを進む","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">500 フィート先、右に曲がり、Fritzlarer Straßeを進む</prosody></amazon:effect></speak>"},{"distanceAlongGeometry":65.7,"announcement":"右に曲がり、Fritzlarer Straßeを進む その先 左折してRödelheimer Straße(K809)を進む","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">右に曲がり、Fritzlarer Straßeを進む その先 左折してRödelheimer Straße(<say-as interpret-as=\"address\">K809</say-as>)を進む</prosody></amazon:effect></speak>"}],"bannerInstructions":[{"distanceAlongGeometry":162.0,"primary":{"text":"Fritzlarer Straße","components":[{"text":"Fritzlarer Straße","type":"text"}],"type":"turn","modifier":"right"}},{"distanceAlongGeometry":65.7,"primary":{"text":"Fritzlarer Straße","components":[{"text":"Fritzlarer Straße","type":"text"}],"type":"turn","modifier":"right"},"sub":{"text":"Rödelheimer Straße","components":[{"text":"Rödelheimer Straße","type":"text"}],"type":"turn","modifier":"left"}}],"driving_side":"right","weight":37.0,"intersections":[{"location":[8.635108,50.124531],"bearings":[52,142,320],"entry":[true,false,false],"in":2,"out":0},{"location":[8.63694,50.125309],"bearings":[75,241,320],"entry":[true,false,true],"in":1,"out":0}]},{"distance":53.0,"duration":12.0,"geometry":"o_lr~A}cdnOvSc`@","name":"Fritzlarer Straße","mode":"driving","maneuver":{"location":[8.637008,50.12532],"bearing_before":75.0,"bearing_after":134.0,"instruction":"右に曲がり、Fritzlarer Straßeを進む","type":"continue","modifier":"right"},"voiceInstructions":[{"distanceAlongGeometry":53.0,"announcement":"左折してRödelheimer Straße(K809)を進む","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">左折してRödelheimer Straße(<say-as interpret-as=\"address\">K809</say-as>)を進む</prosody></amazon:effect></speak>"}],"bannerInstructions":[{"distanceAlongGeometry":53.0,"primary":{"text":"Rödelheimer Straße","components":[{"text":"Rödelheimer Straße","type":"text"}],"type":"turn","modifier":"left"},"secondary":{"text":"K809","components":[{"text":"K809","type":"icon"}],"type":"turn","modifier":"left"}}],"driving_side":"right","weight":12.0,"intersections":[{"location":[8.637008,50.12532],"bearings":[68,134,255],"entry":[false,true,false],"in":2,"out":1}]},{"distance":944.0,"duration":196.0,"geometry":"wjkr~AaeenOmEyDqGyHmEyGoDsFcB_DaBoFqCsIaBwGmAoDeEsK{EqIcGoI}EyGoHyJiHiJ_NqNgY{YgYaZ_N_NoI{JiG_IiCgEyBsDoDeHuD}GyG}KsVie@wI{OqQk\\_IcLcGeHqHmJaGeHgJuKyLgOqLiPuKeN_IoK_DaEqGgJqMuPeEoF","name":"Rödelheimer Straße (K809)","ref":"K809","mode":"driving","maneuver":{"location":[8.637538,50.124989],"bearing_before":134.0,"bearing_after":31.0,"instruction":"左折してRödelheimer Straße(K809)を進む","type":"turn","modifier":"left"},"voiceInstructions":[{"distanceAlongGeometry":924.0,"announcement":"Rödelheimer Straße(K809)を0.5 マイル直進する","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">Rödelheimer Straße(<say-as interpret-as=\"address\">K809</say-as>)を0.5 マイル直進する</prosody></amazon:effect></speak>"},{"distanceAlongGeometry":337.1,"announcement":"0.25 マイル先、斜め左に向かい、Ginnheimer Landstraße(K809)を進む","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">0.25 マイル先、斜め左に向かい、Ginnheimer Landstraße(<say-as interpret-as=\"address\">K809</say-as>)を進む</prosody></amazon:effect></speak>"},{"distanceAlongGeometry":72.2,"announcement":"斜め左に向かい、Ginnheimer Landstraße(K809)を進む その先 斜め右に曲がり、Ginnheimer Landstraßeを進む","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">斜め左に向かい、Ginnheimer Landstraße(<say-as interpret-as=\"address\">K809</say-as>)を進む その先 斜め右に曲がり、Ginnheimer Landstraßeを進む</prosody></amazon:effect></speak>"}],"bannerInstructions":[{"distanceAlongGeometry":944.0,"primary":{"text":"Ginnheimer Landstraße","components":[{"text":"Ginnheimer Landstraße","type":"text"}],"type":"turn","modifier":"slight left"},"secondary":{"text":"K809","components":[{"text":"K809","type":"icon"}],"type":"turn","modifier":"slight left"}},{"distanceAlongGeometry":72.2,"primary":{"text":"Ginnheimer Landstraße","components":[{"text":"Ginnheimer Landstraße","type":"text"}],"type":"turn","modifier":"slight left"},"secondary":{"text":"K809","components":[{"text":"K809","type":"icon"}],"type":"turn","modifier":"slight left"},"sub":{"text":"Ginnheimer Landstraße","components":[{"text":"Ginnheimer Landstraße","type":"text"}],"type":"turn","modifier":"slight right"}}],"driving_side":"right","weight":196.0,"intersections":[{"location":[8.637538,50.124989],"bearings":[31,206,314],"entry":[true,true,false],"in":2,"out":0},{"location":[8.63813,50.125469],"bearings":[57,134,224,322],"entry":[true,false,false,true],"in":2,"out":0},{"location":[8.63856,50.125641],"bearings":[54,240],"entry":[true,false],"in":1,"out":0},{"location":[8.639188,50.126019],"bearings":[40,134,220,313],"entry":[true,true,false,false],"in":2,"out":0},{"location":[8.639518,50.126282],"bearings":[38,218],"entry":[true,false],"in":1,"out":0},{"location":[8.640378,50.12709],"bearings":[33,213],"entry":[true,false],"in":1,"out":0},{"location":[8.6414,50.128052],"bearings":[43,130,217,297],"entry":[true,true,false,false],"in":2,"out":0},{"location":[8.6415,50.12812],"bearings":[43,223],"entry":[true,false],"in":1,"out":0},{"location":[8.64159,50.128181],"bearings":[47,223],"entry":[true,false],"in":1,"out":0},{"location":[8.641738,50.128269],"bearings":[45,227],"entry":[true,false],"in":1,"out":0},{"location":[8.6427,50.12888],"bearings":[45,132,226],"entry":[true,false,false],"in":2,"out":0},{"location":[8.64297,50.129051],"bearings":[45,225],"entry":[true,false],"in":1,"out":0},{"location":[8.643798,50.129639],"bearings":[37,217,306],"entry":[true,false,true],"in":1,"out":0},{"location":[8.64398,50.129791],"bearings":[36,134,217,307],"entry":[true,false,false,true],"in":2,"out":0},{"location":[8.644128,50.129921],"bearings":[36,216,306],"entry":[true,false,false],"in":1,"out":0},{"location":[8.64433,50.1301],"bearings":[37,130,216],"entry":[true,false,false],"in":2,"out":0},{"location":[8.64459,50.130322],"bearings":[39,217],"entry":[true,false],"in":1,"out":0},{"location":[8.644868,50.130539],"bearings":[38,219],"entry":[true,false],"in":1,"out":0},{"location":[8.645408,50.130981],"bearings":[40,218],"entry":[true,false],"in":1,"out":0},{"location":[8.645588,50.131119],"bearings":[10,38,220],"entry":[false,true,false],"in":2,"out":1},{"location":[8.64587,50.131351],"bearings":[38,218,298],"entry":[true,false,false],"in":1,"out":0}]},{"distance":55.0,"duration":23.0,"geometry":"s~wr~AkuunOoD{EsFwGyL`A","name":"Ginnheimer Landstraße (K809)","ref":"K809","mode":"driving","maneuver":{"location":[8.64599,50.131451],"bearing_before":38.0,"bearing_after":355.0,"instruction":"斜め左に向かい、Ginnheimer Landstraße(K809)を進む","type":"turn","modifier":"slight left"},"voiceInstructions":[{"distanceAlongGeometry":35.9,"announcement":"斜め右に曲がり、Ginnheimer Landstraßeを進む その先 左に曲がり、Ginnheimer Landstraßeを進む","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">斜め右に曲がり、Ginnheimer Landstraßeを進む その先 左に曲がり、Ginnheimer Landstraßeを進む</prosody></amazon:effect></speak>"}],"bannerInstructions":[{"distanceAlongGeometry":55.0,"primary":{"text":"Ginnheimer Landstraße","components":[{"text":"Ginnheimer Landstraße","type":"text"}],"type":"turn","modifier":"slight right"}},{"distanceAlongGeometry":35.9,"primary":{"text":"Ginnheimer Landstraße","components":[{"text":"Ginnheimer Landstraße","type":"text"}],"type":"turn","modifier":"slight right"},"sub":{"text":"Ginnheimer Landstraße","components":[{"text":"Ginnheimer Landstraße","type":"text"}],"type":"turn","modifier":"left"}}],"driving_side":"right","weight":23.0,"intersections":[{"location":[8.64599,50.131451],"bearings":[39,94,218],"entry":[true,true,false],"in":2,"out":0},{"location":[8.6461,50.131538],"bearings":[36,145,219,297,341],"entry":[true,true,false,false,false],"in":2,"out":0},{"location":[8.64624,50.13166],"bearings":[184,216,355],"entry":[false,false,true],"in":1,"out":2}]},{"distance":132.0,"duration":29.0,"geometry":"qyxr~A}bvnOqBwBqCiBoDoA_Dg@{EQ_DUcGf@iC`AyB|A{@vBe@vBm@~COxBUpD?pC?vBT|Ad@xB","name":"Ginnheimer Landstraße","mode":"driving","maneuver":{"location":[8.646208,50.131882],"bearing_before":355.0,"bearing_after":29.0,"instruction":"斜め右に曲がり、Ginnheimer Landstraßeを進む","type":"continue","modifier":"slight right"},"voiceInstructions":[{"distanceAlongGeometry":68.3,"announcement":"左に曲がり、Ginnheimer Landstraßeを進む その先 右に曲がり、Ginnheimer Landstraßeを進む","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">左に曲がり、Ginnheimer Landstraßeを進む その先 右に曲がり、Ginnheimer Landstraßeを進む</prosody></amazon:effect></speak>"}],"bannerInstructions":[{"distanceAlongGeometry":132.0,"primary":{"text":"Ginnheimer Landstraße","components":[{"text":"Ginnheimer Landstraße","type":"text"}],"type":"turn","modifier":"left"}},{"distanceAlongGeometry":68.3,"primary":{"text":"Ginnheimer Landstraße","components":[{"text":"Ginnheimer Landstraße","type":"text"}],"type":"turn","modifier":"left"},"sub":{"text":"Ginnheimer Landstraße","components":[{"text":"Ginnheimer Landstraße","type":"text"}],"type":"turn","modifier":"right"}}],"driving_side":"right","weight":29.0,"intersections":[{"location":[8.646208,50.131882],"bearings":[29,175,355],"entry":[true,false,true],"in":1,"out":0},{"location":[8.64632,50.132011],"bearings":[16,87,209],"entry":[true,false,false],"in":2,"out":0},{"location":[8.64636,50.132099],"bearings":[6,196],"entry":[true,false],"in":1,"out":0},{"location":[8.6464,50.13237],"bearings":[184,351],"entry":[false,true],"in":0,"out":1},{"location":[8.6463,50.132629],"bearings":[84,163,295],"entry":[false,false,true],"in":1,"out":2},{"location":[8.645878,50.132721],"bearings":[102,254],"entry":[false,true],"in":0,"out":1}]},{"distance":101.0,"duration":23.0,"geometry":"clzr~AycunObB`B`Bf@bB?xQOhH?~CWbBg@xBy@dEf@","name":"Ginnheimer Landstraße","mode":"driving","maneuver":{"location":[8.645709,50.13269],"bearing_before":254.0,"bearing_after":194.0,"instruction":"左に曲がり、Ginnheimer Landstraßeを進む","type":"continue","modifier":"left"},"voiceInstructions":[{"distanceAlongGeometry":65.9,"announcement":"右に曲がり、Ginnheimer Landstraßeを進む その先 まもなく目の目的地に到着します","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">右に曲がり、Ginnheimer Landstraßeを進む その先 まもなく目の目的地に到着します</prosody></amazon:effect></speak>"}],"bannerInstructions":[{"distanceAlongGeometry":101.0,"primary":{"text":"Ginnheimer Landstraße","components":[{"text":"Ginnheimer Landstraße","type":"text"}],"type":"turn","modifier":"right"}}],"driving_side":"right","weight":23.0,"intersections":[{"location":[8.645709,50.13269],"bearings":[74,194,290],"entry":[false,true,false],"in":0,"out":1},{"location":[8.645648,50.132092],"bearings":[0,169],"entry":[false,true],"in":0,"out":1},{"location":[8.645709,50.131901],"bearings":[25,187,348],"entry":[false,true,false],"in":2,"out":1}]},{"distance":76.0,"duration":14.0,"geometry":"qtxr~AqbunOl@dTLxI?da@","name":"Ginnheimer Landstraße","mode":"driving","maneuver":{"location":[8.645689,50.131802],"bearing_before":187.0,"bearing_after":263.0,"instruction":"右に曲がり、Ginnheimer Landstraßeを進む","type":"continue","modifier":"right"},"voiceInstructions":[{"distanceAlongGeometry":27.1,"announcement":"ginnheimer Landstraße 39, 60487 Frankfurt, Germanyに到着しました。目的地は左側です","ssmlAnnouncement":"<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">ginnheimer Landstraße 39, 60487 Frankfurt, Germanyに到着しました。目的地は左側です</prosody></amazon:effect></speak>"}],"bannerInstructions":[{"distanceAlongGeometry":27.1,"primary":{"text":"Ginnheimer Landstraße 39, 60487 Frankfurt, Germany","components":[{"text":"Ginnheimer Landstraße 39, 60487 Frankfurt, Germany","type":"text"}],"type":"arrive","modifier":"left"}}],"driving_side":"right","weight":14.0,"intersections":[{"location":[8.645689,50.131802],"bearings":[7,121,183,263],"entry":[false,false,true,true],"in":0,"out":3},{"location":[8.645178,50.131771],"bearings":[85,270,355],"entry":[false,true,false],"in":0,"out":1}]},{"distance":0.0,"duration":0.0,"geometry":"urxr~Ak`snO??","name":"Ginnheimer Landstraße","mode":"driving","maneuver":{"location":[8.64463,50.131771],"bearing_before":270.0,"bearing_after":0.0,"instruction":"Ginnheimer Landstraße 39, 60487 Frankfurt, Germanyに到着しました。目的地は左側です","type":"arrive","modifier":"left"},"voiceInstructions":[],"bannerInstructions":[],"driving_side":"right","weight":0.0,"intersections":[{"location":[8.64463,50.131771],"bearings":[90],"entry":[true],"in":0}]}],"annotation":{"distance":[29.2,18.0,5.9,2.9,6.5,7.1,14.3,8.2,31.8,43.4,54.4,32.3,24.2,20.6,11.2,8.4,15.3,29.1,11.4,104.1,66.5,34.1,56.7,5.1,52.9,13.2,19.0,15.2,13.0,7.9,10.2,14.6,11.4,7.6,18.1,17.2,18.8,15.9,21.7,21.0,32.2,55.9,55.9,31.8,23.1,18.8,10.5,9.3,14.4,14.3,21.7,60.6,27.1,47.1,23.3,17.9,21.3,17.9,24.6,30.9,31.3,28.3,22.9,11.3,20.0,32.8,14.0,12.5,16.9,24.7,7.7,8.9,10.2,9.0,12.3,9.0,14.5,8.0,7.6,5.5,4.8,6.3,4.4,6.5,5.1,4.3,3.7,4.8,6.5,5.7,5.5,33.6,16.6,9.0,5.7,7.1,11.1,24.3,12.3,39.1],"duration":[3.504,2.162,0.711,0.343,0.775,0.855,1.713,0.979,3.816,5.205,6.532,3.877,2.9,2.472,1.347,1.005,1.839,3.49,1.362,12.492,7.98,4.091,6.8,0.608,6.346,1.357,1.955,1.565,1.342,0.817,1.049,1.497,1.175,0.784,1.863,1.772,1.939,1.632,2.235,2.157,3.307,5.749,5.753,3.269,2.374,1.929,1.076,0.96,1.484,1.474,2.228,6.232,2.791,4.849,2.395,1.843,2.196,1.843,2.529,3.173,2.819,2.55,2.057,1.021,1.798,2.95,1.258,1.126,1.519,1.817,0.691,0.798,0.916,0.813,1.11,0.806,1.306,0.717,0.687,0.492,0.431,0.563,0.399,0.581,0.459,0.386,0.333,0.436,0.587,0.513,0.497,3.02,1.491,0.806,0.513,0.639,1.002,2.915,1.474,4.696],"congestion":["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"]}}],"routeOptions":{"baseUrl":"https://api-valhalla-here-staging.tilestream.net","user":"mapbox","profile":"driving-traffic","coordinates":[[8.6343942,50.1232172],[8.644264,50.13164]],"alternatives":true,"language":"ja","continue_straight":false,"roundabout_exits":false,"geometries":"polyline6","overview":"full","steps":true,"annotations":"congestion,duration,distance","voice_instructions":true,"banner_instructions":true,"voice_units":"imperial","access_token":"pk.eyJ1IjoiZHJpdmVyYXBwLXN0YWdpbmctanVuZ2xlIiwiYSI6ImNrNWhzb2pyNjByYWkzbW93MjlraTg2dTMifQ.Hh5f4fOuRSgEazHlgEKxVg","uuid":"PrimaryRoute:ck66m8g9x000b11p6d09dbhn5","waypoint_names":";Ginnheimer Landstraße 39, 60487 Frankfurt, Germany","waypoint_targets":";8.644264,50.13164"},"voiceLocale":"ja-JP"},"event_timestamp":1580744133.141894},{"type":"updateLocation","location":{"lat":50.1232179,"lon":8.6343949,"time":1580744133.2,"speed":0.004055141005665064,"bearing":319.7586669921875,"altitude":162.8000030517578,"accuracyHorizontal":14.359999656677246,"provider":"fused"},"event_timestamp":1580744133.200579,"delta_ms":0},{"type":"getStatus","timestamp":1580744134.703,"event_timestamp":1580744133.203165,"delta_ms":0},{"type":"updateLocation","location":{"lat":50.1232179,"lon":8.6343949,"time":1580744133.263,"speed":0.004055141005665064,"bearing":319.7586669921875,"altitude":162.8000030517578,"accuracyHorizontal":14.359999656677246,"provider":"fused"},"event_timestamp":1580744133.263169,"delta_ms":0},{"type":"updateLocation","location":{"lat":50.1232171,"lon":8.6343941,"time":1580744134.061,"speed":0.01645866595208645,"bearing":216.3468780517578,"altitude":162.8000030517578,"accuracyHorizontal":14.633000373840332,"provider":"fused"},"event_timestamp":1580744134.062025,"delta_ms":0},{"type":"getStatus","timestamp":1580744135.731,"event_timestamp":1580744134.23157,"delta_ms":0},{"type":"getStatus","timestamp":1580744136.738,"event_timestamp":1580744135.23874,"delta_ms":0},{"type":"getStatus","timestamp":1580744137.748,"event_timestamp":1580744136.248803,"delta_ms":0},{"type":"getStatus","timestamp":1580744138.764,"event_timestamp":1580744137.264306,"delta_ms":0},{"type":"getStatus","timestamp":1580744139.774,"event_timestamp":1580744138.274999,"delta_ms":0},{"type":"updateLocation","location":{"lat":50.1232173,"lon":8.6343939,"time":1580744139.194,"speed":0.004030901938676834,"bearing":264.70654296875,"altitude":162.8000030517578,"accuracyHorizontal":14.218000411987305,"provider":"fused"},"event_timestamp":1580744139.194152,"delta_ms":0},{"type":"getStatus","timestamp":1580744140.786,"event_timestamp":1580744139.286118,"delta_ms":0},{"type":"getStatus","timestamp":1580744141.812,"event_timestamp":1580744140.312701,"delta_ms":0},{"type":"getStatus","timestamp":1580744142.821,"event_timestamp":1580744141.321435,"delta_ms":0},{"type":"getStatus","timestamp":1580744143.83,"event_timestamp":1580744142.330385,"delta_ms":0},{"type":"getStatus","timestamp":1580744144.843,"event_timestamp":1580744143.343082,"delta_ms":0},{"type":"updateLocation","location":{"lat":50.1232137,"lon":8.6344,"time":1580744144.211,"speed":0.08722998946905136,"bearing":133.47276306152345,"altitude":162.8000030517578,"accuracyHorizontal":14.597000122070313,"provider":"fused"},"event_timestamp":1580744144.211941,"delta_ms":0},{"type":"getStatus","timestamp":1580744145.85,"event_timestamp":1580744144.350621,"delta_ms":0},{"type":"getStatus","timestamp":1580744146.862,"event_timestamp":1580744145.362951,"delta_ms":0},{"type":"getStatus","timestamp":1580744147.871,"event_timestamp":1580744146.371465,"delta_ms":0},{"type":"getStatus","timestamp":1580744148.88,"event_timestamp":1580744147.38087,"delta_ms":0},{"type":"getStatus","timestamp":1580744149.891,"event_timestamp":1580744148.391058,"delta_ms":0},{"type":"updateLocation","location":{"lat":50.1232166,"lon":8.634395,"time":1580744149.291,"speed":0.048854898661375049,"bearing":312.0516662597656,"altitude":162.8000030517578,"accuracyHorizontal":14.652000427246094,"provider":"fused"},"event_timestamp":1580744149.29198,"delta_ms":0},{"type":"getStatus","timestamp":1580744150.903,"event_timestamp":1580744149.40403,"delta_ms":0},{"type":"getStatus","timestamp":1580744151.912,"event_timestamp":1580744150.412873,"delta_ms":0},{"type":"getStatus","timestamp":1580744152.924,"event_timestamp":1580744151.424826,"delta_ms":0},{"type":"getStatus","timestamp":1580744153.931,"event_timestamp":1580744152.431412,"delta_ms":0},{"type":"getStatus","timestamp":1580744154.938,"event_timestamp":1580744153.438255,"delta_ms":0},{"type":"updateLocation","location":{"lat":50.1232091,"lon":8.6343884,"time":1580744154.273,"speed":0.2136557698249817,"bearing":206.76876831054688,"altitude":162.8000030517578,"accuracyHorizontal":14.505000114440918,"provider":"fused"},"event_timestamp":1580744154.273597,"delta_ms":0},{"type":"getStatus","timestamp":1580744155.945,"event_timestamp":1580744154.4453,"delta_ms":0},{"type":"getStatus","timestamp":1580744156.953,"event_timestamp":1580744155.453334,"delta_ms":0},{"type":"getStatus","timestamp":1580744157.962,"event_timestamp":1580744156.462546,"delta_ms":0},{"type":"getStatus","timestamp":1580744158.97,"event_timestamp":1580744157.470169,"delta_ms":0},{"type":"getStatus","timestamp":1580744159.98,"event_timestamp":1580744158.480633,"delta_ms":0},{"type":"updateLocation","location":{"lat":50.123231,"lon":8.634405,"time":1580744159.263,"speed":0.37037393450737,"bearing":25.82938003540039,"altitude":162.8000030517578,"accuracyHorizontal":14.286999702453614,"provider":"fused"},"event_timestamp":1580744159.263185,"delta_ms":0},{"type":"getStatus","timestamp":1580744160.989,"event_timestamp":1580744159.489338,"delta_ms":0},{"type":"getStatus","timestamp":1580744161.999,"event_timestamp":1580744160.500022,"delta_ms":0},{"type":"getStatus","timestamp":1580744163.012,"event_timestamp":1580744161.512278,"delta_ms":0},{"type":"getStatus","timestamp":1580744164.022,"event_timestamp":1580744162.522446,"delta_ms":0},{"type":"getStatus","timestamp":1580744165.029,"event_timestamp":1580744163.529872,"delta_ms":0},{"type":"getStatus","timestamp":1580744166.039,"event_timestamp":1580744164.539721,"delta_ms":0},{"type":"getStatus","timestamp":1580744167.047,"event_timestamp":1580744165.547521,"delta_ms":0},{"type":"getStatus","timestamp":1580744168.058,"event_timestamp":1580744166.558098,"delta_ms":0},{"type":"getStatus","timestamp":1580744169.066,"event_timestamp":1580744167.56616,"delta_ms":0},{"type":"getStatus","timestamp":1580744170.077,"event_timestamp":1580744168.577974,"delta_ms":0},{"type":"getStatus","timestamp":1580744171.085,"event_timestamp":1580744169.585209,"delta_ms":0},{"type":"getStatus","timestamp":1580744172.095,"event_timestamp":1580744170.59549,"delta_ms":0},{"type":"getStatus","timestamp":1580744173.102,"event_timestamp":1580744171.602618,"delta_ms":0},{"type":"getStatus","timestamp":1580744174.115,"event_timestamp":1580744172.615054,"delta_ms":0},{"type":"updateLocation","location":{"lat":50.1232252,"lon":8.6344,"time":1580744173.432,"speed":0.007393053267151117,"bearing":207.9702606201172,"altitude":162.8000030517578,"accuracyHorizontal":14.281999588012696,"provider":"fused"},"event_timestamp":1580744173.432368,"delta_ms":0},{"type":"getStatus","timestamp":1580744175.124,"event_timestamp":1580744173.624903,"delta_ms":0},{"type":"getStatus","timestamp":1580744176.135,"event_timestamp":1580744174.635315,"delta_ms":0},{"type":"getStatus","timestamp":1580744177.145,"event_timestamp":1580744175.645513,"delta_ms":0},{"type":"getStatus","timestamp":1580744178.152,"event_timestamp":1580744176.652443,"delta_ms":0},{"type":"getStatus","timestamp":1580744179.16,"event_timestamp":1580744177.660984,"delta_ms":0},{"type":"getStatus","timestamp":1580744180.17,"event_timestamp":1580744178.67033,"delta_ms":0},{"type":"updateLocation","location":{"lat":50.1232177,"lon":8.6343941,"time":1580744179.409,"speed":0.0,"bearing":0.0,"altitude":162.8000030517578,"accuracyHorizontal":14.432000160217286,"provider":"fused"},"event_timestamp":1580744179.410086,"delta_ms":0},{"type":"getStatus","timestamp":1580744181.184,"event_timestamp":1580744179.684703,"delta_ms":0},{"type":"getStatus","timestamp":1580744182.194,"event_timestamp":1580744180.694649,"delta_ms":0},{"type":"getStatus","timestamp":1580744183.208,"event_timestamp":1580744181.708158,"delta_ms":0},{"type":"getStatus","timestamp":1580744184.219,"event_timestamp":1580744182.719912,"delta_ms":0},{"type":"getStatus","timestamp":1580744185.228,"event_timestamp":1580744183.72902,"delta_ms":0},{"type":"updateLocation","location":{"lat":50.123217,"lon":8.6343939,"time":1580744184.357,"speed":0.0004574917838908732,"bearing":190.3804931640625,"altitude":162.8000030517578,"accuracyHorizontal":14.642999649047852,"provider":"fused"},"event_timestamp":1580744184.357134,"delta_ms":0},{"type":"getStatus","timestamp":1580744186.24,"event_timestamp":1580744184.740624,"delta_ms":0},{"type":"getStatus","timestamp":1580744187.25,"event_timestamp":1580744185.750209,"delta_ms":0},{"type":"getStatus","timestamp":1580744188.258,"event_timestamp":1580744186.758639,"delta_ms":0},{"type":"getStatus","timestamp":1580744189.27,"event_timestamp":1580744187.770137,"delta_ms":0},{"type":"getStatus","timestamp":1580744190.278,"event_timestamp":1580744188.778466,"delta_ms":0},{"type":"updateLocation","location":{"lat":50.1232169,"lon":8.6343928,"time":1580744189.429,"speed":0.013680120930075646,"bearing":262.2109680175781,"altitude":162.8000030517578,"accuracyHorizontal":15.142999649047852,"provider":"fused"},"event_timestamp":1580744189.429813,"delta_ms":0},{"type":"getStatus","timestamp":1580744191.286,"event_timestamp":1580744189.786978,"delta_ms":0},{"type":"getStatus","timestamp":1580744192.293,"event_timestamp":1580744190.793585,"delta_ms":0},{"type":"getStatus","timestamp":1580744193.302,"event_timestamp":1580744191.80243,"delta_ms":0},{"type":"getStatus","timestamp":1580744194.315,"event_timestamp":1580744192.815284,"delta_ms":0},{"type":"getStatus","timestamp":1580744195.328,"event_timestamp":1580744193.828991,"delta_ms":0},{"type":"updateLocation","location":{"lat":50.1232173,"lon":8.6343934,"time":1580744194.462,"speed":0.0062745544128119949,"bearing":32.540714263916019,"altitude":162.8000030517578,"accuracyHorizontal":14.911999702453614,"provider":"fused"},"event_timestamp":1580744194.462407,"delta_ms":0},{"type":"getStatus","timestamp":1580744196.338,"event_timestamp":1580744194.839014,"delta_ms":0},{"type":"getStatus","timestamp":1580744197.35,"event_timestamp":1580744195.850063,"delta_ms":0},{"type":"getStatus","timestamp":1580744198.362,"event_timestamp":1580744196.862629,"delta_ms":0},{"type":"getStatus","timestamp":1580744199.371,"event_timestamp":1580744197.871408,"delta_ms":0},{"type":"getStatus","timestamp":1580744200.379,"event_timestamp":1580744198.879556,"delta_ms":0},{"type":"updateLocation","location":{"lat":50.1232182,"lon":8.6343946,"time":1580744199.406,"speed":0.02246818132698536,"bearing":33.55318069458008,"altitude":162.8000030517578,"accuracyHorizontal":14.710000038146973,"provider":"fused"},"event_timestamp":1580744199.407049,"delta_ms":0},{"type":"getStatus","timestamp":1580744201.388,"event_timestamp":1580744199.888742,"delta_ms":0},{"type":"getStatus","timestamp":1580744202.401,"event_timestamp":1580744200.901613,"delta_ms":0},{"type":"getStatus","timestamp":1580744203.412,"event_timestamp":1580744201.912227,"delta_ms":0},{"type":"getStatus","timestamp":1580744204.42,"event_timestamp":1580744202.920289,"delta_ms":0},{"type":"getStatus","timestamp":1580744205.434,"event_timestamp":1580744203.934623,"delta_ms":0},{"type":"getStatus","timestamp":1580744206.446,"event_timestamp":1580744204.946177,"delta_ms":0},{"type":"getStatus","timestamp":1580744207.453,"event_timestamp":1580744205.953707,"delta_ms":0},{"type":"getStatus","timestamp":1580744208.466,"event_timestamp":1580744206.966619,"delta_ms":0},{"type":"getStatus","timestamp":1580744209.473,"event_timestamp":1580744207.973712,"delta_ms":0},{"type":"getStatus","timestamp":1580744210.482,"event_timestamp":1580744208.982253,"delta_ms":0},{"type":"getStatus","timestamp":1580744211.488,"event_timestamp":1580744209.988828,"delta_ms":0},{"type":"getStatus","timestamp":1580744212.497,"event_timestamp":1580744210.997824,"delta_ms":0},{"type":"getStatus","timestamp":1580744213.506,"event_timestamp":1580744212.006626,"delta_ms":0},{"type":"end_transit","properties":1580744212.223,"event_timestamp":1580744212.223644}],"version":"6.2.1","history_version":"1.0.0"}
""".trimIndent()

private class ReplayCustomEventMapper : CustomEventMapper {
    override fun invoke(eventType: String, event: LinkedTreeMap<*, *>): ReplayEventBase? {
        return when (eventType) {
            "start_transit" -> ReplayEventStartTransit(
                eventTimestamp = event["event_timestamp"] as Double,
                properties = event["properties"] as Double)
            "initial_route" -> {
                val properties = event["properties"] as LinkedTreeMap<*, *>
                val routeOptions = properties["routeOptions"] as LinkedTreeMap<*, *>
                val coordinates = routeOptions["coordinates"] as List<List<Double>>
                val coordinatesLatLng = coordinates.map { LatLng(it[1], it[0]) }
                ReplayEventInitialRoute(
                    eventTimestamp = event["event_timestamp"] as Double,
                    coordinates = coordinatesLatLng
                )
            }
            else -> null
        }
    }
}

private data class ReplayEventStartTransit(
    override val eventTimestamp: Double,
    val properties: Double
) : ReplayEventBase

private data class ReplayEventInitialRoute(
    override val eventTimestamp: Double,
    val coordinates: List<LatLng>
) : ReplayEventBase
