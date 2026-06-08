package com.yvonna.portalhome

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yvonna.portalhome.net.NestSdmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Plays a battery Nest doorbell/camera's WebRTC live stream inside a WebView.
 *
 * Portal has no Google Play Services, so the native libwebrtc stack isn't a good
 * fit. Instead the Chromium WebView does the WebRTC work: webrtc.html creates the
 * SDP offer, this activity relays it to SDM's GenerateWebRtcStream command, then
 * feeds the answer SDP back to the page. The stream is receive-only, so no camera
 * or microphone permission is needed.
 */
class WebRtcDoorbellActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var nest: NestSdmClient
    private lateinit var resourceName: String

    @Volatile
    private var mediaSessionId: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        resourceName = intent.getStringExtra(EXTRA_RESOURCE).orEmpty()
        if (resourceName.isEmpty()) {
            finish()
            return
        }
        nest = NestSdmClient(Config(this))

        webView = WebView(this)
        setContentView(webView)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/webrtc.html")
    }

    /** Bridge methods are invoked by webrtc.html on a background (binder) thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun onOfferReady(offerSdp: String) {
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        nest.generateWebRtcStream(resourceName, offerSdp)
                    }
                }
                result.onSuccess { answer ->
                    mediaSessionId = answer.mediaSessionId
                    // setAnswer(sdp) — quote() escapes the SDP into a JS string literal.
                    webView.evaluateJavascript(
                        "setAnswer(${JSONObject.quote(answer.answerSdp)})", null
                    )
                }.onFailure { e ->
                    showStatus("Error: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            runOnUiThread { showStatus("Error: $message") }
        }

        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("WebRtcDoorbell", message)
        }
    }

    private fun showStatus(text: String) {
        webView.evaluateJavascript("setStatus(${JSONObject.quote(text)})", null)
    }

    override fun onDestroy() {
        // Best-effort release of the media session so the doorbell stops streaming.
        val sid = mediaSessionId
        val name = resourceName
        if (sid.isNotEmpty()) {
            Thread { runCatching { nest.stopWebRtcStream(name, sid) } }.start()
        }
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESOURCE = "resource_name"
    }
}
