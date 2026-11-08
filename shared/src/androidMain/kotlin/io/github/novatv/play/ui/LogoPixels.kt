package io.github.novatv.play.ui

import android.graphics.Bitmap
import coil3.toBitmap

actual fun logoPixels(image: coil3.Image, size: Int): IntArray? {
    var bmp = image.toBitmap()
    // A hardware bitmap can't be read pixel by pixel: copy it to a plain one first.
    if (android.os.Build.VERSION.SDK_INT >= 26 && bmp.config == Bitmap.Config.HARDWARE) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    val small = Bitmap.createScaledBitmap(bmp, size, size, true)
    return IntArray(size * size).also { small.getPixels(it, 0, size, 0, 0, size, size) }
}
