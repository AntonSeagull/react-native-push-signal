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
  /** Collapses the same incoming message delivered more than once (FCM is at-least-once). */
  private const val MESSAGE_DEDUPE_MS = 500L

  /** Upper bound for taps buffered before JS subscribes. */
  private const val MAX_PENDING_PRESSES = 20
  private const val TOKEN_TIMEOUT_SECONDS = 10L
  private const val TOKEN_MAX_ATTEMPTS = 5
  private const val TOKEN_INITIAL_RETRY_DELAY_MS = 1_000L
  private const val TOKEN_MAX_RETRY_DELAY_MS = 8_000L
  private const val PLAY_SERVICES_MAX_ATTEMPTS = 5
  private const val PLAY_SERVICES_INITIAL_RETRY_DELAY_MS = 1_000L
  private const val PLAY_SERVICES_MAX_RETRY_DELAY_MS = 8_000L

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
  @Volatile private var onNotificationAction: ((PushMessage) -> Unit)? = null
  /** False until the push module (Firebase) is initialized or already present. */
  @Volatile private var ready = false
  private val pendingPresses = ArrayDeque<PushMessage>()
  private val pendingActions = ArrayDeque<PushMessage>()
  private var lastPressKey: String? = null
  private var lastPressAt = 0L
  private var lastActionKey: String? = null
  private var lastActionAt = 0L
  private val pendingMessages = CopyOnWriteArrayList<PushMessage>()
  private var lastMessageKey: String? = null
  private var lastMessageAt = 0L
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
    Log.d(TAG, "[tap] attach: lifecycle callbacks registered on ${app.javaClass.simpleName}")
    PushSignalDevLog.add(
      DevLogLevel.INFO,
      "lifecycle",
      "Центр подключён",
      app.javaClass.simpleName
    )
    app.currentActivityOrNull()?.let { activity ->
      currentActivity = activity
      registerActivity(activity)
      handleIntent(activity.intent)
    }
    pendingFirebaseConfig?.let { config ->
      pendingFirebaseConfig = null
      finishInitialize(applyFirebaseConfig(app, config))
      markReady()
    }
  }

  /**
   * Enables/disables the native debugging overlay. Called from `initialize`
   * when the host passes `devPanel: true`.
   */
  fun setDevPanel(enabled: Boolean) {
    runOnMain {
      PushSignalDevPanel.setEnabled(enabled)
      if (enabled) {
        currentActivity?.let { PushSignalDevPanel.attach(it) }
      }
    }
  }

  fun initialize(config: AndroidFirebaseConfig, onDone: (Exception?) -> Unit) {
    if (!config.hasRequiredFields()) {
      PushSignalDevLog.add(
        DevLogLevel.WARN,
        "init",
        "Firebase-конфиг неполный",
        "initialize проигнорирован: нужны project_id, mobilesdk_app_id, current_key, project_number"
      )
      onDone(null)
      markReady()
      return
    }

    val context = application
    if (context == null) {
      pendingFirebaseConfig = config
      initializeWaiters.add(onDone)
      return
    }

    onDone(applyFirebaseConfig(context, config))
    markReady()
  }

  fun setOnMessage(callback: (PushMessage) -> Unit) {
    onMessage = callback
    PushSignalDevLog.add(DevLogLevel.INFO, "listener", "JS onMessage подключён")
    ensureReadyIfFirebaseInitialized()
    flushPendingMessagesIfReady()
  }

  fun setOnNotificationPress(callback: (PushMessage) -> Unit) {
    onNotificationPress = callback
    PushSignalDevLog.add(DevLogLevel.INFO, "listener", "JS onNotificationPress подключён")
    ensureReadyIfFirebaseInitialized()
    flushPendingPressesIfReady()
  }

  fun setOnNotificationAction(callback: (PushMessage) -> Unit) {
    onNotificationAction = callback
    PushSignalDevLog.add(DevLogLevel.INFO, "listener", "JS onNotificationAction подключён")
    ensureReadyIfFirebaseInitialized()
    flushPendingActionsIfReady()
  }

  /**
   * Buffered taps/messages are only delivered once the push module is ready
   * (Firebase applied or already present). This removes the cold-start race
   * where a tap fires before initialization completes on slow devices.
   */
  private fun markReady() {
    if (ready) {
      return
    }
    ready = true
    PushSignalDevLog.add(DevLogLevel.SUCCESS, "init", "Модуль готов к доставке")
    flushPendingMessagesIfReady()
    flushPendingPressesIfReady()
    flushPendingActionsIfReady()
  }

  /**
   * When Firebase is already initialized (e.g. the host uses google-services.json
   * and never calls `initialize`), mark the module ready as soon as JS binds its
   * listeners so buffered events are not held back indefinitely.
   */
  private fun ensureReadyIfFirebaseInitialized() {
    if (ready) {
      return
    }
    val app = application ?: return
    if (FirebaseApp.getApps(app).isNotEmpty()) {
      markReady()
    }
  }

  private fun flushPendingMessagesIfReady() {
    val callback = onMessage ?: return
    if (!ready) {
      return
    }
    val queued = pendingMessages.toList()
    pendingMessages.clear()
    queued.forEach { message ->
      runOnMain { deliverMessage(message) }
    }
  }

  private fun flushPendingPressesIfReady() {
    val callback = onNotificationPress ?: return
    if (!ready) {
      return
    }
    val pending = synchronized(lock) {
      val queued = pendingPresses.toList()
      pendingPresses.clear()
      queued
    }
    Log.d(TAG, "[tap] flushPendingPressesIfReady: flushing ${pending.size} pending press(es)")
    pending.forEach(callback)
  }

  private fun flushPendingActionsIfReady() {
    val callback = onNotificationAction ?: return
    if (!ready) {
      return
    }
    val pending = synchronized(lock) {
      val queued = pendingActions.toList()
      pendingActions.clear()
      queued
    }
    Log.d(TAG, "[action] flushPendingActionsIfReady: flushing ${pending.size} pending action(s)")
    pending.forEach(callback)
  }

  fun fetchToken(): String {
    val context = application
      ?: throw PushSignalException("E_NOT_INITIALIZED", "PushSignal is not initialized")

    ensurePlayServices(context)

    PushSignalDevLog.add(DevLogLevel.INFO, "token", "Запрос FCM-токена")
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
      PushSignalDevLog.add(DevLogLevel.ERROR, "token", "FCM вернул пустой токен")
      throw PushSignalException("E_FCM_TOKEN", "Firebase returned an empty FCM token")
    }

    PushSignalDevLog.add(DevLogLevel.SUCCESS, "token", "FCM-токен получен", maskToken(token))
    return token
  }

  /**
   * Waits for Google Play services to become ready, then fails with an actionable
   * message only when they truly cannot serve FCM (for example a China-only ROM
   * without GMS). On slow or older devices `isGooglePlayServicesAvailable` can
   * transiently report Play services as missing/updating right after a cold start,
   * so transient statuses are retried with backoff instead of failing fast. The
   * message names the provider the device should use instead, so the cause is
   * obvious in React Native logs.
   */
  private fun ensurePlayServices(context: Context) {
    var delayMs = PLAY_SERVICES_INITIAL_RETRY_DELAY_MS
    var lastStatus = ConnectionResult.SUCCESS

    for (attempt in 1..PLAY_SERVICES_MAX_ATTEMPTS) {
      val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
      if (status == ConnectionResult.SUCCESS) {
        return
      }
      lastStatus = status
      if (attempt == PLAY_SERVICES_MAX_ATTEMPTS || !isTransientPlayServicesStatus(status)) {
        break
      }
      Log.w(
        TAG,
        "Play services not ready (code $status), attempt $attempt/$PLAY_SERVICES_MAX_ATTEMPTS. Retrying in ${delayMs}ms"
      )
      try {
        Thread.sleep(delayMs)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        break
      }
      delayMs = (delayMs * 2).coerceAtMost(PLAY_SERVICES_MAX_RETRY_DELAY_MS)
    }

    val status = lastStatus
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
    PushSignalDevLog.add(DevLogLevel.ERROR, "provider", "Google Play services недоступны", "$code · $message")
    throw PushSignalException(code, message)
  }

  /**
   * Statuses that commonly clear on their own shortly after a cold start or a Play
   * services update. `SERVICE_DISABLED` and `SERVICE_INVALID` need a user action, so
   * they are not retried and fail immediately.
   */
  private fun isTransientPlayServicesStatus(status: Int): Boolean {
    return status == ConnectionResult.SERVICE_UPDATING ||
      status == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED ||
      status == ConnectionResult.SERVICE_MISSING
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
    PushSignalDevLog.add(DevLogLevel.ERROR, "token", "Не удалось получить FCM-токен", message)
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
    if (!markMessageDelivered(message)) {
      Log.d(TAG, "[push] emitMessage: dropped duplicate id=${message.id}")
      PushSignalDevLog.add(DevLogLevel.WARN, "push", "Пуш подавлен (дубликат)", message.id)
      return
    }
    val inForeground = startedActivityCount > 0 || currentActivity != null
    val where = if (inForeground) "foreground" else "background"
    PushSignalDevLog.add(DevLogLevel.SUCCESS, "push", "Пуш получен ($where)", describe(message))

    // In the foreground we always draw the tray notification for displayable
    // pushes. In the background FCM itself renders `notification` payloads, but a
    // data-only push that carries action buttons is invisible to the system, so
    // the library posts it (with its buttons) instead.
    val shouldPost = message.isDisplayable() &&
      (inForeground || message.buttons.isNotEmpty())
    if (shouldPost) {
      runOnMain { postNotification(message) }
    }
    runOnMain { deliverMessage(message) }
  }

  private fun deliverMessage(message: PushMessage) {
    val callback = onMessage
    if (callback == null || !ready) {
      pendingMessages.add(message)
      return
    }

    try {
      callback(message)
    } catch (_: Throwable) {
      // Ignore listener failures.
    }
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
    } else {
      mainHandler.post(block)
    }
  }

  private fun postNotification(message: PushMessage) {
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

    val requestCode = notificationId(message)
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

    message.buttons.forEachIndexed { index, button ->
      actionPendingIntent(context, message, button, index)?.let { pendingIntent ->
        builder.addAction(0, button.title, pendingIntent)
      }
    }

    val imageUrl = message.image?.takeIf { it.isNotBlank() }
    if (imageUrl == null) {
      manager.notify(requestCode, builder.build())
      logNotificationPosted(message)
      return
    }

    // Fetch the image off the main thread, then post with a big-picture style.
    fetchBitmap(imageUrl) { bitmap ->
      if (bitmap != null) {
        builder.setLargeIcon(bitmap)
        builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap))
      }
      manager.notify(requestCode, builder.build())
      logNotificationPosted(message)
    }
  }

  private fun logNotificationPosted(message: PushMessage) {
    PushSignalDevLog.add(
      DevLogLevel.INFO,
      "push",
      "Показано уведомление",
      message.title ?: message.body
    )
  }

  /**
   * Builds the [PendingIntent] for a notification action button. Uses
   * [PendingIntent.getActivity] so a tap reopens (or cold-starts) the host app
   * instead of only waking a short-lived broadcast receiver: the extras are then
   * preserved by the system and re-read through [handleIntent], which lets the
   * tap survive process death until JS subscribes. Returns null when the host
   * app has no launcher activity to open.
   */
  private fun actionPendingIntent(
    context: Context,
    message: PushMessage,
    button: PushButton,
    index: Int
  ): PendingIntent? {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?: return null
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    launchIntent.putExtra(EXTRA_ACTION_KEY, true)
    launchIntent.putExtra(EXTRA_ACTION_ID, button.id)
    launchIntent.putExtra("google.message_id", message.id ?: UUID.randomUUID().toString())
    message.title?.let { launchIntent.putExtra("gcm.notification.title", it) }
    message.body?.let { launchIntent.putExtra("gcm.notification.body", it) }
    message.data.forEach { (key, value) ->
      launchIntent.putExtra(key, value)
    }
    val requestCode = ((message.id ?: message.title ?: "push") + "#" + button.id + "#" + index).hashCode()
    return PendingIntent.getActivity(
      context,
      requestCode,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  /**
   * Downloads a bitmap from [url] on a background thread. Never throws; a null
   * result means "no image" and the notification is still posted.
   */
  private fun fetchBitmap(url: String, onDone: (android.graphics.Bitmap?) -> Unit) {
    Thread {
      var bitmap: android.graphics.Bitmap? = null
      try {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = true
        connection.useCaches = true
        connection.connect()
        if (connection.responseCode in 200..299) {
          connection.inputStream.use { stream ->
            val factory = android.graphics.BitmapFactory.Options().apply {
              inSampleSize = 2
            }
            bitmap = android.graphics.BitmapFactory.decodeStream(stream, null, factory)
          }
        }
      } catch (_: Throwable) {
        bitmap = null
      }
      runOnMain { onDone(bitmap) }
    }.start()
  }

  private fun emitAction(message: PushMessage) {
    if (!markActionDelivered(message)) {
      Log.d(TAG, "[action] emitAction: dropped duplicate id=${message.id}, action=${message.action}")
      PushSignalDevLog.add(DevLogLevel.WARN, "action", "Кнопка подавлена (дубликат)", message.action)
      return
    }
    // Buttons do not auto-cancel the notification, so dismiss it here the same
    // way a tap on the body does. Do it before buffering so the tray clears even
    // if JS is not ready yet.
    cancelNotification(message)
    PushSignalDevLog.add(DevLogLevel.SUCCESS, "action", "Нажата кнопка уведомления", message.action)
    val listener = onNotificationAction
    Log.d(
      TAG,
      "[action] emitAction: id=${message.id}, action=${message.action}, listenerBound=${listener != null}, ready=$ready"
    )
    if (listener != null && ready) {
      listener(message)
    } else {
      synchronized(lock) {
        pendingActions.addLast(message)
        while (pendingActions.size > MAX_PENDING_PRESSES) {
          pendingActions.removeFirst()
        }
      }
    }
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

  /**
   * Stable notification id for a message. Used both when posting the
   * notification and when cancelling it after a button tap, so the two always
   * refer to the same entry.
   */
  private fun notificationId(message: PushMessage): Int {
    return (message.id ?: message.title ?: "push").hashCode()
  }

  /** Dismisses the posted notification after a button tap. */
  private fun cancelNotification(message: PushMessage) {
    val context = application ?: return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      ?: return
    manager.cancel(notificationId(message))
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
    currentActivity = activity
    registerActivity(activity)
    if (PushSignalDevPanel.isEnabled()) {
      PushSignalDevPanel.attach(activity)
    }
    handleIntent(activity.intent)
  }

  override fun onActivityStarted(activity: Activity) {
    currentActivity = activity
    startedActivityCount += 1
  }

  override fun onActivityResumed(activity: Activity) {
    currentActivity = activity
    if (PushSignalDevPanel.isEnabled()) {
      PushSignalDevPanel.attach(activity)
    }
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
    PushSignalDevPanel.detachIfHost(activity)
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
    if (intent == null) {
      Log.d(TAG, "[tap] handleIntent: null intent")
      return
    }
    val extras = intent.extras
    val keys = extras?.keySet()?.joinToString(",") ?: "none"
    Log.d(
      TAG,
      "[tap] handleIntent: isPushTap=${intent.isPushTap()}, handled=${intent.getBooleanExtra(EXTRA_HANDLED, false)}, extras=[$keys]"
    )
    if (!intent.isPushTap() || intent.getBooleanExtra(EXTRA_HANDLED, false)) {
      return
    }

    intent.putExtra(EXTRA_HANDLED, true)

    if (intent.getBooleanExtra(EXTRA_ACTION_KEY, false)) {
      val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
      if (actionId != null) {
        Log.d(TAG, "[action] handleIntent: emitting action id=$actionId")
        emitAction(intent.toPushMessage().copy(action = actionId))
        return
      }
    }

    Log.d(TAG, "[tap] handleIntent: emitting press")
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
    Log.d(TAG, "[tap] handleNewIntent: activity=${activity?.javaClass?.simpleName}, action=${intent?.action}")
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
      PushSignalDevLog.add(DevLogLevel.SUCCESS, "init", "Firebase уже инициализирован")
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
      PushSignalDevLog.add(DevLogLevel.SUCCESS, "init", "Firebase инициализирован", config.project_id)
      null
    } catch (error: Exception) {
      PushSignalDevLog.add(DevLogLevel.ERROR, "init", "Ошибка инициализации Firebase", error.message)
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
      Log.d(TAG, "[tap] emitPress: dropped duplicate id=${message.id}")
      PushSignalDevLog.add(DevLogLevel.WARN, "tap", "Тап подавлен (дубликат)", message.id)
      return
    }
    PushSignalDevLog.add(DevLogLevel.SUCCESS, "tap", "Тап по уведомлению", describe(message))
    val listener = onNotificationPress
    Log.d(TAG, "[tap] emitPress: id=${message.id}, listenerBound=${listener != null}, ready=$ready")
    if (listener != null && ready) {
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

  /**
   * Same idea as [markPressDelivered], but a single notification can carry
   * several buttons, so the pressed button id is part of the key. Collapses the
   * same button tap seen through several Android entry points.
   */
  private fun markActionDelivered(message: PushMessage): Boolean {
    val messageKey = message.id ?: "${message.title.orEmpty()}|${message.body.orEmpty()}"
    val key = "$messageKey|${message.action.orEmpty()}"
    val now = System.currentTimeMillis()
    synchronized(lock) {
      if (lastActionKey == key && now - lastActionAt < PRESS_DEDUPE_MS) {
        return false
      }
      lastActionKey = key
      lastActionAt = now
    }
    return true
  }

  /**
   * FCM delivers at least once, so the same [RemoteMessage] can reach
   * `onMessageReceived` more than once. Collapse duplicates within a short
   * window so subscribers receive exactly one `onMessage` per notification.
   */
  private fun markMessageDelivered(message: PushMessage): Boolean {
    val key = message.id ?: "${message.title.orEmpty()}|${message.body.orEmpty()}"
    val now = System.currentTimeMillis()
    synchronized(lock) {
      if (lastMessageKey == key && now - lastMessageAt < MESSAGE_DEDUPE_MS) {
        return false
      }
      lastMessageKey = key
      lastMessageAt = now
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
    extras.getBoolean(EXTRA_ACTION_KEY, false) ||
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
      key == EXTRA_PRESS_KEY ||
      key == EXTRA_ACTION_KEY ||
      key == EXTRA_ACTION_ID
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
    data,
    image = data[DATA_KEY_IMAGE],
    buttons = parseButtons(data[DATA_KEY_BUTTONS])
  )
}

private fun AndroidFirebaseConfig.hasRequiredFields(): Boolean {
  return !project_id.isNullOrBlank() &&
    !mobilesdk_app_id.isNullOrBlank() &&
    !current_key.isNullOrBlank() &&
    !project_number.isNullOrBlank()
}

/** One-line human summary of a message for the dev panel. */
private fun describe(message: PushMessage): String {
  val parts = mutableListOf<String>()
  message.title?.takeIf { it.isNotBlank() }?.let { parts += "title=\"$it\"" }
  message.body?.takeIf { it.isNotBlank() }?.let { parts += "body=\"$it\"" }
  parts += "data=${message.data.size}"
  return parts.joinToString(" ")
}

/** Keeps only the tail of a token so it is useful but not fully exposed. */
private fun maskToken(token: String): String {
  return if (token.length <= 10) token else "…${token.takeLast(10)}"
}

private const val EXTRA_HANDLED_KEY = "pushsignal.handled"
private const val EXTRA_PRESS_KEY = "pushsignal.press"
private const val EXTRA_ACTION_KEY = "pushsignal.action"
private const val EXTRA_ACTION_ID = "pushsignal.action.id"
private const val DATA_KEY_IMAGE = "image"
private const val DATA_KEY_BUTTONS = "buttons"

internal fun RemoteMessage.toPushMessage(): PushMessage {
  val data = this.data
  return PushMessage(
    messageId,
    notification?.title ?: data["title"]?.takeIf { it.isNotBlank() },
    notification?.body ?: data["body"]?.takeIf { it.isNotBlank() },
    data,
    image = data[DATA_KEY_IMAGE]?.takeIf { it.isNotBlank() },
    buttons = parseButtons(data[DATA_KEY_BUTTONS])
  )
}

/**
 * A push is displayable when it carries a title, a body or action buttons. Used
 * to decide whether the library must draw a background notification for a
 * data-only push (FCM cannot render buttons in a `notification` payload).
 */
internal fun PushMessage.isDisplayable(): Boolean {
  return !title.isNullOrEmpty() || !body.isNullOrEmpty() || buttons.isNotEmpty()
}

/** Parses the `buttons` data entry (a JSON array string) into button objects. */
internal fun parseButtons(raw: String?): List<PushButton> {
  if (raw.isNullOrBlank()) {
    return emptyList()
  }
  val buttons = mutableListOf<PushButton>()
  try {
    val array = org.json.JSONArray(raw)
    for (i in 0 until array.length()) {
      val item = array.optJSONObject(i) ?: continue
      val id = item.optString("id", "")
      val title = item.optString("title", "")
      if (id.isBlank() || title.isBlank()) {
        continue
      }
      val extras = linkedMapOf<String, String>()
      item.keys().forEach { key ->
        if (key != "id" && key != "title") {
          extras[key] = item.opt(key)?.toString() ?: ""
        }
      }
      buttons += PushButton(id, title, extras)
    }
  } catch (_: Exception) {
    return emptyList()
  }
  return buttons
}
