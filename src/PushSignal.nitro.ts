import type { HybridObject } from 'react-native-nitro-modules';

export type PermissionStatus = 'authorized' | 'denied' | 'notDetermined';
export type PushPlatform = 'ios' | 'android';
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
  projectId?: string;
  applicationId?: string;
  apiKey?: string;
  gcmSenderId?: string;
}

export interface PushSignal extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  initialize(config: AndroidFirebaseConfig): void;
  getPermissionStatus(): Promise<PermissionStatus>;
  requestPermission(): Promise<PermissionStatus>;
  getCredentials(): Promise<PushCredentials>;
  setOnMessage(callback: (message: PushMessage) => void): void;
  setOnNotificationPress(callback: (message: PushMessage) => void): void;
}
