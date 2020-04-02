package com.mapbox.navigation.examples.core

import android.annotation.SuppressLint
import android.location.Location
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineCallback
import com.mapbox.android.core.location.LocationEngineProvider
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.android.core.location.LocationEngineResult
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.directions.session.RoutesRequestCallback
import com.mapbox.navigation.core.location.ReplayRouteLocationEngine
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.examples.R
import com.mapbox.navigation.examples.utils.Utils
import com.mapbox.navigation.ui.map.NavigationMapboxMap
import com.mapbox.navigation.ui.map.NavigationMapboxMapInstanceState
import kotlinx.android.synthetic.main.activity_trip_service.mapView
import kotlinx.android.synthetic.main.free_drive_navigation_layout.*
import timber.log.Timber

/**
 * To ensure proper functioning of this example make sure your Location is turned on.
 */
class FreeDriveNavigationActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        const val MAP_INSTANCE_STATE_KEY = "navgation_mapbox_map_instance_state"
        const val DEFAULT_INTERVAL_IN_MILLISECONDS = 1000L
        const val DEFAULT_MAX_WAIT_TIME = DEFAULT_INTERVAL_IN_MILLISECONDS * 5
    }

    private var mapboxNavigation: MapboxNavigation? = null
    private var navigationMapboxMap: NavigationMapboxMap? = null
    private var mapInstanceState: NavigationMapboxMapInstanceState? = null

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.free_drive_navigation_layout)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        val mapboxNavigationOptions = MapboxNavigation.defaultNavigationOptions(
                this,
                Utils.getMapboxAccessToken(this)
        )

        mapboxNavigation = MapboxNavigation(
                applicationContext,
                Utils.getMapboxAccessToken(this),
                mapboxNavigationOptions,
                locationEngine = getLocationEngine()
        ).also {
            it.registerRouteProgressObserver(routeProgressObserver)
        }

        initListeners()
    }

    override fun onMapReady(mapboxMap: MapboxMap) {
        mapboxMap.setStyle(Style.MAPBOX_STREETS) { style ->
            navigationMapboxMap = NavigationMapboxMap(mapView, mapboxMap)
            mapboxMap.moveCamera(CameraUpdateFactory.zoomTo(15.0))
            navigationMapboxMap?.initializeLocationComponent()
            mapInstanceState?.let { state ->
                navigationMapboxMap?.restoreFrom(state)
            }
            initLocationEngine()
        }
    }

    fun initLocationEngine() {
        val requestLocationUpdateRequest =
                LocationEngineRequest.Builder(DEFAULT_INTERVAL_IN_MILLISECONDS)
                        .setPriority(LocationEngineRequest.PRIORITY_NO_POWER)
                        .setMaxWaitTime(DEFAULT_MAX_WAIT_TIME)
                        .build()

        mapboxNavigation?.locationEngine?.requestLocationUpdates(
                requestLocationUpdateRequest,
                locationListenerCallback,
                mainLooper
        )
        mapboxNavigation?.locationEngine?.getLastLocation(locationListenerCallback)
    }

    private val routesReqCallback = object : RoutesRequestCallback {
        override fun onRoutesReady(routes: List<DirectionsRoute>) {
            if (routes.isNotEmpty()) {
                navigationMapboxMap?.drawRoute(routes[0])
                if (shouldSimulateRoute()) {
                    (mapboxNavigation?.locationEngine as ReplayRouteLocationEngine).assign(routes[0])
                }
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

    @SuppressLint("MissingPermission")
    fun initListeners() {
        startNavigation.setOnClickListener {
            navigationMapboxMap?.enterNavigationMode()
            navigationMapboxMap?.addProgressChangeListener(mapboxNavigation!!)
            if (mapboxNavigation?.getRoutes()?.isNotEmpty() == true) {
                navigationMapboxMap?.startCamera(mapboxNavigation?.getRoutes()!![0])
            }
            mapboxNavigation?.startTripSession()
            startNavigation.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        mapboxNavigation?.registerLocationObserver(locationObserver)
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
        mapboxNavigation?.unregisterRouteProgressObserver(routeProgressObserver)
        stopLocationUpdates()
        mapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapboxNavigation?.stopTripSession()
        mapboxNavigation?.onDestroy()
        mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        navigationMapboxMap?.saveStateWith(MAP_INSTANCE_STATE_KEY, outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        super.onRestoreInstanceState(savedInstanceState)
        mapInstanceState = savedInstanceState?.getParcelable(MAP_INSTANCE_STATE_KEY)
    }

    private val locationListenerCallback: LocationEngineCallback<LocationEngineResult> =
            object : LocationEngineCallback<LocationEngineResult> {
                override fun onSuccess(result: LocationEngineResult) {
                    // todo
                }

                override fun onFailure(exception: Exception) {
                    Timber.i(exception)
                }
            }

    private fun stopLocationUpdates() {
        mapboxNavigation?.locationEngine?.removeLocationUpdates(locationListenerCallback)
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
                navigationMapboxMap?.updateLocation(keyPoints)
            } else {
                navigationMapboxMap?.updateLocation(enhancedLocation)
            }
        }
    }

    private val routeProgressObserver = object : RouteProgressObserver {
        override fun onRouteProgressChanged(routeProgress: RouteProgress) {
            // do something with the route progress
            Timber.i("route progress: ${routeProgress.currentState()}")
        }
    }

    // Used to determine if the ReplayRouteLocationEngine should be used to simulate the routing.
    // This is used for testing purposes.
    private fun shouldSimulateRoute(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(this.applicationContext)
                .getBoolean(this.getString(R.string.simulate_route_key), false)
    }

    // If shouldSimulateRoute is true a ReplayRouteLocationEngine will be used which is intended
    // for testing else a real location engine is used.
    private fun getLocationEngine(): LocationEngine {
        return if (shouldSimulateRoute()) {
            ReplayRouteLocationEngine()
        } else {
            LocationEngineProvider.getBestLocationEngine(this)
        }
    }
}
