import type { CodegenTypes, TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type NativePushButton = {
  id: string;
  title: string;
  [key: string]: unknown;
};

export type NativePushMessage = {
  id?: string;
  title?: string;
  body?: string;
  data: Object;
  image?: string;
  buttons?: NativePushButton[];
  action?: string;
};

export type NativePushCredentials = {
  token: string;
  environment?: string;
};

export type NativePushDiagnostics = {
  platform: string;
  /** True when Google Play services can serve FCM; false on a ROM without GMS. */
  gmsAvailable: boolean;
  /** Raw GoogleApiAvailability status code. 0 means SUCCESS. */
  gmsStatus: number;
  manufacturer: string;
  brand: string;
  model: string;
  /** Machine-readable provider id: fcm, hms, mi_push, oppo_push, vivo_push, meizu_push, apns, unknown. */
  provider: string;
  /** Human-readable provider name, e.g. "HMS Push Kit". */
  providerName: string;
  /** True when the provider service app was found on the device. */
  providerInstalled: boolean;
  /** Actionable sentence explaining which service to use instead of the standard one. */
  hint?: string;
};

export interface Spec extends TurboModule {
  initialize(config: Object): Promise<void>;
  getCredentials(): Promise<NativePushCredentials>;
  getDiagnostics(): Promise<NativePushDiagnostics>;
  startListening(): void;
  readonly onMessage: CodegenTypes.EventEmitter<NativePushMessage>;
  readonly onNotificationPress: CodegenTypes.EventEmitter<NativePushMessage>;
  readonly onNotificationAction: CodegenTypes.EventEmitter<NativePushMessage>;
}

/**
 * `get`, not `getEnforcing`: a missing native module (not rebuilt, old
 * architecture, Expo Go) must not throw at import time and crash the host app.
 * The wrapper checks for null and degrades to a no-op instead.
 */
export default TurboModuleRegistry.get<Spec>('PushSignal');
