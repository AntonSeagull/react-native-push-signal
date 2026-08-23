package com.margelo.nitro.pushsignal

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.RemoteMessage
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal object PushSignalCenter : Application.ActivityLifecycleCallbacks {
  private const val PREFS_NAME = "pushsignal"
  private const val PREF_ASKED_PERMISSION = "asked_notifications"
  private const val EXTRA_HANDLED = "pushsignal.handled"
  private const val PERMISSION_LAUNCHER_KEY = "com.margelo.nitro.pushsignal.permission"

  private val lock = Any()
  @Volatile private var application: Application? = null
  @Volatile private var currentActivity: Activity? = null
  @Volatile private var permissionLauncher: ActivityResultLauncher<String>? = null
  @Volatile private var onMessage: ((PushMessage) -> Unit)? = null
  @Volatile private var onNotificationPress: ((PushMessage) -> Unit)? = null
  @Volatile private var pendingPress: PushMessage? = null
  private val permissionWaiters = CopyOnWriteArrayList<(PermissionStatus) -> Unit>()
  private val registeredActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
  @Volatile private var pendingFirebaseConfig: AndroidFirebaseConfig? = null

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
      applyFirebaseConfig(app, config)
    }
  }

  fun initialize(config: AndroidFirebaseConfig) {
    if (!config.hasRequiredFields()) {
      return
    }

    val context = application
    if (context == null) {
      pendingFirebaseConfig = config
      return
    }

    applyFirebaseConfig(context, config)
  }

  fun setOnMessage(callback: (PushMessage) -> Unit) {
    onMessage = callback
  }

  fun setOnNotificationPress(callback: (PushMessage) -> Unit) {
    onNotificationPress = callback
    val pending = synchronized(lock) {
      val message = pendingPress
      pendingPress = null
      message
    }
    if (pending != null) {
      callback(pending)
    }
  }

  fun getPermissionStatus(): PermissionStatus {
    val context = application ?: return PermissionStatus.NOTDETERMINED
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return if (notificationsEnabled) {
        PermissionStatus.AUTHORIZED
      } else {
        PermissionStatus.DENIED
      }
    }

    val granted =
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (granted && notificationsEnabled) {
      return PermissionStatus.AUTHORIZED
    }

    val asked =
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_ASKED_PERMISSION, false)
    return if (!asked && !granted) {
      PermissionStatus.NOTDETERMINED
    } else {
      PermissionStatus.DENIED
    }
  }

  fun requestPermission(onResult: (PermissionStatus) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      onResult(getPermissionStatus())
      return
    }

    val activity = currentActivity
    val launcher = permissionLauncher
    if (activity == null || launcher == null) {
      onResult(getPermissionStatus())
      return
    }

    if (
      ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      onResult(getPermissionStatus())
      return
    }

    permissionWaiters.add(onResult)
    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
  }

  fun fetchToken(): String {
    val context = application
      ?: throw IllegalStateException("PushSignal is not initialized")

    try {
      if (FirebaseApp.getApps(context).isEmpty()) {
        FirebaseApp.initializeApp(context)
      }
      FirebaseApp.getInstance()
    } catch (error: IllegalStateException) {
      throw IllegalStateException(
        "Firebase is not configured. Call initialize({ projectId, applicationId, apiKey, gcmSenderId }) or add google-services.json.",
        error
      )
    }

    val task = FirebaseMessaging.getInstance().token
    val token = try {
      Tasks.await(task)
    } catch (error: Exception) {
      throw IllegalStateException(
        "Failed to get an FCM token. Call initialize({ projectId, applicationId, apiKey, gcmSenderId }) or add google-services.json.",
        error
      )
    }

    if (token.isNullOrEmpty()) {
      throw IllegalStateException("Firebase returned an empty FCM token")
    }

    return token
  }

  fun emitMessage(message: PushMessage) {
    onMessage?.invoke(message)
  }

  fun emitMessage(remoteMessage: RemoteMessage) {
    emitMessage(remoteMessage.toPushMessage())
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
    currentActivity = activity
    registerActivity(activity)
    handleIntent(activity.intent)
  }

  override fun onActivityStarted(activity: Activity) = Unit

  override fun onActivityResumed(activity: Activity) {
    currentActivity = activity
    handleIntent(activity.intent)
  }

  override fun onActivityPaused(activity: Activity) {
    if (currentActivity === activity) {
      currentActivity = null
    }
  }

  override fun onActivityStopped(activity: Activity) = Unit

  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

  override fun onActivityDestroyed(activity: Activity) {
    if (currentActivity === activity) {
      currentActivity = null
      permissionLauncher = null
    }
  }

  private fun registerActivity(activity: Activity) {
    if (!registeredActivities.add(activity)) {
      return
    }

    val componentActivity = activity as? ComponentActivity ?: return

    permissionLauncher =
      componentActivity.activityResultRegistry.register(
        PERMISSION_LAUNCHER_KEY,
        componentActivity,
        ActivityResultContracts.RequestPermission()
      ) {
        activity
          .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .edit()
          .putBoolean(PREF_ASKED_PERMISSION, true)
          .apply()
        val status = getPermissionStatus()
        val waiters = permissionWaiters.toList()
        permissionWaiters.clear()
        waiters.forEach { it(status) }
      }

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

  private fun applyFirebaseConfig(context: Context, config: AndroidFirebaseConfig) {
    if (!config.hasRequiredFields() || FirebaseApp.getApps(context).isNotEmpty()) {
      return
    }

    try {
      val options =
        FirebaseOptions.Builder()
          .setProjectId(config.projectId!!.trim())
          .setApplicationId(config.applicationId!!.trim())
          .setApiKey(config.apiKey!!.trim())
          .setGcmSenderId(config.gcmSenderId!!.trim())
          .build()
      FirebaseApp.initializeApp(context, options)
    } catch (_: Exception) {
      // Keep initialize quiet; getCredentials reports a missing Firebase app.
    }
  }

  private fun emitPress(message: PushMessage) {
    val listener = onNotificationPress
    if (listener != null) {
      listener(message)
    } else {
      synchronized(lock) {
        pendingPress = message
      }
    }
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
  return extras.containsKey("google.message_id") ||
    extras.containsKey("google.sent_time") ||
    extras.containsKey("gcm.n.e") ||
    extras.containsKey("gcm.notification.title")
}

private fun Intent.toPushMessage(): PushMessage {
  val extras = extras ?: Bundle()
  val data = linkedMapOf<String, String>()
  for (key in extras.keySet()) {
    if (key.startsWith("google.") || key.startsWith("gcm.") || key == EXTRA_HANDLED_KEY) {
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
  return !projectId.isNullOrBlank() &&
    !applicationId.isNullOrBlank() &&
    !apiKey.isNullOrBlank() &&
    !gcmSenderId.isNullOrBlank()
}

private const val EXTRA_HANDLED_KEY = "pushsignal.handled"

internal fun RemoteMessage.toPushMessage(): PushMessage {
  return PushMessage(
    messageId,
    notification?.title,
    notification?.body,
    data
  )
}
