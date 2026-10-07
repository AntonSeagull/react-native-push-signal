package com.pushsignal

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Severity of a dev-panel entry. Drives the colour used by
 * [PushSignalDevPanel] so a glance is enough to spot a failure.
 */
internal enum class DevLogLevel(val label: String) {
  INFO("INFO"),
  SUCCESS("OK"),
  WARN("WARN"),
  ERROR("ERROR"),
}

internal data class DevLogEntry(
  val id: Long,
  val timestamp: Long,
  val level: DevLogLevel,
  /** Short group, e.g. "push", "tap", "token", "init". */
  val category: String,
  val title: String,
  val detail: String? = null,
)

/**
 * In-memory ring buffer that records what the notification module is doing.
 *
 * Entries are collected unconditionally (cheap and capped) so a push that
 * arrives before `initialize({ devPanel: true })` — or from the background
 * `FirebaseMessagingService` — is still visible once the panel is enabled.
 * History lives only for the current process, as requested.
 */
internal object PushSignalDevLog {
  private const val MAX_ENTRIES = 300

  private val lock = Any()
  private val entries = ArrayDeque<DevLogEntry>()
  private var nextId = 0L

  private val listeners = CopyOnWriteArrayList<() -> Unit>()
  private val mainHandler = Handler(Looper.getMainLooper())

  fun add(
    level: DevLogLevel,
    category: String,
    title: String,
    detail: String? = null,
  ) {
    synchronized(lock) {
      entries.addLast(DevLogEntry(nextId++, System.currentTimeMillis(), level, category, title, detail))
      while (entries.size > MAX_ENTRIES) {
        entries.removeFirst()
      }
    }
    dispatchToListeners()
  }

  fun snapshot(): List<DevLogEntry> = synchronized(lock) { entries.toList() }

  fun count(): Int = synchronized(lock) { entries.size }

  fun lastLevel(): DevLogLevel? = synchronized(lock) { entries.lastOrNull()?.level }

  fun clear() {
    synchronized(lock) { entries.clear() }
    dispatchToListeners()
  }

  fun addListener(listener: () -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }

  private fun dispatchToListeners() {
    if (listeners.isEmpty()) {
      return
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      listeners.forEach { it() }
    } else {
      mainHandler.post { listeners.forEach { it() } }
    }
  }
}
