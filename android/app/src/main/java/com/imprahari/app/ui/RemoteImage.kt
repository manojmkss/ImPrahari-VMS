package com.imprahari.app.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.imprahari.app.net.ApiClient
import com.imprahari.app.net.AuthExpiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recently shown NVR pictures, so scrolling back doesn't refetch them. */
private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** Decode a JPEG no wider than about [maxWidth] px (saves memory in lists). */
fun decodeScaled(bytes: ByteArray, maxWidth: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

/**
 * An NVR image ("/api/...") fetched with the session, or null until loaded
 * (or when there is none). [version] refetches it (e.g. a camera snapshot
 * refreshed every few seconds); the cache key includes it.
 */
@Composable
fun rememberRemoteImage(api: ApiClient, path: String?, version: Any? = null, maxWidth: Int = 720): ImageBitmap? {
    val key = path?.let { "$it#$version#$maxWidth" }
    var image by remember(path) { mutableStateOf(key?.let { cache.get(it) }) }
    LaunchedEffect(key) {
        val p = path ?: return@LaunchedEffect
        val k = key ?: return@LaunchedEffect
        cache.get(k)?.let { image = it; return@LaunchedEffect }
        val bytes = try {
            api.imageBytes(p)
        } catch (e: AuthExpiredException) {
            null
        } ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) { decodeScaled(bytes, maxWidth) } ?: return@LaunchedEffect
        cache.put(k, decoded)
        image = decoded
    }
    return image
}
