package com.pushsignal

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

internal object PushSignalCenter : Application.ActivityLifecycleCallbacks {
  private const val TAG = "PushSignal"
  private const val EXTRA_HANDLED = "pushsignal.handled"
  private const val CHANNEL_ID = "push_signal_default"
  /** Collapses the same tap seen through several Android entry points. */
  private const val PRESS_DEDUPE_MS = 2_000L

  /** Upper bound for taps buffered before JS subscribes. */
  private const val MAX_PENDING_PRESSES = 20
  private const val TOKEN_TIMEOUT_SECONDS = 10L
  private const val TOKEN_MAX_ATTEMPTS = 5
  private const val TOKEN_INITIAL_RETRY_DELAY_MS = 1_000L
  private const val TOKEN_MAX_RETRY_DELAY_MS = 8_000L

  private const val PROVIDER_FCM = "fcm"
  private const val PROVIDER_HMS = "hms"
  private const val PROVIDER_MI_PUSH = "mi_push"
  private const val PROVIDER_OPPO_PUSH = "oppo_push"
  private const val PROVIDER_VIVO_PUSH = "vivo_push"
  private const val PROVIDER_MEIZU_PUSH = "meizu_push"
  private const val PROVIDER_UNKNOWN = "unknown"

  /** Service apps used to positively confirm an alternative provider on the device. */
  private const val PACKAGE_HMS = "com.huawei.hwid"
  private const val PACKAGE_MI_PUSH = "com.xiaomi.xmsf"

  private val lock = Any()
  private val mainHandler = Handler(Looper.getMainLooper())
  @Volatile private var application: Application? = null
  @Volatile private var currentActivity: Activity? = null
  @Volatile private var onMessage: ((PushMessage) -> Unit)? = null
  @Volatile private var onNotificationPress: ((PushMessage) -> Unit)? = null
  private val pendingPresses = ArrayDeque<PushMessage>()
  private var lastPressKey: String? = null
  private var lastPressAt = 0L
  private val pendingMessages = CopyOnWriteArrayList<PushMessage>()
  private val registeredActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
  @Volatile private var startedActivityCount = 0
  @Volatile private var pendingFirebaseConfig: AndroidFirebaseConfig? = null
  private val initializeWaiters = CopyOnWriteArrayList<(Exception?) -> Unit>()

  fun attach(context: Context) {
    val app = context.applicationContext as? Application ?: return
    if (application === app) {
      return
    }
    application?.unregisterActivityLifecycleCallbacks(this)
    application = app
    app.registerActivityLifecycleCallbacks(this)
    app.currentActivityOrNull()?.let { activity ->
      currentActivity = activity
      registerActivity(activity)
      handleIntent(activity.intent)
    }
    pendingFirebaseConfig?.let { config ->
      pendingFirebaseConfig = null
      finishInitialize(applyFirebaseConfig(app, config))
    }
  }

  fun initialize(config: AndroidFirebaseConfig, onDone: (Exception?) -> Unit) {
    if (!config.hasRequiredFields()) {
      onDone(null)
      return
    }

    val context = application
    if (context == null) {
      pendingFirebaseConfig = config
      initializeWaiters.add(onDone)
      return
    }

    onDone(applyFirebaseConfig(context, config))
  }

  fun setOnMessage(callback: (PushMessage) -> Unit) {
    onMessage = callback
    val queued = pendingMessages.toList()
    pendingMessages.clear()
    queued.forEach { message ->
      runOnMain { deliverMessage(message) }
    }
  }

  fun setOnNotificationPress(callback: (PushMessage) -> Unit) {
    onNotificationPress = callback
    val pending = synchronized(lock) {
      val queued = pendingPresses.toList()
      pendingPresses.clear()
      queued
    }
    pending.forEach(callback)
  }

  fun fetchToken(): String {
    val context = application
      ?: throw PushSignalException("E_NOT_INITIALIZED", "PushSignal is not initialized")

    ensurePlayServices(context)

    try {
      if (FirebaseApp.getApps(context).isEmpty()) {
        FirebaseApp.initializeApp(context)
      }
      FirebaseApp.getInstance()
    } catch (error: IllegalStateException) {
      throw PushSignalException(
        "E_FIREBASE_CONFIG",
        "Firebase is not configured. Call initialize({ project_id, mobilesdk_app_id, current_key, project_number }) or add google-services.json.",
        error
      )
    }

    val token = awaitTokenWithRetry(context)

    if (token.isNullOrEmpty()) {
      throw PushSignalException("E_FCM_TOKEN", "Firebase returned an empty FCM token")
    }

    return token
  }

