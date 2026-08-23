package com.margelo.nitro.pushsignal

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.core.Promise

@DoNotStrip
class PushSignal : HybridPushSignalSpec() {
  override fun initialize(config: AndroidFirebaseConfig): Promise<Unit> {
    val promise = Promise<Unit>()
    PushSignalCenter.initialize(config) { error ->
      if (error == null) {
        promise.resolve(Unit)
      } else {
        promise.reject(error)
      }
    }
    return promise
  }

  override fun getCredentials(): Promise<PushCredentials> {
    return Promise.async {
      val token = PushSignalCenter.fetchToken()
      PushCredentials(
        PushPlatform.ANDROID_OS,
        token,
        null
      )
    }
  }

  override fun setOnMessage(callback: (message: PushMessage) -> Unit) {
    PushSignalCenter.setOnMessage(callback)
  }

  override fun setOnNotificationPress(callback: (message: PushMessage) -> Unit) {
    PushSignalCenter.setOnNotificationPress(callback)
  }
}
