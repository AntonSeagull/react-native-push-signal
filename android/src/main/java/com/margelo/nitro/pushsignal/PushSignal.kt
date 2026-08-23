package com.margelo.nitro.pushsignal

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.core.Promise

@DoNotStrip
class PushSignal : HybridPushSignalSpec() {
  override fun initialize(config: AndroidFirebaseConfig) {
    PushSignalCenter.initialize(config)
  }

  override fun getPermissionStatus(): Promise<PermissionStatus> {
    return Promise.async {
      PushSignalCenter.getPermissionStatus()
    }
  }

  override fun requestPermission(): Promise<PermissionStatus> {
    val promise = Promise<PermissionStatus>()
    PushSignalCenter.requestPermission { status ->
      promise.resolve(status)
    }
    return promise
  }

  override fun getCredentials(): Promise<PushCredentials> {
    return Promise.async {
      val token = PushSignalCenter.fetchToken()
      PushCredentials(
        PushPlatform.ANDROID,
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
