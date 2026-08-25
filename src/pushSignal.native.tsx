import { NitroModules } from 'react-native-nitro-modules';
import type {
  AndroidFirebaseConfig,
  OnMessageListener,
  PushCredentials,
  PushMessage,
  PushSignal,
} from './PushSignal.nitro';

const PushSignalHybridObject =
  NitroModules.createHybridObject<PushSignal>('PushSignal');

const messageListeners = new Set<OnMessageListener>();
const pressListeners = new Set<(message: PushMessage) => void>();
let nativeCallbacksBound = false;

function bindNativeCallbacks() {
  if (nativeCallbacksBound) {
    return;
  }

  nativeCallbacksBound = true;

  PushSignalHybridObject.setOnMessage(async (message) => {
    const results = await Promise.all(
      [...messageListeners].map(async (listener) => {
        try {
          return await listener(message);
        } catch {
          return false;
        }
      })
    );

    return results.some((result) => result === true);
  });

  PushSignalHybridObject.setOnNotificationPress((message) => {
    pressListeners.forEach((listener) => listener(message));
  });
}

export function initialize(config: AndroidFirebaseConfig = {}): Promise<void> {
  return PushSignalHybridObject.initialize(config);
}

export function getCredentials(): Promise<PushCredentials> {
  return PushSignalHybridObject.getCredentials();
}

export function onMessage(listener: OnMessageListener): () => void {
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
