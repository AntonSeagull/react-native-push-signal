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

export interface Spec extends TurboModule {
  initialize(config: Object): Promise<void>;
  getCredentials(): Promise<NativePushCredentials>;
  startListening(): void;
  readonly onMessage: CodegenTypes.EventEmitter<NativePushMessage>;
  readonly onNotificationPress: CodegenTypes.EventEmitter<NativePushMessage>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('PushSignal');
