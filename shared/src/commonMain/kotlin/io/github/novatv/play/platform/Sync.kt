package io.github.novatv.play.platform

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** A lock for code shared by several threads (works on the JVM and on iOS). */
class Lock : SynchronizedObject()

inline fun <T> Lock.locked(block: () -> T): T = synchronized(this, block)

/** A map several threads read and write; values are computed outside the lock so a slow one blocks no one. */
class SafeMap<K, V> {
    private val lock = Lock()
    private val m = HashMap<K, V>()
    operator fun get(k: K): V? = lock.locked { m[k] }
    operator fun set(k: K, v: V) { lock.locked { m[k] = v } }
    fun remove(k: K) { lock.locked { m.remove(k) } }
    fun clear() = lock.locked { m.clear() }
    inline fun getOrPut(k: K, make: () -> V): V = get(k) ?: make().also { set(k, it) }
}
