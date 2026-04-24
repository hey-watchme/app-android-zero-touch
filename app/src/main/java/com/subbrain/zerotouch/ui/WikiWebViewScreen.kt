package com.subbrain.zerotouch.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val WIKI_BASE_URL = "https://app-web-zero-touch.vercel.app/wiki"

private fun buildWikiUrl(deviceId: String?): String {
    val builder = Uri.parse(WIKI_BASE_URL)
        .buildUpon()
        .appendQueryParameter("layout", "split")
    if (!deviceId.isNullOrBlank()) {
        builder.appendQueryParameter("device_id", deviceId)
    }
    return builder.build().toString()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WikiWebViewScreen(
    modifier: Modifier = Modifier,
    deviceId: String? = null
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            val initialUrl = buildWikiUrl(deviceId)
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                loadUrl(initialUrl)
            }
        },
        update = { view ->
            val targetUrl = buildWikiUrl(deviceId)
            if (view.url != targetUrl) {
                view.loadUrl(targetUrl)
            }
        },
    )
}
