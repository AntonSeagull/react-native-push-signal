package com.pushsignal

data class PushMessage(
  val id: String?,
  val title: String?,
  val body: String?,
  val data: Map<String, String>,
)

data class AndroidFirebaseConfig(
  val project_id: String?,
  val mobilesdk_app_id: String?,
  val current_key: String?,
  val project_number: String?,
)
