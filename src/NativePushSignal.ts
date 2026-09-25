import type { CodegenTypes, TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type NativePushMessage = {
  id?: string;
  title?: string;
  body?: string;
  data: Object;
};

export type NativePushCredentials = {
  platform: string;
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
}

export default TurboModuleRegistry.getEnforcing<Spec>('PushSignal');
