package io.github.novatv.play

import android.content.Context

/** The TV app has no push messages (game alerts are the phone's). */
object Push {
    val token: String? get() = null
    fun start(ctx: Context) {}
}
