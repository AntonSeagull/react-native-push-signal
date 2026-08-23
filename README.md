# react-native-push-signal

English | [Русский](README.ru.md)

Get push permission, device credentials for your server, and listen for incoming notifications or taps. The library does not send pushes — your backend talks to APNs and FCM.

## Installation

```sh
npm install react-native-push-signal react-native-nitro-modules
```

`react-native-nitro-modules` is required because this library uses [Nitro Modules](https://nitro.margelo.com/).

## Usage

```ts
import {
  getCredentials,
  getPermissionStatus,
  initialize,
  onMessage,
  onNotificationPress,
  requestPermission,
} from 'react-native-push-signal';

initialize({
  projectId: 'my-project',
  applicationId: '1:123456789:android:abcd',
  apiKey: 'AIza...',
  gcmSenderId: '123456789',
});

const status = await getPermissionStatus();
const afterRequest = await requestPermission();
const credentials = await getCredentials();
// POST credentials to your server: { platform, token, environment? }

const stopMessages = onMessage((message) => {
  console.log('incoming', message);
});

const stopPress = onNotificationPress((message) => {
  console.log('opened from notification', message);
});
```

`onNotificationPress` also delivers the tap that launched the app if you subscribe after startup.

### What to send to your server

| Platform | `credentials.token` | Server sends through |
| --- | --- | --- |
| iOS | APNs device token (hex) | Apple APNs HTTP/2 |
| Android | FCM registration token | Firebase Cloud Messaging HTTP v1 |

Keep Apple `.p8` keys and the Firebase service account on the server. The app never sees them.

`environment` is iOS-only: `sandbox` for development builds, `production` for TestFlight / App Store.

## iOS setup

1. Enable **Push Notifications** on the app target.
2. Enable **Background Modes → Remote notifications**.
3. Use a physical device. The simulator cannot register with APNs.

The library hooks `UNUserNotificationCenter` and APNs token callbacks, so the host `AppDelegate` does not need extra code.

## Android setup

Remote Android pushes go through FCM. You can either pass the client Firebase fields at runtime or use `google-services.json`.

### Runtime config (no file in the APK)

Call `initialize` with values from `google-services.json` (not a service account):

```ts
initialize({
  projectId: '...',       // project_id
  applicationId: '...',   // mobilesdk_app_id
  apiKey: '...',          // current_key
  gcmSenderId: '...',     // project_number
});
```

All four fields are required for Firebase to start. If any is missing, `initialize` does nothing and does not throw. iOS ignores this call.

Keep the Firebase service account on your server. Do not put it in the app.

### google-services.json

Alternatively, add Firebase to the host app:

1. Place `google-services.json` in `android/app`.
2. Apply the Google Services plugin in `android/app/build.gradle`:

```gradle
apply plugin: "com.google.gms.google-services"
```

3. Add the plugin classpath in `android/build.gradle` / `settings.gradle` as in the [Firebase Android setup](https://firebase.google.com/docs/android/setup).

Without `initialize(...)` or that file, `getCredentials()` throws. Notification permission (`POST_NOTIFICATIONS`) is requested on API 33+.

If the host app already declares its own `FirebaseMessagingService`, only one service can handle `com.google.firebase.MESSAGING_EVENT`. Prefer this library’s service or forward events into it.

## Incoming messages vs taps

- `onMessage` — the push arrived while the app is in the foreground (and Android data messages while the process is alive). The library does not draw a banner.
- `onNotificationPress` — the user opened the notification, including a cold start.
- On iOS, a visible push received in the background or when the app is killed is delivered on tap, not through `onMessage`. That is an OS limit.

## Web

Permission helpers resolve to `denied`. `getCredentials()` throws. Listeners are no-ops.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT
