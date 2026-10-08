package com.pushsignal

data class PushButton(
  val id: String,
  val title: String,
  /** Extra server-provided fields (url, deep link, ...), all stringified. */
  val extras: Map<String, String> = emptyMap(),
)

data class PushMessage(
  val id: String?,
  val title: String?,
  val body: String?,
  val data: Map<String, String>,
  val image: String? = null,
  val buttons: List<PushButton> = emptyList(),
  /** Id of the pressed button; set only on action events. */
  val action: String? = null,
)

data class AndroidFirebaseConfig(
  val project_id: String?,
  val mobilesdk_app_id: String?,
  val current_key: String?,
  val project_number: String?,
)

data class PushDiagnostics(
  val platform: String,
  val gmsAvailable: Boolean,
  val gmsStatus: Int,
  val manufacturer: String,
  val brand: String,
  val model: String,
  val provider: String,
  val providerName: String,
  val providerInstalled: Boolean?,
  val hint: String?,
)

/**
 * Carries a machine-readable [code] to JavaScript, so the host app can branch on
 * the exact reason (missing GMS, disabled GMS, FCM token failure, ...).
 */
class PushSignalException(
  val code: String,
  message: String,
  cause: Throwable? = null,
) : IllegalStateException(message, cause)
