package com.pushsignal

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import java.util.concurrent.Executors

class PushSignalModule(reactContext: ReactApplicationContext) :
  NativePushSignalSpec(reactContext) {

  @Volatile
  private var listening = false

  override fun initialize(config: ReadableMap, promise: Promise) {
    val firebaseConfig = AndroidFirebaseConfig(
      project_id = config.getStringOrNull("project_id"),
      mobilesdk_app_id = config.getStringOrNull("mobilesdk_app_id"),
      current_key = config.getStringOrNull("current_key"),
      project_number = config.getStringOrNull("project_number"),
    )

    PushSignalCenter.attach(reactApplicationContext)
    PushSignalCenter.initialize(firebaseConfig) { error ->
      if (error == null) {
        promise.resolve(null)
      } else {
        promise.reject("E_INIT", error.message, error)
      }
    }
  }

  override fun getCredentials(promise: Promise) {
    executor.execute {
      try {
        PushSignalCenter.attach(reactApplicationContext)
        val token = PushSignalCenter.fetchToken()
        val result = Arguments.createMap().apply {
          putString("platform", "android_os")
          putString("token", token)
        }
        promise.resolve(result)
      } catch (error: Exception) {
        promise.reject("E_CREDENTIALS", error.message, error)
      }
    }
  }

  override fun startListening() {
    if (listening) {
      return
    }
    listening = true

    PushSignalCenter.setOnMessage { message ->
      emitOnMessage(message.toWritableMap())
    }
    PushSignalCenter.setOnNotificationPress { message ->
      emitOnNotificationPress(message.toWritableMap())
    }
  }

  companion object {
    const val NAME = NativePushSignalSpec.NAME
    private val executor = Executors.newSingleThreadExecutor()
  }
}

private fun ReadableMap.getStringOrNull(key: String): String? {
  if (!hasKey(key) || isNull(key)) {
    return null
  }
  return getString(key)
}

private fun PushMessage.toWritableMap(): WritableMap {
  val map = Arguments.createMap()
  id?.let { map.putString("id", it) }
  title?.let { map.putString("title", it) }
  body?.let { map.putString("body", it) }
  val dataMap = Arguments.createMap()
  data.forEach { (key, value) ->
    dataMap.putString(key, value)
  }
  map.putMap("data", dataMap)
  return map
}
