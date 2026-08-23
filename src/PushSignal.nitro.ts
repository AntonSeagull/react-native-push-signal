import type { HybridObject } from 'react-native-nitro-modules';

/** JS/C++ name cannot be `android`: NDK defines `-DANDROID` and breaks the generated enum. */
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

export interface AndroidFirebaseConfig {
  project_id?: string;
  mobilesdk_app_id?: string;
  current_key?: string;
  project_number?: string;
}

export interface PushSignal extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  initialize(config: AndroidFirebaseConfig): Promise<void>;
  getCredentials(): Promise<PushCredentials>;
  setOnMessage(callback: (message: PushMessage) => void): void;
  setOnNotificationPress(callback: (message: PushMessage) => void): void;
}