  /**
   * Fails fast with an actionable message when Google Play services cannot serve FCM
   * (for example a China-only ROM without GMS). The message names the provider the
   * device should use instead, so the cause is obvious in React Native logs.
   */
  private fun ensurePlayServices(context: Context) {
    val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
    if (status == ConnectionResult.SUCCESS) {
      return
    }

    val code = when (status) {
      ConnectionResult.SERVICE_MISSING -> "E_GMS_MISSING"
      ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> "E_GMS_UPDATE_REQUIRED"
      ConnectionResult.SERVICE_DISABLED -> "E_GMS_DISABLED"
      ConnectionResult.SERVICE_INVALID -> "E_GMS_INVALID"
      else -> "E_GMS_UNAVAILABLE"
    }

    val reason = when (status) {
      ConnectionResult.SERVICE_MISSING ->
        "Google Play services are missing on this device, so the standard Google push service (FCM) cannot be used."
      ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED ->
        "Google Play services are outdated. Update them in the Play Store and try again."
      ConnectionResult.SERVICE_DISABLED ->
        "Google Play services are disabled. Enable them in the device settings and try again."
      ConnectionResult.SERVICE_INVALID ->
        "Google Play services are invalid or corrupted on this device. Reinstalling them usually helps."
      else ->
        "Google Play services are unavailable (code $status), so the standard Google push service (FCM) cannot be used."
    }

    val diagnostics = diagnose(context)
    val message = listOfNotNull(reason, diagnostics.hint).joinToString(" ")
    Log.w(TAG, "Play services check failed ($code): $message")
    throw PushSignalException(code, message)
  }

  /**
   * Collects device and provider diagnostics without throwing. Used both by the
   * `getDiagnostics` API and to enrich errors with a concrete replacement service.
   */
  fun diagnose(context: Context): PushDiagnostics {
    val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
    val gmsAvailable = status == ConnectionResult.SUCCESS
    val provider = resolveProvider(context, gmsAvailable)
    val hint = providerHint(provider, gmsAvailable)

    return PushDiagnostics(
      platform = "android_os",
      gmsAvailable = gmsAvailable,
      gmsStatus = status,
      manufacturer = Build.MANUFACTURER ?: "",
      brand = Build.BRAND ?: "",
      model = Build.MODEL ?: "",
      provider = provider.id,
      providerName = provider.name,
      providerInstalled = provider.installed,
      hint = hint,
    )
  }

  private data class ProviderInfo(
    val id: String,
    val name: String,
    val installed: Boolean?,
  )

  /**
   * Picks the push provider this device should use. Checks the standard Google
   * service first, then positively confirms an installed alternative, then falls
   * back to the manufacturer mapping. Class reflection cannot see another
   * provider's SDK unless the host bundles it, so package checks and the
   * manufacturer are the reliable signals.
   */
  private fun resolveProvider(context: Context, gmsAvailable: Boolean): ProviderInfo {
    if (gmsAvailable) {
      return ProviderInfo(PROVIDER_FCM, "Firebase Cloud Messaging (FCM)", true)
    }

    if (isPackageInstalled(context, PACKAGE_HMS)) {
      return ProviderInfo(PROVIDER_HMS, "HMS Push Kit (Huawei Push)", true)
    }
    if (isPackageInstalled(context, PACKAGE_MI_PUSH)) {
      return ProviderInfo(PROVIDER_MI_PUSH, "Mi Push (Xiaomi Push)", true)
    }

    val keys = setOf(Build.MANUFACTURER, Build.BRAND)
      .filterNotNull()
      .map { it.uppercase() }

    return when {
      keys.any { it.contains("HUAWEI") || it.contains("HONOR") } ->
        ProviderInfo(PROVIDER_HMS, "HMS Push Kit (Huawei Push)", false)
      keys.any { it.contains("XIAOMI") || it.contains("REDMI") || it.contains("POCO") } ->
        ProviderInfo(PROVIDER_MI_PUSH, "Mi Push (Xiaomi Push)", false)
      keys.any { it.contains("OPPO") || it.contains("ONEPLUS") || it.contains("REALME") } ->
        ProviderInfo(PROVIDER_OPPO_PUSH, "OPPO Push (HeyTap)", false)
      keys.any { it.contains("VIVO") || it.contains("IQOO") } ->
        ProviderInfo(PROVIDER_VIVO_PUSH, "vivo Push", false)
      keys.any { it.contains("MEIZU") } ->
        ProviderInfo(PROVIDER_MEIZU_PUSH, "Meizu Push", false)
      else ->
        ProviderInfo(PROVIDER_UNKNOWN, "unknown", null)
    }
  }

