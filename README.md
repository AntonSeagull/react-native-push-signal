# react-native-push-signal

English | [Русский](README.ru.md)

Get device credentials for your server and listen for incoming notifications or taps. The library does not send pushes — your backend talks to APNs and FCM. Request notification permission yourself with [`react-native-permissions`](https://github.com/zoontek/react-native-permissions).

## Installation

```sh
npm install react-native-push-signal react-native-nitro-modules
```

`react-native-nitro-modules` is required because this library uses [Nitro Modules](https://nitro.margelo.com/).

## Usage

```ts
import {
  checkNotifications,
  requestNotifications,
  RESULTS,
} from 'react-native-permissions';
import {
  getCredentials,
  initialize,
  onMessage,
  onNotificationPress,
} from 'react-native-push-signal';

await initialize({
  project_id: 'my-project',
  mobilesdk_app_id: '1:123456789:android:abcd',
  current_key: 'AIza...',
  project_number: '123456789',
});

const { status } = await checkNotifications();
if (status !== RESULTS.GRANTED) {
  await requestNotifications(['alert', 'badge', 'sound']);
}

const credentials = await getCredentials();
// POST credentials to your server: { platform, token, environment? }

const stopMessages = onMessage((message) => {
  console.log('incoming', message);
  if (message.data.silent === '1') {
    return;
  }
  return true;
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

The library hooks `UNUserNotificationCenter` at launch (before JS starts) and APNs token callbacks, so the host `AppDelegate` does not need extra code. Foreground pushes only reach JS if this delegate is installed before launch finishes.

## Android setup

Remote Android pushes go through FCM. You can either pass the client Firebase fields at runtime or use `google-services.json`.

### Runtime config (no file in the APK)

Call `initialize` with values from `google-services.json` (not a service account):

```ts
await initialize({
  project_id: '...',
  mobilesdk_app_id: '...',
  current_key: '...',
  project_number: '...',
});
```

All four fields are required for Firebase to start. If any is missing, `initialize` resolves and does nothing. iOS ignores the config and still resolves the promise.

Keep the Firebase service account on your server. Do not put it in the app.

### google-services.json

Alternatively, add Firebase to the host app:

1. Place `google-services.json` in `android/app`.
2. Apply the Google Services plugin in `android/app/build.gradle`:

```gradle
apply plugin: "com.google.gms.google-services"
```

3. Add the plugin classpath in `android/build.gradle` / `settings.gradle` as in the [Firebase Android setup](https://firebase.google.com/docs/android/setup).

Without `initialize(...)` or that file, `getCredentials()` throws.

If the host app already declares its own `FirebaseMessagingService`, only one service can handle `com.google.firebase.MESSAGING_EVENT`. Prefer this library’s service or forward events into it.

## Incoming messages vs taps

| App state | iOS | Android (notification payload) | Android (data-only) |
| --- | --- | --- | --- |
| Foreground | `onMessage`. Banner if a listener returns `true` | `onMessage`. Banner if a listener returns `true` | `onMessage`. Tray only if a listener returns `true` and the payload has title or body |
| Background / killed | System banner. Tap → `onNotificationPress` | System banner. Tap → `onNotificationPress` | No banner. `onMessage` if the process is alive |

- `onMessage` — the push arrived while the app is in the foreground (and Android data messages while the process is alive). Return `true` to show the system banner as usual. No return, `false`, or a throw means no banner. If several listeners are registered, the banner shows when any of them returns `true`.
- `onNotificationPress` — the user opened the notification, including a cold start.
- On iOS, a visible push received in the background or when the app is killed is delivered on tap, not through `onMessage`. That is an OS limit.

If `onMessage` throws or takes longer than about 2 seconds, the library does not show a banner.

## Web

`initialize()` resolves. `getCredentials()` throws. Listeners (`onMessage`, `onNotificationPress`) are no-ops.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT
