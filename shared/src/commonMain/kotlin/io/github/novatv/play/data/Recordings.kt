package io.github.novatv.play.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.github.novatv.play.App
import io.github.novatv.play.json.JSONArray
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.nowMs
import io.github.novatv.play.platform.locked

/** A recording, scheduled, running or finished. Times are the programme's, before the platform adds its padding. */
data class Recording(
    val id: String,
    val channelKey: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
    /** The stream address worked out when it was scheduled (used if the playlist isn't loaded when it starts). */
    val url: String,
    val status: Status = Status.SCHEDULED,
    val file: String? = null,
    val bytes: Long = 0,
    val error: String? = null,
) {
    enum class Status { SCHEDULED, RECORDING, DONE, FAILED }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("ck", channelKey).put("cn", channelName).put("t", title).put("s", start)
        .put("e", stop).put("u", url).put("st", status.name).put("f", file ?: "").put("b", bytes).put("err", error ?: "")

    companion object {
        fun fromJson(o: JSONObject) = Recording(o.optString("id"), o.optString("ck"), o.optString("cn"), o.optString("t"), o.optLong("s"),
            o.optLong("e"), o.optString("u"), runCatching { Status.valueOf(o.optString("st")) }.getOrDefault(Status.FAILED),
            o.optString("f").ifBlank { null }, o.optLong("b"), o.optString("err").ifBlank { null })
    }
}

/**
 * What a platform that can record provides (Android: a foreground service, alarms and the app's own storage).
 * Platforms without one leave [io.github.novatv.play.platform.Host.recorder] null and the app hides recording.
 */
interface Recorder {
    /** Arms the start (or starts now when [Recording.start] has passed). */
    fun schedule(r: Recording)
    /** Stops a running recording or disarms a scheduled one. */
    fun cancel(id: String)
    fun deleteFile(path: String)
    /** Free space where recordings are written, in bytes. */
    fun freeBytes(): Long
}

/** The list of recordings, kept in the app's settings (not in the backup: the files stay on the device). */
object Recordings {
    /** Bumped on every change, so screens showing recordings refresh. */
    var version by mutableIntStateOf(0)
        private set

    private val lock = io.github.novatv.play.platform.Lock()

    val all: List<Recording> get() = App.store.recordingsJson.let { a -> (0 until a.length()).map { Recording.fromJson(a.getJSONObject(it)) } }

    private fun save(list: List<Recording>) { App.store.recordingsJson = JSONArray(list.map { it.toJson() }); version++ }

    fun update(id: String, f: (Recording) -> Recording) = lock.locked { save(all.map { if (it.id == id) f(it) else it }) }
    fun get(id: String) = all.firstOrNull { it.id == id }

    /** Schedules [p] on [c]; null when it can't be (no recorder, already over, or an overlapping one on that channel). */
    fun schedule(c: Channel, p: Programme?): String? {
        val rec = App.host.recorder ?: return "Recording isn't available on this device"
        val prov = App.repo.provider ?: return "The playlist isn't loaded yet"
        val now = nowMs()
        // Without a guide entry: the next hour from now.
        val start = p?.start ?: now
        val stop = p?.stop ?: (now + 3600_000L)
        if (stop <= now) return "That programme has already ended"
        val key = App.repo.channelKey(c)
        if (all.any { it.channelKey == key && it.status.let { s -> s == Recording.Status.SCHEDULED || s == Recording.Status.RECORDING } && it.start < stop && start < it.stop })
            return "Already set to record"
        val url = try { prov.liveUrl(c) } catch (e: Exception) { return "Could not get the channel's address" }
        val r = Recording("r${now}", key, c.name, p?.title ?: c.name, start, stop, url)
        lock.locked { save(all + r) }
        rec.schedule(r)
        return null
    }

    /** The running or scheduled recording of [c] at [t], if any. */
    fun at(c: Channel, t: Long): Recording? {
        val key = App.repo.channelKey(c)
        return all.firstOrNull { it.channelKey == key && t in it.start until it.stop && (it.status == Recording.Status.SCHEDULED || it.status == Recording.Status.RECORDING) }
    }

    fun cancel(id: String) {
        App.host.recorder?.cancel(id)
        val r = get(id) ?: return
        if (r.status == Recording.Status.SCHEDULED) lock.locked { save(all.filter { it.id != id }) }
    }

    fun delete(id: String) {
        val r = get(id) ?: return
        if (r.status == Recording.Status.RECORDING) App.host.recorder?.cancel(id)
        r.file?.let { App.host.recorder?.deleteFile(it) }
        lock.locked { save(all.filter { it.id != id }) }
    }

    /** Plays a finished (or growing) recording from its file. */
    fun play(r: Recording) {
        val f = r.file ?: return
        io.github.novatv.play.play(PlayItem.Vod("rec:${r.id}", r.title,
            movie = Movie("rec:${r.id}", r.title, null, "", null, null, if (f.startsWith("file:")) f else "file://$f")))
    }
}