  private fun providerHint(provider: ProviderInfo, gmsAvailable: Boolean): String? {
    if (gmsAvailable) {
      return null
    }
    val device = listOfNotNull(Build.MANUFACTURER, Build.BRAND)
      .filter { it.isNotBlank() }
      .distinct()
      .joinToString("/")

    return when (provider.id) {
      PROVIDER_HMS ->
        "This device ($device) has no usable Google Play services, so FCM will not issue a token. Use HMS Push Kit (Huawei Push) instead of the standard Google service."
      PROVIDER_MI_PUSH ->
        "This device ($device) has no usable Google Play services, so FCM will not issue a token. Use Mi Push (Xiaomi Push) instead of the standard Google service."
      PROVIDER_OPPO_PUSH ->
        "This device ($device) has no usable Google Play services, so FCM will not issue a token. Use OPPO Push (HeyTap) instead of the standard Google service."
      PROVIDER_VIVO_PUSH ->
        "This device ($device) has no usable Google Play services, so FCM will not issue a token. Use vivo Push instead of the standard Google service."
      PROVIDER_MEIZU_PUSH ->
        "This device ($device) has no usable Google Play services, so FCM will not issue a token. Use Meizu Push instead of the standard Google service."
      else ->
        "No usable Google Play services were found and no known push provider was detected on this ROM, so FCM cannot be used. Integrate another push provider (HMS, Mi Push, OPPO/vivo/Meizu Push)."
    }
  }

  private fun isPackageInstalled(context: Context, packageName: String): Boolean {
    return try {
      context.packageManager.getPackageInfo(packageName, 0)
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    } catch (_: Exception) {
      false
    }
  }

  /**
   * FCM returns SERVICE_NOT_AVAILABLE for transient conditions (no Google account yet,
   * Play services still starting, flaky network). Xiaomi/MIUI devices hit this often,
   * so retry with backoff before surfacing the failure.
   */
  private fun awaitTokenWithRetry(context: Context): String? {
    var delayMs = TOKEN_INITIAL_RETRY_DELAY_MS
    var lastError: Exception? = null

    for (attempt in 1..TOKEN_MAX_ATTEMPTS) {
      try {
        return Tasks.await(
          FirebaseMessaging.getInstance().token,
          TOKEN_TIMEOUT_SECONDS,
          TimeUnit.SECONDS
        )
      } catch (error: Exception) {
        lastError = error
        if (attempt == TOKEN_MAX_ATTEMPTS || !isRetryableTokenError(error)) {
          break
        }
        Log.w(TAG, "FCM token attempt $attempt/$TOKEN_MAX_ATTEMPTS failed: ${error.message}. Retrying in ${delayMs}ms")
        try {
          Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          break
        }
        delayMs = (delayMs * 2).coerceAtMost(TOKEN_MAX_RETRY_DELAY_MS)
      }
    }

    val cause = lastError
    val diagnostics = diagnose(context)
    val message = listOfNotNull(
      "Failed to get an FCM token: ${cause?.message ?: "unknown error"}.",
      "Make sure the device is signed into a Google account, Google Play services are up to date, " +
        "and the app is allowed to use background data.",
      "Call initialize({ project_id, mobilesdk_app_id, current_key, project_number }) or add google-services.json.",
      diagnostics.hint,
    ).joinToString(" ")
    Log.w(TAG, "Failed to get an FCM token: $message")
    throw PushSignalException("E_FCM_TOKEN", message, cause)
  }

