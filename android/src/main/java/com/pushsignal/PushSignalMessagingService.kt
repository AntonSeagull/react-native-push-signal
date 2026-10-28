package com.pushsignal

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushSignalMessagingService : FirebaseMessagingService() {
  override fun onCreate() {
    super.onCreate()
    guard("MessagingService.onCreate") {
      PushSignalCenter.attach(applicationContext)
    }
  }

  override fun onNewToken(token: String) {
    guard("MessagingService.onNewToken") {
      PushSignalCenter.attach(applicationContext)
    }
  }

  override fun onMessageReceived(message: RemoteMessage) {
    guard("MessagingService.onMessageReceived") {
      PushSignalCenter.attach(applicationContext)
      PushSignalCenter.emitMessage(message)
    }
  }
}
