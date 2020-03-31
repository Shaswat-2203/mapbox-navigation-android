package com.mapbox.navigation.core.directions.session

import android.util.Log.d
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.navigation.base.extensions.ifNonNull
import com.mapbox.navigation.base.logger.model.Message
import com.mapbox.navigation.base.logger.model.Tag
import com.mapbox.navigation.base.route.RouteRefreshCallback
import com.mapbox.navigation.base.route.Router
import com.mapbox.navigation.logger.MapboxLogger
import java.util.concurrent.CopyOnWriteArrayList

// todo make internal
class MapboxDirectionsSession(
    private val router: Router
) : DirectionsSession {

    private val TAG = "MAPBOX_DIRECTIONSESSION"
    private val routesObservers = CopyOnWriteArrayList<RoutesObserver>()
    private var routeOptions: RouteOptions? = null

    override var routes: List<DirectionsRoute> = emptyList()
        set(value) {
            router.cancel()
            if (routes.isEmpty() && value.isEmpty()) {
                return
            }
            field = value
            if (routes.isNotEmpty()) {
                this.routeOptions = routes[0].routeOptions()
            }
            routesObservers.forEach { it.onRoutesChanged(value) }
        }

    override fun getRouteOptions(): RouteOptions? = routeOptions

    override fun cancel() {
        router.cancel()
    }

    override fun requestRouteRefresh(route: DirectionsRoute, legIndex: Int, callback: RouteRefreshCallback) {
        router.getRouteRefresh(route, legIndex, callback)
    }

    override fun requestRoutes(
        routeOptions: RouteOptions,
        routesRequestCallback: RoutesRequestCallback?
    ) {
        router.getRoute(routeOptions, object : Router.Callback {
            override fun onResponse(routes: List<DirectionsRoute>) {
                this@MapboxDirectionsSession.routes = routes
                routesRequestCallback?.onRoutesReady(routes)
                MapboxLogger.d(Tag(TAG), Message("requestRoutes router returned ${routes.size} route(s)"))
            }

            override fun onFailure(throwable: Throwable) {
                routesRequestCallback?.onRoutesRequestFailure(throwable, routeOptions)
                MapboxLogger.d(Tag(TAG), Message("getRoute() call failed with: ${throwable.localizedMessage}"))
            }

            override fun onCanceled() {
                routesRequestCallback?.onRoutesRequestCanceled(routeOptions)
                MapboxLogger.d(Tag(TAG), Message("gerRoute() was canceled"))
            }
        })
    }

    override fun requestFasterRoute(
        adjustedRouteOptions: RouteOptions,
        routesRequestCallback: RoutesRequestCallback
    ) {
        if (routes.isEmpty()) {
            routesRequestCallback.onRoutesRequestCanceled(adjustedRouteOptions)
            return
        }
        router.getRoute(adjustedRouteOptions, object : Router.Callback {
            override fun onResponse(routes: List<DirectionsRoute>) {
                routesRequestCallback.onRoutesReady(routes)
            }

            override fun onFailure(throwable: Throwable) {
                ifNonNull(routeOptions) { options ->
                    routesRequestCallback.onRoutesRequestFailure(throwable, options)
                }
            }

            override fun onCanceled() {
                ifNonNull(routeOptions) { options ->
                    routesRequestCallback.onRoutesRequestCanceled(options)
                }
            }
        })
    }

    override fun registerRoutesObserver(routesObserver: RoutesObserver) {
        routesObservers.add(routesObserver)
        if (routes.isNotEmpty()) {
            routesObserver.onRoutesChanged(routes)
        }
    }

    override fun unregisterRoutesObserver(routesObserver: RoutesObserver) {
        routesObservers.remove(routesObserver)
    }

    override fun unregisterAllRoutesObservers() {
        routesObservers.clear()
    }

    override fun shutDownSession() {
        cancel()
    }
}
