package com.pitchpulse.app.ui.pitches

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader

/** Callbacks from the Leaflet page. Invoked on the JavaBridge thread, re-posted to main. */
class MapJsBridge(
    private val onReady: () -> Unit,
    private val onClick: (Double, Double, String) -> Unit,
    private val onTiles: (Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onReady() { main.post { onReady.invoke() } }

    @JavascriptInterface
    fun onMapClick(lat: Double, lng: Double, layer: String) { main.post { onClick(lat, lng, layer) } }

    @JavascriptInterface
    fun onTileStatus(ok: Boolean, @Suppress("UNUSED_PARAMETER") errors: Int) { main.post { onTiles(ok) } }
}

/**
 * Leaflet map bundled in assets (served via WebViewAssetLoader from an https origin so tile
 * servers receive a proper Referer). Tiles come from Esri / OSM / AMap XYZ endpoints: no API key.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapWebView(
    modifier: Modifier,
    layerId: String,
    centerLat: Double,
    centerLng: Double,
    overlayJson: String,
    /** Each increment zooms the map to the current rectangle. */
    fitRequest: Int,
    onMapClick: (Double, Double, String) -> Unit,
    onTileStatus: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // Tied to this WebView instance: a recreated WebView must wait for its own page load.
    var ready by remember { mutableStateOf(false) }
    val clickCb = rememberUpdatedState(onMapClick)
    val tilesCb = rememberUpdatedState(onTileStatus)

    val webView = remember {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
            }
            addJavascriptInterface(
                MapJsBridge(
                    onReady = { ready = true },
                    onClick = { lat, lng, layer -> clickCb.value(lat, lng, layer) },
                    onTiles = { tilesCb.value(it) },
                ),
                "Android",
            )
            loadUrl("https://appassets.androidplatform.net/assets/map/index.html")
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.removeJavascriptInterface("Android")
            webView.destroy()
        }
    }

    // Initial centre once the page is ready; layer changes re-centre in the new datum.
    LaunchedEffect(ready, layerId) {
        if (!ready) return@LaunchedEffect
        webView.evaluateJavascript("PP.setLayer('$layerId');", null)
        webView.evaluateJavascript("PP.setView($centerLat, $centerLng, 18);", null)
    }

    LaunchedEffect(ready, overlayJson) {
        if (!ready) return@LaunchedEffect
        webView.evaluateJavascript("PP.setOverlay($overlayJson);", null)
    }

    LaunchedEffect(ready, fitRequest) {
        if (!ready || fitRequest == 0) return@LaunchedEffect
        webView.evaluateJavascript("PP.setOverlay($overlayJson); PP.fit();", null)
    }

    AndroidView(factory = { webView }, modifier = modifier)
}
