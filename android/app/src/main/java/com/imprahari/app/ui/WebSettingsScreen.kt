package com.imprahari.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.imprahari.app.data.SessionStore
import com.imprahari.app.net.ApiClient
import com.imprahari.app.net.AuthExpiredException
import com.imprahari.app.net.PinnedTrust
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The NVR's own web settings, inside the app, already signed in: the app's
 * session is handed to the page (the same browser-storage keys the web app
 * reads) before it loads. Only the NVR's own pages open here; other links go
 * to the browser. A self-signed NVR certificate is accepted only if it's the
 * one the user trusted when connecting the app.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSettingsScreen(api: ApiClient, store: SessionStore, onBack: () -> Unit, onSignedOut: () -> Unit) {
    val context = LocalContext.current
    val base = remember { store.serverUrl.orEmpty().trimEnd('/') }
    val origin = remember(base) { Uri.parse(base).let { "${it.scheme}://${it.authority}" } }
    var tokens by remember { mutableStateOf<Pair<String, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        tokens = try {
            api.accessToken() to (store.refreshToken ?: throw AuthExpiredException())
        } catch (e: AuthExpiredException) {
            onSignedOut()
            null
        } catch (e: IOException) {
            error = e.message ?: "Can't reach the NVR"
            null
        }
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Web settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val t = tokens
            when {
                error != null -> Text(
                    error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                t == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webViewClient = object : WebViewClient() {
                                var handedOver = false

                                override fun onPageFinished(view: WebView, url: String) {
                                    loading = false
                                    if (!handedOver && url.startsWith("$origin/api/health")) {
                                        // A plain page of the NVR's own origin: storage there is the web
                                        // app's storage. Put the session in, then open the settings.
                                        handedOver = true
                                        val js = "localStorage.setItem('imprahari_access_token', ${JSONObject.quote(t.first)});" +
                                            "localStorage.setItem('imprahari_refresh_token', ${JSONObject.quote(t.second)});"
                                        view.evaluateJavascript(js) { view.loadUrl("$base/settings") }
                                    }
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url
                                    if ("${url.scheme}://${url.authority}" == origin) return false
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, url))
                                    } catch (e: android.content.ActivityNotFoundException) {
                                        // nothing to open it with
                                    }
                                    return true
                                }

                                @SuppressLint("WebViewClientOnReceivedSslError")
                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                    // Proceed only for the exact certificate the user trusted (pinned).
                                    val pin = store.certPinSha256
                                    val der = derOf(error.certificate)
                                    if (pin != null && der != null && pin.equals(PinnedTrust.sha256Hex(der), ignoreCase = true)) {
                                        handler.proceed()
                                    } else {
                                        handler.cancel()
                                    }
                                }
                            }
                            loadUrl("$base/api/health")
                            webView = this
                        }
                    },
                )
            }
            if (loading && tokens != null) {
                Column(modifier = Modifier.align(Alignment.Center)) { CircularProgressIndicator() }
            }
        }
    }
}

/** The certificate's DER bytes (Android 10+ hands it over directly). */
private fun derOf(cert: SslCertificate?): ByteArray? {
    cert ?: return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return cert.x509Certificate?.encoded
    val bytes = SslCertificate.saveState(cert)?.getByteArray("x509-certificate") ?: return null
    return try {
        (CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate).encoded
    } catch (e: java.security.cert.CertificateException) {
        null
    }
}
