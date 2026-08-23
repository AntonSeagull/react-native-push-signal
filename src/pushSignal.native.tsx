import { NitroModules } from 'react-native-nitro-modules';
import type {
  AndroidFirebaseConfig,
  PermissionStatus,
  PushCredentials,
  PushMessage,
  PushSignal,
} from './PushSignal.nitro';

const PushSignalHybridObject =
  NitroModules.createHybridObject<PushSignal>('PushSignal');

const messageListeners = new Set<(message: PushMessage) => void>();
const pressListeners = new Set<(message: PushMessage) => void>();
let nativeCallbacksBound = false;

function bindNativeCallbacks() {
  if (nativeCallbacksBound) {
    return;
  }

  nativeCallbacksBound = true;

  PushSignalHybridObject.setOnMessage((message) => {
    messageListeners.forEach((listener) => listener(message));
  });

  PushSignalHybridObject.setOnNotificationPress((message) => {
    pressListeners.forEach((listener) => listener(message));
  });
}

export function initialize(config: AndroidFirebaseConfig = {}): void {
  PushSignalHybridObject.initialize(config);
}

export function getPermissionStatus(): Promise<PermissionStatus> {
  return PushSignalHybridObject.getPermissionStatus();
}

export function requestPermission(): Promise<PermissionStatus> {
  return PushSignalHybridObject.requestPermission();
}

export function getCredentials(): Promise<PushCredentials> {
  return PushSignalHybridObject.getCredentials();
}

export function onMessage(
  listener: (message: PushMessage) => void
): () => void {
  bindNativeCallbacks();
  messageListeners.add(listener);
  return () => {
    messageListeners.delete(listener);
  };
}

export function onNotificationPress(
  listener: (message: PushMessage) => void
): () => void {
  bindNativeCallbacks();
  pressListeners.add(listener);
  return () => {
    pressListeners.delete(listener);
  };
}
