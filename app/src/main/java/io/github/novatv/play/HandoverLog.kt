package io.github.novatv.play

/** One line per step of passing the live player between the preview and full screen (logcat tag NovaHandover). */
object HandoverLog {
    private const val TAG = "NovaHandover"
    fun i(msg: String) { android.util.Log.i(TAG, msg) }
}
