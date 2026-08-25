package com.pushsignal

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushSignalMessagingService : FirebaseMessagingService() {
  override fun onCreate() {
    super.onCreate()
    PushSignalCenter.attach(applicationContext)
  }

  override fun onNewToken(token: String) {
    PushSignalCenter.attach(applicationContext)
  }

  override fun onMessageReceived(message: RemoteMessage) {
    PushSignalCenter.attach(applicationContext)
    PushSignalCenter.emitMessage(message)
  }
}
