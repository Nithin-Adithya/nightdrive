package com.nithin.nightdrive

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Live Google Maps inside the dashboard card, using the mobile web version so no API key or
 * Play Services is needed, restyled into a bare dark map. The WebView stays hidden (showing the placeholder art) until a page
 * has loaded, and is paused whenever the launcher is not in front.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class MapCard(
    private val activity: Activity,
    private val web: WebView,
    /** "Open Maps" button shown over the placeholder art while the live map is unavailable. */
    private val fallback: View,
    private val openMapsApp: () -> Unit
) {
    private var loaded = false
    private var failed = false
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null

    init {
        val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
        web.setBackgroundColor(activity.getColor(R.color.card))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            setSupportMultipleWindows(false)
        }
        // Let the map pan sideways instead of the pager swiping to the app grid.
        web.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                v.parent.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isGoogle(request.url.scheme, request.url.host)) return false
                // intent:// and other "open in app" links hand over to the real Maps app.
                if (request.isForMainFrame && request.hasGesture()) openMapsApp()
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                failed = false
                restyle(view)
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                restyle(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (failed) return
                restyle(view)
                loaded = true
                web.visibility = View.VISIBLE
                fallback.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                failed = true
                loaded = false
                web.visibility = View.INVISIBLE
                fallback.visibility = View.VISIBLE
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) {
                when {
                    !origin.startsWith("https://") ||
                        !isGoogle("https", origin.removePrefix("https://").substringBefore('/').substringBefore(':')) ->
                        callback.invoke(origin, false, false)
                    hasLocationPermission() -> callback.invoke(origin, true, false)
                    else -> {
                        pendingGeo = origin to callback
                        activity.requestPermissions(LOCATION_PERMISSIONS, REQUEST_LOCATION)
                    }
                }
            }
        }
    }

    /** Dark inverts the map's colours; light leaves Google's normal daytime map. */
    var dark = true
        set(value) {
            field = value
            restyle(web)
        }

    private fun restyle(view: WebView) {
        view.evaluateJavascript(MAP_ONLY_JS, null)
        // setAttribute/removeAttribute, not toggleAttribute: Android 8 head units ship an old WebView.
        val action = if (dark) "setAttribute('data-nd-dark', '')" else "removeAttribute('data-nd-dark')"
        view.evaluateJavascript("document.documentElement.$action;", null)
    }

    private var active = false

    fun resume() {
        active = true
        web.onResume()
        if (!loaded) web.loadUrl(MAPS_URL)
    }

    /** Called when the connection comes back after a failed load. */
    fun retry() {
        if (failed && !loaded) web.loadUrl(MAPS_URL)
    }

    fun pause() {
        active = false
        web.onPause()
    }

    fun destroy() = web.destroy()

    fun onLocationPermissionResult() {
        val (origin, callback) = pendingGeo ?: return
        pendingGeo = null
        callback.invoke(origin, hasLocationPermission(), false)
    }

    private fun hasLocationPermission() = LOCATION_PERMISSIONS.any {
        activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isGoogle(scheme: String?, host: String?): Boolean {
        if (scheme != "https" || host == null) return false
        return host.startsWith("google.") || host.contains(".google.")
    }

    companion object {
        const val REQUEST_LOCATION = 1
        private const val MAPS_URL = "https://www.google.com/maps/@?api=1&map_action=map"

        /**
         * Restyles Google's page into a bare dark map. Everything in the page's app container is
         * hidden except the map itself, the "my location" button and the Google logo, which
         * removes the "Open app" banner, the search bar and the bottom sheet. The map is then
         * allowed to fill the space they leave. Dark mode is a separate switch (data-nd-dark).
         *
         * The page builds itself after load, so the marking pass repeats for a while. It leans
         * on Google's current markup; if that changes and the map can't be found, nothing is
         * hidden and the page shows as normal.
         */
        private val MAP_ONLY_JS = """
            (function () {
              // Always ask for a GPS position. Without this the page asks for a coarse one, which
              // comes from Google's network location service and never arrives on units without it.
              var geo = window.Geolocation && Geolocation.prototype;
              if (geo && !geo.ndPatched) {
                geo.ndPatched = true;
                var precise = function (options) {
                  return Object.assign({}, options, { enableHighAccuracy: true });
                };
                var getOnce = geo.getCurrentPosition;
                geo.getCurrentPosition = function (ok, fail, options) {
                  return getOnce.call(this, ok, fail, precise(options));
                };

                // GPS reports about once a second, and the page slides to each fix and then sits
                // still, so the map follows a moving car in hops. Feed it in-between positions
                // five times a second instead, each a little further along the line from the
                // previous fix to the newest one, so it glides. This shows the car up to one fix
                // (about a second) behind. Nothing extra runs while the car is standing still.
                var watch = geo.watchPosition;
                var clear = geo.clearWatch;
                var gliders = {};
                geo.watchPosition = function (ok, fail, options) {
                  var from = null, to = null, shown = null, started = 0, span = 1000, lastFix = 0;
                  var timer = null;
                  var emit = function (lat, lng) {
                    shown = { lat: lat, lng: lng };
                    var c = to.coords;
                    ok({
                      coords: {
                        latitude: lat, longitude: lng, accuracy: c.accuracy, altitude: c.altitude,
                        altitudeAccuracy: c.altitudeAccuracy, heading: c.heading, speed: c.speed
                      },
                      timestamp: Date.now()
                    });
                  };
                  var step = function () {
                    var f = Math.min(1, (Date.now() - started) / span);
                    var c = to.coords;
                    if (!document.hidden) {
                      emit(from.lat + (c.latitude - from.lat) * f, from.lng + (c.longitude - from.lng) * f);
                    }
                    if (f >= 1) {
                      clearInterval(timer);
                      timer = null;
                    }
                  };
                  var id = watch.call(this, function (fix) {
                    var now = Date.now();
                    var c = fix.coords;
                    var jump = shown === null ? 1 :
                      Math.abs(c.latitude - shown.lat) + Math.abs(c.longitude - shown.lng);
                    to = fix;
                    // First fix, a standstill, or a leap (tunnel exit, GPS correction): show it as is.
                    if (shown === null || jump < 0.000005 || jump > 0.003) {
                      if (timer) clearInterval(timer);
                      timer = null;
                      emit(c.latitude, c.longitude);
                    } else {
                      from = shown;
                      span = Math.max(400, Math.min(2000, now - lastFix));
                      started = now;
                      if (!timer) timer = setInterval(step, 200);
                    }
                    lastFix = now;
                  }, fail, precise(options));
                  gliders[id] = function () { if (timer) clearInterval(timer); };
                  return id;
                };
                geo.clearWatch = function (id) {
                  if (gliders[id]) {
                    gliders[id]();
                    delete gliders[id];
                  }
                  return clear.call(this, id);
                };
              }
              if (!document.head) return;
              if (!document.getElementById('nd-style')) {
                var style = document.createElement('style');
                style.id = 'nd-style';
                style.textContent =
                  'html[data-nd-dark]{filter:invert(0.92) hue-rotate(180deg) saturate(0.8) brightness(0.95)!important;background:#000!important}' +
                  'html[data-nd-ready] .ml-persistent-promo-banner{display:none!important}' +
                  'html[data-nd-ready] #app{top:0!important}' +
                  'html[data-nd-ready] #app>div:not([data-nd-keep]){display:none!important}' +
                  // The page shrinks and shifts the map to make room for the headers we hide.
                  'html[data-nd-ready] #app>div[style*="max-height"]{max-height:none!important;transform:none!important}' +
                  'html[data-nd-ready] .ml-branding-icon-google-logo-on-map{transform:none!important}' +
                  'html[data-nd-ready] .ml-my-location-fab button{transform:none!important;position:fixed!important;' +
                  'right:16px!important;bottom:16px!important;top:auto!important;left:auto!important}';
                document.head.appendChild(style);
              }
              function mark() {
                var app = document.getElementById('app');
                if (!app) return;
                var foundMap = false;
                for (var i = 0; i < app.children.length; i++) {
                  var c = app.children[i];
                  var isMap = c.style.position === 'absolute' && c.style.height === '100%';
                  if (isMap) foundMap = true;
                  if (isMap || c.classList.contains('ml-my-location-fab') ||
                      c.querySelector('.ml-branding-icon-google-logo-on-map')) {
                    c.setAttribute('data-nd-keep', '');
                  }
                }
                if (foundMap && !document.documentElement.hasAttribute('data-nd-ready')) {
                  document.documentElement.setAttribute('data-nd-ready', '');
                  window.dispatchEvent(new Event('resize'));
                }
                // Centre and zoom in on the car once per page load, then keep following it. The
                // first time, this is what brings up the location permission prompt.
                var locate = document.querySelector('.ml-my-location-fab button');
                if (foundMap && locate && !window.ndLocated) {
                  window.ndLocated = true;
                  setTimeout(function () { locate.click(); }, 1500);
                }
              }
              mark();
              if (!window.ndMarkTimer) {
                var runs = 0;
                window.ndMarkTimer = setInterval(function () {
                  mark();
                  if (++runs >= 30) clearInterval(window.ndMarkTimer);
                }, 1000);
              }
            })();
        """.trimIndent()

        private val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
