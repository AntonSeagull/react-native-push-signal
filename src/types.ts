/** JS name cannot be `android`: NDK defines `-DANDROID` and breaks native enums. */
export type PushPlatform = 'ios' | 'android_os';
export type PushEnvironment = 'sandbox' | 'production';

export interface PushCredentials {
  token: string;
  environment?: PushEnvironment;
}

export interface PushButton {
  /** Stable identifier the server puts in the payload, echoed back on tap. */
  id: string;
  /** Label shown on the button. */
  title: string;
  /** Any extra fields the server attached to the button (url, deep link, ...). */
  [key: string]: unknown;
}

export interface PushMessage {
  id?: string;
  title?: string;
  body?: string;
  data: Record<string, string>;
  /** URL of the image to show in the notification (needs `mutable-content` on iOS). */
  image?: string;
  /** Action buttons sent by the server. */
  buttons?: PushButton[];
  /** Id of the pressed button; set only on `onNotificationAction`. */
  action?: string;
}

export type OnMessageListener = (message: PushMessage) => void | Promise<void>;

export type OnNotificationActionListener = (
  message: PushMessage,
  button: PushButton
) => void;

export type PushProvider =
  | 'fcm'
  | 'hms'
  | 'mi_push'
  | 'oppo_push'
  | 'vivo_push'
  | 'meizu_push'
  | 'apns'
  | 'unknown';

export interface PushDiagnostics {
  platform: PushPlatform;
  /** True when Google Play services can serve FCM; false on a ROM without GMS. */
  gmsAvailable: boolean;
  /** Raw GoogleApiAvailability status code. 0 means SUCCESS. */
  gmsStatus: number;
  manufacturer: string;
  brand: string;
  model: string;
  provider: PushProvider;
  /** Human-readable provider name, e.g. "HMS Push Kit". */
  providerName: string;
  /** True only when the provider service app was positively found on the device. */
  providerInstalled?: boolean;
  /** Actionable sentence explaining which service to use instead of the standard one. */
  hint?: string;
}

export interface AndroidFirebaseConfig {
  project_id?: string;
  mobilesdk_app_id?: string;
  current_key?: string;
  project_number?: string;
  /**
   * Shows a native debugging overlay at the bottom of the screen with the
   * notification module's event log (Android only). Collapsed it is a small
   * pill; tap it to expand. Intended for development builds.
   */
  devPanel?: boolean;
}
