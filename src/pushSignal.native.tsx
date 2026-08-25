import NativePushSignal from './NativePushSignal';
import type {
  AndroidFirebaseConfig,
  OnMessageListener,
  PushCredentials,
  PushEnvironment,
  PushMessage,
  PushPlatform,
} from './types';

const messageListeners = new Set<OnMessageListener>();
const pressListeners = new Set<(message: PushMessage) => void>();
let nativeCallbacksBound = false;

function normalizeMessage(raw: {
  id?: string;
  title?: string;
  body?: string;
  data: Object;
}): PushMessage {
  const data: Record<string, string> = {};
  if (raw.data && typeof raw.data === 'object') {
    for (const [key, value] of Object.entries(
      raw.data as Record<string, unknown>
    )) {
      if (value == null) {
        continue;
      }
      data[key] = typeof value === 'string' ? value : String(value);
    }
  }

  return {
    id: raw.id,
    title: raw.title,
    body: raw.body,
    data,
  };
}

function normalizeCredentials(raw: {
  platform: string;
  token: string;
  environment?: string;
}): PushCredentials {
  return {
    platform: raw.platform as PushPlatform,
    token: raw.token,
    environment: raw.environment as PushEnvironment | undefined,
  };
}

function bindNativeCallbacks() {
  if (nativeCallbacksBound) {
    return;
  }

  nativeCallbacksBound = true;

  NativePushSignal.onMessage((raw) => {
    const message = normalizeMessage(raw);
    for (const listener of [...messageListeners]) {
      try {
        Promise.resolve(listener(message)).then(
          () => undefined,
          () => undefined
        );
      } catch {
        // Ignore listener failures so one bad subscriber cannot break delivery.
      }
    }
  });

  NativePushSignal.onNotificationPress((raw) => {
    const message = normalizeMessage(raw);
    pressListeners.forEach((listener) => listener(message));
  });

  NativePushSignal.startListening();
}

export function initialize(config: AndroidFirebaseConfig = {}): Promise<void> {
  return NativePushSignal.initialize(config);
}

export function getCredentials(): Promise<PushCredentials> {
  return NativePushSignal.getCredentials().then(normalizeCredentials);
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

bindNativeCallbacks();
