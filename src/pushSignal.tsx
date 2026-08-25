import type {
  AndroidFirebaseConfig,
  OnMessageListener,
  PushCredentials,
  PushMessage,
} from './PushSignal.nitro';

export async function initialize(
  _config: AndroidFirebaseConfig = {}
): Promise<void> {}

export async function getCredentials(): Promise<PushCredentials> {
  throw new Error('Push credentials are not supported on web');
}

export function onMessage(_listener: OnMessageListener): () => void {
  return () => {};
}

export function onNotificationPress(
  _listener: (message: PushMessage) => void
): () => void {
  return () => {};
}
