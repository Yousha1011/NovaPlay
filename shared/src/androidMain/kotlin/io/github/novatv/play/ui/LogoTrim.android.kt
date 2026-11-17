package io.github.novatv.play.ui

import android.graphics.Bitmap

actual fun trimTransparent(bmp: Bitmap): Bitmap {
    if (!bmp.hasAlpha()) return bmp
    val w = bmp.width; val h = bmp.height
    if (w <= 2 || h <= 2) return bmp
    val src = if (android.os.Build.VERSION.SDK_INT >= 26 && bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return bmp else bmp
    val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
    val b = opaqueBounds(w, h) { x, y -> px[y * w + x] ushr 24 } ?: return bmp
    if (b[0] == 0 && b[1] == 0 && b[2] == w && b[3] == h) return bmp
    return Bitmap.createBitmap(src, b[0], b[1], b[2] - b[0], b[3] - b[1])
}
