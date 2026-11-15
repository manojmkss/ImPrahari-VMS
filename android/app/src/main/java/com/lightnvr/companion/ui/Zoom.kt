package com.lightnvr.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Pinch-zoom math (pure, for tests). The content is scaled about its centre
 * and then shifted by `offset` (px); the shift is limited so the scaled
 * content always covers the view - no panning into black.
 */
object ZoomMath {
    const val MAX_SCALE = 8f
    const val DOUBLE_TAP_SCALE = 2.5f

    /** Largest shift allowed at [scale] for a view of [size]. */
    fun maxOffset(scale: Float, size: Size) = Offset((scale - 1f) * size.width / 2f, (scale - 1f) * size.height / 2f)

    fun clamp(offset: Offset, scale: Float, size: Size): Offset {
        val m = maxOffset(scale, size)
        // "+ 0f" turns -0.0 into 0.0 (at 1x the limit is ±0).
        return Offset(offset.x.coerceIn(-m.x, m.x) + 0f, offset.y.coerceIn(-m.y, m.y) + 0f)
    }

    /**
     * One pinch/pan step: scale by [zoom] around [centroid] (px from the
     * view's top-left) and move by [pan]. The point under the fingers stays
     * under them.
     */
    fun transform(scale: Float, offset: Offset, size: Size, centroid: Offset, pan: Offset, zoom: Float): Pair<Float, Offset> {
        val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
        val c = centroid - Offset(size.width / 2f, size.height / 2f)
        val moved = (offset - c) * (newScale / scale) + c + pan
        return newScale to clamp(moved, newScale, size)
    }

    /** Double tap: zoom in on [tap], or back out to the whole picture. */
    fun doubleTap(scale: Float, size: Size, tap: Offset): Pair<Float, Offset> {
        if (scale > 1.01f) return 1f to Offset.Zero
        val c = tap - Offset(size.width / 2f, size.height / 2f)
        return DOUBLE_TAP_SCALE to clamp(-c * (DOUBLE_TAP_SCALE - 1f), DOUBLE_TAP_SCALE, size)
    }

    /** The part of the picture in view, as fractions of it (left, top, width, height) - for the mini-map. */
    fun visible(scale: Float, offset: Offset, size: Size): FloatArray {
        if (size.width <= 0f || size.height <= 0f) return floatArrayOf(0f, 0f, 1f, 1f)
        val w = 1f / scale
        val h = 1f / scale
        val left = 0.5f - w / 2f - offset.x / (scale * size.width)
        val top = 0.5f - h / 2f - offset.y / (scale * size.height)
        return floatArrayOf(left, top, w, h)
    }
}

@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        internal set
    var offset by mutableStateOf(Offset.Zero)
        internal set

    val zoomed: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

@Composable
fun rememberZoomState(): ZoomState = remember { ZoomState() }

/**
 * Pinch to zoom (up to 8×), drag to look around while zoomed, double-tap to
 * zoom in on a spot or back out. Shows the zoom level and a mini-map of the
 * part of the picture in view. [content] is drawn scaled - for video it must
 * be a TextureView-backed player (a SurfaceView can't be scaled).
 */
@Composable
fun ZoomableBox(
    state: ZoomState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val box = Size(size.width.toFloat(), size.height.toFloat())
    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged {
                size = it
                state.offset = ZoomMath.clamp(state.offset, state.scale, Size(it.width.toFloat(), it.height.toFloat()))
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    val (s, o) = ZoomMath.doubleTap(state.scale, Size(this.size.width.toFloat(), this.size.height.toFloat()), tap)
                    state.scale = s
                    state.offset = o
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val area = Size(this.size.width.toFloat(), this.size.height.toFloat())
                    // At 1x a one-finger drag has nothing to move.
                    if (zoom == 1f && state.scale <= 1.01f) return@detectTransformGestures
                    val (s, o) = ZoomMath.transform(state.scale, state.offset, area, centroid, pan, zoom)
                    state.scale = s
                    state.offset = o
                }
            },
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.offset.x
                    translationY = state.offset.y
                },
            content = content,
        )
        if (state.zoomed && size.width > 0) {
            ZoomBadge(state, box, Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
    }
}

/** "2.4×" and a mini-map: where in the picture you are. */
@Composable
private fun ZoomBadge(state: ZoomState, size: Size, modifier: Modifier) {
    val v = ZoomMath.visible(state.scale, state.offset, size)
    Box(
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(6.dp))
            .padding(5.dp),
    ) {
        Canvas(
            modifier = Modifier
                .width(72.dp)
                .aspectRatio(if (size.height > 0f) size.width / size.height else 16f / 9f),
        ) {
            drawRect(Color(0x55FFFFFF), style = Stroke(width = 1.5f))
            drawRect(
                Color(0xCCFFFFFF),
                topLeft = Offset(v[0] * this.size.width, v[1] * this.size.height),
                size = Size(v[2] * this.size.width, v[3] * this.size.height),
            )
        }
        Text(
            String.format(Locale.US, "%.1f×", state.scale),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
        )
    }
}