  private fun isRetryableTokenError(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
      if (current is java.io.IOException || current is java.util.concurrent.TimeoutException) {
        return true
      }
      val message = current.message?.uppercase() ?: ""
      if (
        message.contains("SERVICE_NOT_AVAILABLE") ||
        message.contains("TIMEOUT") ||
        message.contains("INTERNAL_SERVER_ERROR")
      ) {
        return true
      }
      current = current.cause
    }
    return false
  }

  fun emitMessage(remoteMessage: RemoteMessage) {
    val message = remoteMessage.toPushMessage()
    runOnMain { deliverMessage(message) }
  }

  private fun deliverMessage(message: PushMessage) {
    val callback = onMessage
    if (callback == null) {
      pendingMessages.add(message)
      return
    }

    try {
      callback(message)
    } catch (_: Throwable) {
      // Ignore listener failures.
    }

    val inForeground = startedActivityCount > 0 || currentActivity != null
    if (
      inForeground &&
      (!message.title.isNullOrEmpty() || !message.body.isNullOrEmpty())
    ) {
      postForegroundNotification(message)
    }
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
    } else {
      mainHandler.post(block)
    }
  }

  private fun postForegroundNotification(message: PushMessage) {
    val context = application ?: return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      ?: return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val existing = manager.getNotificationChannel(CHANNEL_ID)
      if (existing == null) {
        manager.createNotificationChannel(
          NotificationChannel(
            CHANNEL_ID,
            "Notifications",
            NotificationManager.IMPORTANCE_HIGH
          )
        )
      }
    }

    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?: return
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    launchIntent.putExtra(EXTRA_PRESS_KEY, true)
    launchIntent.putExtra("google.message_id", message.id ?: UUID.randomUUID().toString())
    message.title?.let { launchIntent.putExtra("gcm.notification.title", it) }
    message.body?.let { launchIntent.putExtra("gcm.notification.body", it) }
    message.data.forEach { (key, value) ->
      launchIntent.putExtra(key, value)
    }

    val requestCode = (message.id ?: message.title ?: "push").hashCode()
    val pendingIntent = PendingIntent.getActivity(
      context,
      requestCode,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(smallIcon(context))
      .setContentTitle(message.title.orEmpty())
      .setContentText(message.body.orEmpty())
      .setContentIntent(pendingIntent)
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setDefaults(NotificationCompat.DEFAULT_SOUND)
      .setNumber(1)

    manager.notify(requestCode, builder.build())
  }

  private fun smallIcon(context: Context): Int {
    val named = context.resources.getIdentifier("ic_notification", "drawable", context.packageName)
    if (named != 0) {
      return named
    }
    val appIcon = context.applicationInfo.icon
    if (appIcon != 0) {
      return appIcon
    }
    return android.R.drawable.stat_notify_more
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
    currentActivity = activity
    registerActivity(activity)
    handleIntent(activity.intent)
  }

  override fun onActivityStarted(activity: Activity) {
    currentActivity = activity
    startedActivityCount += 1
  }

  override fun onActivityResumed(activity: Activity) {
    currentActivity = activity
    handleIntent(activity.intent)
  }

  override fun onActivityPaused(activity: Activity) {
    if (currentActivity === activity) {
      currentActivity = null
    }
  }

  override fun onActivityStopped(activity: Activity) {
    startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
    if (currentActivity === activity) {
      currentActivity = null
    }
  }

  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

  override fun onActivityDestroyed(activity: Activity) {
    if (currentActivity === activity) {
      currentActivity = null
    }
  }

  private fun registerActivity(activity: Activity) {
    if (!registeredActivities.add(activity)) {
      return
    }

    val componentActivity = activity as? ComponentActivity ?: return
    componentActivity.addOnNewIntentListener { intent ->
      handleIntent(intent)
    }
  }

  private fun handleIntent(intent: Intent?) {
    if (intent == null || !intent.isPushTap() || intent.getBooleanExtra(EXTRA_HANDLED, false)) {
      return
    }

    intent.putExtra(EXTRA_HANDLED, true)
    emitPress(intent.toPushMessage())
  }

  /**
   * Entry point for taps observed by the React runtime. On the new architecture
   * `ReactActivity.onNewIntent` never calls `super`, so `ComponentActivity`'s
   * `addOnNewIntentListener` callbacks do not fire; `ReactContext` still dispatches
   * the intent to its [com.facebook.react.bridge.ActivityEventListener]s, and the
   * native module forwards them here. Also syncs the Activity's intent so the
   * lifecycle fallback in `onActivityResumed` re-reads the fresh payload.
   */
  internal fun handleNewIntent(activity: Activity?, intent: Intent?) {
    if (intent == null) {
      return
    }
    if (activity != null) {
      currentActivity = activity
      try {
        activity.intent = intent
      } catch (_: Throwable) {
        // Not every Activity accepts a replaced intent; the direct call still delivers.
      }
    }
    handleIntent(intent)
  }

  private fun applyFirebaseConfig(context: Context, config: AndroidFirebaseConfig): Exception? {
    if (FirebaseApp.getApps(context).isNotEmpty()) {
      FirebaseMessaging.getInstance().isAutoInitEnabled = true
      return null
    }

    if (!config.hasRequiredFields()) {
      return null
    }

    return try {
      val options =
        FirebaseOptions.Builder()
          .setProjectId(config.project_id!!.trim())
          .setApplicationId(config.mobilesdk_app_id!!.trim())
          .setApiKey(config.current_key!!.trim())
          .setGcmSenderId(config.project_number!!.trim())
          .build()
      FirebaseApp.initializeApp(context, options)
      FirebaseMessaging.getInstance().isAutoInitEnabled = true
      null
    } catch (error: Exception) {
      error
    }
  }

  private fun finishInitialize(error: Exception?) {
    val waiters = initializeWaiters.toList()
    initializeWaiters.clear()
    waiters.forEach { it(error) }
  }

  private fun emitPress(message: PushMessage) {
    if (!markPressDelivered(message)) {
      return
    }
    val listener = onNotificationPress
    if (listener != null) {
      listener(message)
    } else {
      synchronized(lock) {
        pendingPresses.addLast(message)
        while (pendingPresses.size > MAX_PENDING_PRESSES) {
          pendingPresses.removeFirst()
        }
      }
    }
  }

  /**
   * Several Android entry points can observe the same tap (ReactContext's
   * ActivityEventListener, ActivityLifecycleCallbacks, ComponentActivity's
   * onNewIntent listener). Collapse duplicates by message id so subscribers
   * receive exactly one press per notification.
   */
  private fun markPressDelivered(message: PushMessage): Boolean {
    val key = message.id ?: "${message.title.orEmpty()}|${message.body.orEmpty()}"
    val now = System.currentTimeMillis()
    synchronized(lock) {
      if (lastPressKey == key && now - lastPressAt < PRESS_DEDUPE_MS) {
        return false
      }
      lastPressKey = key
      lastPressAt = now
    }
    return true
  }
}

