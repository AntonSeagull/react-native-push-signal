import NativePushSignal from './NativePushSignal';
import type { NativePushDiagnostics } from './NativePushSignal';
import { PushSignalError } from './PushSignalError';
import type {
  AndroidFirebaseConfig,
  OnMessageListener,
  PushCredentials,
  PushDiagnostics,
  PushEnvironment,
  PushMessage,
  PushPlatform,
  PushProvider,
} from './types';

const messageListeners = new Set<OnMessageListener>();
const pressListeners = new Set<(message: PushMessage) => void>();
let nativeCallbacksBound = false;

/**
 * Messages and taps can arrive before the host app subscribes (the native side
 * flushes its queue as soon as the JS module is evaluated, which happens long
 * before a screen mounts and calls `onMessage` / `onNotificationPress`). Keep
 * them here and replay to the first subscriber instead of dropping them.
 */
const queuedMessages: PushMessage[] = [];
const queuedPresses: PushMessage[] = [];
const MAX_QUEUED_EVENTS = 20;

function enqueue(queue: PushMessage[], message: PushMessage): void {
  queue.push(message);
  if (queue.length > MAX_QUEUED_EVENTS) {
    queue.splice(0, queue.length - MAX_QUEUED_EVENTS);
  }
}

function deliverMessage(message: PushMessage): void {
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
}

function deliverPress(message: PushMessage): void {
  for (const listener of [...pressListeners]) {
    try {
      listener(message);
    } catch {
      // Ignore listener failures so one bad subscriber cannot break delivery.
    }
  }
}

function flushQueuedMessages(): void {
  if (queuedMessages.length === 0 || messageListeners.size === 0) {
    return;
  }
  const queued = queuedMessages.splice(0, queuedMessages.length);
  queued.forEach(deliverMessage);
}

function flushQueuedPresses(): void {
  if (queuedPresses.length === 0 || pressListeners.size === 0) {
    return;
  }
  const queued = queuedPresses.splice(0, queuedPresses.length);
  queued.forEach(deliverPress);
}

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
  token: string;
  environment?: string;
}): PushCredentials {
  return {
    token: raw.token,
    environment: raw.environment as PushEnvironment | undefined,
  };
}

function normalizeDiagnostics(raw: NativePushDiagnostics): PushDiagnostics {
  return {
    platform: raw.platform as PushPlatform,
    gmsAvailable: raw.gmsAvailable,
    gmsStatus: raw.gmsStatus,
    manufacturer: raw.manufacturer,
    brand: raw.brand,
    model: raw.model,
    provider: raw.provider as PushProvider,
    providerName: raw.providerName,
    providerInstalled: raw.providerInstalled ?? undefined,
    hint: raw.hint ?? undefined,
  };
}

function toPushSignalError(
  error: unknown,
  fallbackCode: string
): PushSignalError {
  const candidate = error as { code?: string; message?: string } | undefined;
  return new PushSignalError(
    candidate?.code ?? fallbackCode,
    candidate?.message ?? 'PushSignal request failed',
    error
  );
}

/**
 * Attaches structured diagnostics to an error on a best-effort basis. Failing to
 * collect them must never hide the original, already actionable error.
 */
async function enrichError(error: PushSignalError): Promise<PushSignalError> {
  try {
    const diagnostics = normalizeDiagnostics(
      await NativePushSignal.getDiagnostics()
    );
    error.diagnostics = diagnostics;
    error.provider = diagnostics.provider;
    error.hint = diagnostics.hint;
  } catch {
    // Diagnostics are optional; keep the original error.
  }
  return error;
}

function bindNativeCallbacks() {
  if (nativeCallbacksBound) {
    return;
  }

  nativeCallbacksBound = true;

  NativePushSignal.onMessage((raw) => {
    const message = normalizeMessage(raw);
    if (messageListeners.size === 0) {
      enqueue(queuedMessages, message);
      return;
    }
    deliverMessage(message);
  });

  NativePushSignal.onNotificationPress((raw) => {
    const message = normalizeMessage(raw);
    if (pressListeners.size === 0) {
      enqueue(queuedPresses, message);
      return;
    }
    deliverPress(message);
  });

  NativePushSignal.startListening();
}

export async function initialize(
  config: AndroidFirebaseConfig = {}
): Promise<void> {
  try {
    await NativePushSignal.initialize(config);
  } catch (error) {
    throw await enrichError(toPushSignalError(error, 'E_INIT'));
  }
}

export async function getCredentials(): Promise<PushCredentials> {
  try {
    const raw = await NativePushSignal.getCredentials();
    return normalizeCredentials(raw);
  } catch (error) {
    throw await enrichError(toPushSignalError(error, 'E_CREDENTIALS'));
  }
}

export async function getDiagnostics(): Promise<PushDiagnostics> {
  return normalizeDiagnostics(await NativePushSignal.getDiagnostics());
}

export function onMessage(listener: OnMessageListener): () => void {
  bindNativeCallbacks();
  messageListeners.add(listener);
  flushQueuedMessages();
  return () => {
    messageListeners.delete(listener);
  };
}

export function onNotificationPress(
  listener: (message: PushMessage) => void
): () => void {
  bindNativeCallbacks();
  pressListeners.add(listener);
  flushQueuedPresses();
  return () => {
    pressListeners.delete(listener);
  };
}

bindNativeCallbacks();
