package com.pushsignal

import android.util.Log

/**
 * Crash shield for every entry point Android or React Native can call into.
 *
 * The library must never crash the host app: a push that is lost is better than
 * an app that dies on start. Everything the system invokes (ContentProvider,
 * FirebaseMessagingService, ActivityLifecycleCallbacks, main-thread posts,
 * background threads, TurboModule methods) runs through [guard], which swallows
 * any [Throwable] — including `NoClassDefFoundError` / `LinkageError` from a host
 * with a mismatched Firebase/GMS version — logs it and returns [fallback].
 *
 * Never remove these guards. See AGENTS.md, "Приложение никогда не должно падать".
 */
internal inline fun <T> guard(where: String, fallback: T, block: () -> T): T {
  return try {
    block()
  } catch (error: Throwable) {
    reportGuarded(where, error)
    fallback
  }
}

internal inline fun guard(where: String, block: () -> Unit) {
  guard(where, Unit, block)
}

/** Logging itself must not throw, so it is isolated as well. */
internal fun reportGuarded(where: String, error: Throwable) {
  try {
    Log.e("PushSignal", "Suppressed crash in $where: ${error.javaClass.name}: ${error.message}", error)
  } catch (_: Throwable) {
  }
  try {
    PushSignalDevLog.add(
      DevLogLevel.ERROR,
      "guard",
      "Подавлен сбой: $where",
      "${error.javaClass.simpleName}: ${error.message}"
    )
  } catch (_: Throwable) {
  }
}
