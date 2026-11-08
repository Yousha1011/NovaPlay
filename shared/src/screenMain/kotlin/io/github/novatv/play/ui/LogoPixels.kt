package io.github.novatv.play.ui

import coil3.toBitmap

actual fun logoPixels(image: coil3.Image, size: Int): IntArray? {
    val bmp = image.toBitmap()
    if (bmp.width <= 0 || bmp.height <= 0) return null
    // A grid of samples across the logo is enough to tell its main colour.
    return IntArray(size * size) { i ->
        bmp.getColor((i % size) * bmp.width / size, (i / size) * bmp.height / size)
    }
}
