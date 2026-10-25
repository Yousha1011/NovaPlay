package io.github.novatv.play.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.Path
import java.text.Normalizer
import java.util.zip.GZIPInputStream

actual fun nowMs(): Long = System.currentTimeMillis()

actual val IO: CoroutineDispatcher get() = Dispatchers.IO

private val marks = Regex("\\p{Mn}+")
actual fun foldAccents(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(marks, "")

actual fun gunzip(src: Path, dst: Path) {
    GZIPInputStream(src.toFile().inputStream().buffered(64 * 1024), 64 * 1024).use { i ->
        dst.toFile().outputStream().buffered(64 * 1024).use { o -> i.copyTo(o, 64 * 1024) }
    }
}

internal fun ensure(p: Path): Path = p.also { FileSystem.SYSTEM.createDirectories(it) }
