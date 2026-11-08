package io.github.novatv.play.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.novatv.play.platform.IO
import io.github.novatv.play.platform.SafeMap
import kotlinx.coroutines.withContext

/** A small grid of a logo's pixels as ARGB ints, read by each platform from the loaded image; null if it can't. */
expect fun logoPixels(image: coil3.Image, size: Int): IntArray?

/**
 * The colour a logo is mostly made of, leaving out see-through, near-white, near-black and grey pixels: the green of
 * Bangladesh's flag, the maroon of the West Indies crest. Null for a logo that is only black, white or grey.
 */
fun dominantTone(px: IntArray): Long? {
    // 12 hue buckets; each counts its pixels weighted by how strongly coloured they are.
    val weight = DoubleArray(12); val r = DoubleArray(12); val g = DoubleArray(12); val b = DoubleArray(12)
    for (p in px) {
        if ((p ushr 24) < 160) continue
        val rr = (p shr 16) and 0xFF; val gg = (p shr 8) and 0xFF; val bb = p and 0xFF
        val max = maxOf(rr, gg, bb); val min = minOf(rr, gg, bb)
        if (max < 40 || min > 215) continue
        val sat = (max - min) / max.toDouble()
        if (sat < 0.25) continue
        val d = (max - min).toDouble()
        var hue = when (max) { rr -> ((gg - bb) / d) % 6; gg -> (bb - rr) / d + 2; else -> (rr - gg) / d + 4 } * 60
        if (hue < 0) hue += 360
        val i = (hue / 30).toInt().coerceIn(0, 11)
        weight[i] += sat; r[i] += rr * sat; g[i] += gg * sat; b[i] += bb * sat
    }
    val best = weight.indices.maxByOrNull { weight[it] } ?: return null
    if (weight[best] < 3.0) return null
    val w = weight[best]
    return 0xFF000000L or ((r[best] / w).toLong() shl 16) or ((g[best] / w).toLong() shl 8) or (b[best] / w).toLong()
}

private val tones = SafeMap<String, Long>()
private const val NONE = 0L

/** [url]'s logo colour once the logo has loaded (kept for the session), or null meanwhile or when it has none. */
@Composable
fun rememberLogoTone(url: String?): Color? {
    val ctx = LocalPlatformContext.current
    val tone by produceState(tones[url ?: ""]?.takeIf { it != NONE }?.let { Color(it) }, url) {
        if (url == null || tones[url] != null) return@produceState
        val result = try { SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(url).build()) } catch (e: Exception) { null }
        val image = (result as? SuccessResult)?.image ?: return@produceState
        val found = withContext(IO) { try { logoPixels(image, 24)?.let(::dominantTone) } catch (e: Exception) { null } }
        tones[url] = found ?: NONE
        value = found?.let { Color(it) }
    }
    return tone
}
