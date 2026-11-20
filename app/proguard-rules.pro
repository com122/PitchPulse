# Keep the JavaScript bridge used by the Leaflet calibration WebView.
-keepclassmembers class com.pitchpulse.app.ui.pitches.MapJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
