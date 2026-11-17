package io.github.novatv.play.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import coil3.Bitmap
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation

/**
 * [bmp] without its see-through edges, or [bmp] itself when it has none (or is all see-through). Logos come with
 * very different empty margins; trimmed, every logo fills its box the same way.
 */
expect fun trimTransparent(bmp: Bitmap): Bitmap

/** The bounds of the pixels in a [w]x[h] image that are not see-through, as left, top, right, bottom (exclusive); null if none. */
internal fun opaqueBounds(w: Int, h: Int, alpha: (Int, Int) -> Int): IntArray? {
    val solid = 24
    fun rowHas(y: Int): Boolean { for (x in 0 until w) if (alpha(x, y) > solid) return true; return false }
    fun colHas(x: Int, top: Int, bottom: Int): Boolean { for (y in top until bottom) if (alpha(x, y) > solid) return true; return false }
    var top = 0; while (top < h && !rowHas(top)) top++
    if (top == h) return null
    var bottom = h; while (bottom > top && !rowHas(bottom - 1)) bottom--
    var left = 0; while (left < w && !colHas(left, top, bottom)) left++
    var right = w; while (right > left && !colHas(right - 1, top, bottom)) right--
    return intArrayOf(left, top, right, bottom)
}

/** Coil step that trims a logo's see-through edges; the result is cached with the image. */
object LogoTrim : Transformation() {
    override val cacheKey = "logo-trim-1"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap = try { trimTransparent(input) } catch (e: Throwable) { input }
}

/**
 * A channel logo alone, trimmed and fitted to [modifier]'s box, so all logos look the same size. The channel's
 * initials stand in, without a plate, only when it has no logo or the logo can't be loaded.
 */
@Composable
fun BareLogo(url: String?, name: String, modifier: Modifier, initialsStyle: TextStyle) {
    val ctx = LocalPlatformContext.current
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank() || failed) Text(initials(name), style = initialsStyle, maxLines = 1)
        else {
            val req = remember(url) { ImageRequest.Builder(ctx).data(url).transformations(LogoTrim).build() }
            AsyncImage(req, name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onError = { failed = true })
        }
    }
}
