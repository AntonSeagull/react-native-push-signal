/** JS name cannot be `android`: NDK defines `-DANDROID` and breaks native enums. */
export type PushPlatform = 'ios' | 'android_os';
export type PushEnvironment = 'sandbox' | 'production';

export interface PushCredentials {
  platform: PushPlatform;
  token: string;
  environment?: PushEnvironment;
}

export interface PushMessage {
  id?: string;
  title?: string;
  body?: string;
  data: Record<string, string>;
}

export type OnMessageListener = (message: PushMessage) => void | Promise<void>;

export interface AndroidFirebaseConfig {
  project_id?: string;
  mobilesdk_app_id?: string;
  current_key?: string;
  project_number?: string;
}
