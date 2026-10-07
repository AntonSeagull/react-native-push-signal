package com.pushsignal

import android.content.Intent
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import java.util.concurrent.Executors

class PushSignalModule(reactContext: ReactApplicationContext) :
  NativePushSignalSpec(reactContext) {

  @Volatile
  private var listening = false

  /**
   * Catches notification taps while the JS process is alive. On the new
   * architecture `ReactActivity` swallows `onNewIntent`, so
   * `ComponentActivity`'s own listeners never fire; the React runtime still
   * dispatches intents to registered `ActivityEventListener`s.
   */
  private val activityEventListener = object : BaseActivityEventListener() {
    override fun onNewIntent(intent: Intent) {
      PushSignalCenter.handleNewIntent(reactApplicationContext.currentActivity, intent)
    }
  }

  init {
    reactApplicationContext.addActivityEventListener(activityEventListener)
  }

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
        val code = (error as? PushSignalException)?.code ?: "E_INIT"
        promise.reject(code, error.message, error)
      }
    }
  }

  override fun getCredentials(promise: Promise) {
    executor.execute {
      try {
        PushSignalCenter.attach(reactApplicationContext)
        val token = PushSignalCenter.fetchToken()
        val result = Arguments.createMap().apply {
          putString("token", token)
        }
        promise.resolve(result)
      } catch (error: Exception) {
        val code = (error as? PushSignalException)?.code ?: "E_CREDENTIALS"
        promise.reject(code, error.message, error)
      }
    }
  }

  override fun getDiagnostics(promise: Promise) {
    executor.execute {
      try {
        PushSignalCenter.attach(reactApplicationContext)
        promise.resolve(PushSignalCenter.diagnose(reactApplicationContext).toWritableMap())
      } catch (error: Exception) {
        promise.reject("E_DIAGNOSTICS", error.message, error)
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

  override fun invalidate() {
    reactApplicationContext.removeActivityEventListener(activityEventListener)
    super.invalidate()
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

private fun PushDiagnostics.toWritableMap(): WritableMap {
  val map = Arguments.createMap()
  map.putString("platform", platform)
  map.putBoolean("gmsAvailable", gmsAvailable)
  map.putInt("gmsStatus", gmsStatus)
  map.putString("manufacturer", manufacturer)
  map.putString("brand", brand)
  map.putString("model", model)
  map.putString("provider", provider)
  map.putString("providerName", providerName)
  providerInstalled?.let { map.putBoolean("providerInstalled", it) }
  hint?.let { map.putString("hint", it) }
  return map
}