private fun Application.currentActivityOrNull(): Activity? {
  return try {
    val activityThreadClass = Class.forName("android.app.ActivityThread")
    val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
    val activitiesField = activityThreadClass.getDeclaredField("mActivities")
    activitiesField.isAccessible = true
    val activities = activitiesField.get(activityThread) as Map<*, *>
    activities.values.firstNotNullOfOrNull { record ->
      val recordClass = record?.javaClass ?: return@firstNotNullOfOrNull null
      val paused = recordClass.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(record)
      if (paused) {
        return@firstNotNullOfOrNull null
      }
      recordClass.getDeclaredField("activity").apply { isAccessible = true }.get(record) as? Activity
    }
  } catch (_: Exception) {
    null
  }
}

private fun Intent.isPushTap(): Boolean {
  val extras = extras ?: return false
  return extras.getBoolean(EXTRA_PRESS_KEY, false) ||
    extras.containsKey("google.message_id") ||
    extras.containsKey("google.sent_time") ||
    extras.containsKey("gcm.n.e") ||
    extras.containsKey("gcm.notification.title")
}

private fun Intent.toPushMessage(): PushMessage {
  val extras = extras ?: Bundle()
  val data = linkedMapOf<String, String>()
  for (key in extras.keySet()) {
    if (
      key.startsWith("google.") ||
      key.startsWith("gcm.") ||
      key == EXTRA_HANDLED_KEY ||
      key == EXTRA_PRESS_KEY
    ) {
      continue
    }
    @Suppress("DEPRECATION")
    val value = extras.get(key) ?: continue
    data[key] = value.toString()
  }

  return PushMessage(
    extras.getString("google.message_id"),
    extras.getString("gcm.n.title")
      ?: extras.getString("gcm.notification.title")
      ?: extras.getString("title"),
    extras.getString("gcm.n.body")
      ?: extras.getString("gcm.notification.body")
      ?: extras.getString("body"),
    data
  )
}

private fun AndroidFirebaseConfig.hasRequiredFields(): Boolean {
  return !project_id.isNullOrBlank() &&
    !mobilesdk_app_id.isNullOrBlank() &&
    !current_key.isNullOrBlank() &&
    !project_number.isNullOrBlank()
}

private const val EXTRA_HANDLED_KEY = "pushsignal.handled"
private const val EXTRA_PRESS_KEY = "pushsignal.press"

internal fun RemoteMessage.toPushMessage(): PushMessage {
  return PushMessage(
    messageId,
    notification?.title,
    notification?.body,
    data
  )
}
