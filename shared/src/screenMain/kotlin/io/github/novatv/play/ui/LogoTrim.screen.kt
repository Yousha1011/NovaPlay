package io.github.novatv.play.ui

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.IRect

actual fun trimTransparent(bmp: Bitmap): Bitmap {
    val w = bmp.width; val h = bmp.height
    if (w <= 2 || h <= 2 || bmp.isOpaque) return bmp
    val b = opaqueBounds(w, h) { x, y -> bmp.getColor(x, y) ushr 24 } ?: return bmp
    if (b[0] == 0 && b[1] == 0 && b[2] == w && b[3] == h) return bmp
    val out = Bitmap()
    return if (bmp.extractSubset(out, IRect.makeLTRB(b[0], b[1], b[2], b[3]))) out else bmp
}
