import type {
  AndroidFirebaseConfig,
  PermissionStatus,
  PushCredentials,
  PushMessage,
} from './PushSignal.nitro';

export function initialize(_config: AndroidFirebaseConfig = {}): void {}

export async function getPermissionStatus(): Promise<PermissionStatus> {
  return 'denied';
}

export async function requestPermission(): Promise<PermissionStatus> {
  return 'denied';
}

export async function getCredentials(): Promise<PushCredentials> {
  throw new Error('Push credentials are not supported on web');
}

export function onMessage(
  _listener: (message: PushMessage) => void
): () => void {
  return () => {};
}

export function onNotificationPress(
  _listener: (message: PushMessage) => void
): () => void {
  return () => {};
}
