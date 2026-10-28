package com.pushsignal

import android.content.Intent
import android.util.Log
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
      guard("Module.onNewIntent") {
        Log.d("PushSignal", "[tap] ActivityEventListener.onNewIntent: action=${intent.action}")
        PushSignalCenter.handleNewIntent(reactApplicationContext.currentActivity, intent)
      }
    }
  }

  init {
    guard("Module.init") {
      reactApplicationContext.addActivityEventListener(activityEventListener)
      Log.d("PushSignal", "[tap] ActivityEventListener registered on ReactContext")
    }
  }

  override fun initialize(config: ReadableMap, promise: Promise) {
    try {
      initializeUnsafe(config, promise)
    } catch (error: Throwable) {
      reportGuarded("Module.initialize", error)
      rejectSafely(promise, "E_INIT", error)
    }
  }

  private fun initializeUnsafe(config: ReadableMap, promise: Promise) {
    val firebaseConfig = AndroidFirebaseConfig(
      project_id = config.getStringOrNull("project_id"),
      mobilesdk_app_id = config.getStringOrNull("mobilesdk_app_id"),
      current_key = config.getStringOrNull("current_key"),
      project_number = config.getStringOrNull("project_number"),
    )

    val devPanel = config.hasKey("devPanel") && !config.isNull("devPanel") &&
      config.getBoolean("devPanel")

    PushSignalCenter.attach(reactApplicationContext)
    PushSignalCenter.setDevPanel(devPanel)
    PushSignalCenter.initialize(firebaseConfig) { error ->
      if (error == null) {
        guard("Module.initialize.resolve") { promise.resolve(null) }
      } else {
        val code = (error as? PushSignalException)?.code ?: "E_INIT"
        rejectSafely(promise, code, error)
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
      } catch (error: Throwable) {
        // Throwable, not Exception: a NoClassDefFoundError on this worker thread
        // would otherwise kill the whole process.
        val code = (error as? PushSignalException)?.code ?: "E_CREDENTIALS"
        rejectSafely(promise, code, error)
      }
    }
  }

  override fun getDiagnostics(promise: Promise) {
    executor.execute {
      try {
        PushSignalCenter.attach(reactApplicationContext)
        promise.resolve(PushSignalCenter.diagnose(reactApplicationContext).toWritableMap())
      } catch (error: Throwable) {
        rejectSafely(promise, "E_DIAGNOSTICS", error)
      }
    }
  }

  override fun startListening() {
    if (listening) {
      return
    }
    listening = true
    Log.d("PushSignal", "[tap] startListening: binding native callbacks")

    // `emitOn...` dereferences the codegen event emitter, which is null on the
    // old architecture and after the JS runtime is torn down. Each emit is
    // guarded so a missing emitter drops the event instead of crashing.
    guard("Module.startListening") {
      PushSignalCenter.setOnMessage { message ->
        guard("Module.emitOnMessage") {
          emitOnMessage(message.toWritableMap())
        }
      }
      PushSignalCenter.setOnNotificationPress { message ->
        guard("Module.emitOnNotificationPress") {
          Log.d("PushSignal", "[tap] emitOnNotificationPress -> JS: id=${message.id}")
          emitOnNotificationPress(message.toWritableMap())
        }
      }
      PushSignalCenter.setOnNotificationAction { message ->
        guard("Module.emitOnNotificationAction") {
          Log.d("PushSignal", "[action] emitOnNotificationAction -> JS: action=${message.action}")
          emitOnNotificationAction(message.toWritableMap())
        }
      }
    }
  }

  override fun invalidate() {
    guard("Module.invalidate") {
      reactApplicationContext.removeActivityEventListener(activityEventListener)
      // Drop callbacks bound to this (now dead) JS runtime; events are buffered
      // again until the next runtime calls startListening.
      PushSignalCenter.clearListeners()
      PushSignalCenter.setDevPanel(false)
    }
    super.invalidate()
  }

  private fun rejectSafely(promise: Promise, code: String, error: Throwable) {
    guard("Module.reject") {
      promise.reject(code, error.message ?: error.javaClass.simpleName, error)
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
  image?.let { map.putString("image", it) }
  action?.let { map.putString("action", it) }
  if (buttons.isNotEmpty()) {
    val buttonsArray = Arguments.createArray()
    buttons.forEach { button ->
      val buttonMap = Arguments.createMap().apply {
        putString("id", button.id)
        putString("title", button.title)
        button.extras.forEach { (key, value) ->
          putString(key, value)
        }
      }
      buttonsArray.pushMap(buttonMap)
    }
    map.putArray("buttons", buttonsArray)
  }
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
