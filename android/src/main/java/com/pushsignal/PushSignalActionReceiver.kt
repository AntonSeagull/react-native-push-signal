package com.pushsignal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives taps on notification action buttons. The foreground poster builds a
 * broadcast PendingIntent per button that carries the button id and the full
 * message payload; this receiver hands the tap back to [PushSignalCenter].
 */
class PushSignalActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    PushSignalCenter.attach(context)
    PushSignalCenter.handleAction(intent)
  }
}
