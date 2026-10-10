# react-native-push-signal

English | [Русский](README.ru.md)

Get device credentials for your server and listen for incoming notifications or taps. The library does not send pushes — your backend talks to APNs and FCM. Request notification permission yourself with [`react-native-permissions`](https://github.com/zoontek/react-native-permissions).

## Installation

```sh
npm install react-native-push-signal
```

> For an agent/developer: step-by-step native setup and verification — see
> [AGENTS_INSTALL.md](AGENTS_INSTALL.md).

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
  onNotificationAction,
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
// POST credentials to your server: { token, environment? }

const stopMessages = onMessage((message) => {
  console.log('incoming', message);
});

const stopPress = onNotificationPress((message) => {
  console.log('opened from notification', message);
});

const stopAction = onNotificationAction((message, button) => {
  // button — the pressed button, including its extra fields
  console.log('button pressed', button.id, button);
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

### Images and buttons: Notification Service Extension

Rich images and dynamic action buttons on iOS are added through a Notification Service Extension. Without it, iOS ignores `image` and `buttons` and the push arrives as a plain notification.

1. In Xcode create a **Notification Service Extension** target (`File → New → Target`).
2. Give the extension target a bundle id like `<your.bundle.id>.pushsignal`.
3. Add the extension sources. In the Podfile, add to the extension target:

```ruby
target 'YourNotificationServiceExtension' do
  pod 'PushSignalNotificationServiceExtension', :path => '../node_modules/react-native-push-signal'
end
```

Or add `ios/NotificationServiceExtension/NotificationService.{h,m}` to the extension target manually.

4. In the extension target's `Info.plist`, set `NSExtension` → `NSExtensionPointIdentifier` = `com.apple.usernotifications.service` (Xcode sets this when creating from the template).

The server must send `aps.mutable-content = 1` for pushes with an image or buttons, otherwise the extension will not run.

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

Pass `devPanel: true` to show a native debugging overlay — see [Dev panel (Android)](#dev-panel-android).

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

### When it fails on Xiaomi: `SERVICE_NOT_AVAILABLE`

If `getCredentials()` throws `Failed to get an FCM token: SERVICE_NOT_AVAILABLE`, the FCM token could not be obtained. Your server never receives a token, so there is nowhere to send pushes — delivery itself is not the problem.

Before requesting the token the library checks Google Play services and retries a few times with backoff, but some causes can only be fixed on the device:

- **Google account.** The device must be signed into a Google account. FCM does not issue a token without one.
- **Google Play services.** They must be installed, enabled and up to date. On a China-only ROM without GMS, FCM cannot work at all — you need another push provider; see [Detecting a non-Google device](#detecting-a-non-google-device-getdiagnostics-and-pushsignalerror).
- **Network.** Check connectivity and disable VPN and private DNS.
- **MIUI / HyperOS.** Enable autostart and remove battery restrictions: Settings → Apps → your app → Autostart; Battery → No restrictions. Allow background data for the app.

What to look for in the logs:

```sh
adb logcat | grep -iE "SERVICE_NOT_AVAILABLE|FirebaseMessaging|PushSignal"
```

### Detecting a non-Google device: `getDiagnostics()` and `PushSignalError`

When Google Play services cannot serve FCM, the library detects it and tells you which service to use instead — both in the error message and as structured data, so the cause is visible in React Native logs.

`initialize()` and `getCredentials()` reject with `PushSignalError`:

```ts
import { getCredentials, PushSignalError } from 'react-native-push-signal';

try {
  const credentials = await getCredentials();
} catch (error) {
  if (error instanceof PushSignalError) {
    console.warn(error.code); // E_GMS_MISSING, E_FCM_TOKEN, ...
    console.warn(error.message); // "... Use HMS Push Kit (Huawei Push) instead of the standard Google service."
    console.warn(error.provider); // 'hms'
    console.warn(error.hint); // actionable sentence
    console.warn(error.diagnostics); // full device/provider snapshot
  }
}
```

You can also inspect diagnostics without requesting a token:

```ts
import { getDiagnostics } from 'react-native-push-signal';

const diagnostics = await getDiagnostics();
// { platform, gmsAvailable, gmsStatus, manufacturer, brand, model,
//   provider, providerName, providerInstalled?, hint? }
```

Provider suggested when GMS is unavailable:

| Manufacturer                | `provider`   | Service                                 |
| --------------------------- | ------------ | --------------------------------------- |
| HUAWEI / HONOR              | `hms`        | HMS Push Kit (Huawei Push)              |
| Xiaomi / Redmi / POCO       | `mi_push`    | Mi Push (Xiaomi Push)                   |
| OPPO / OnePlus / realme     | `oppo_push`  | OPPO Push (HeyTap)                      |
| vivo / iQOO                 | `vivo_push`  | vivo Push                               |
| Meizu                       | `meizu_push` | Meizu Push                              |
| anything else               | `unknown`    | generic “use another provider”          |

`providerInstalled` is `true` only when the provider service app was positively found on the device (`com.huawei.hwid`, `com.xiaomi.xmsf`); otherwise it is omitted. iOS always reports `provider: 'apns'`. The library never logs for you — pass the data wherever you need.

Error codes: `E_GMS_MISSING`, `E_GMS_DISABLED`, `E_GMS_UPDATE_REQUIRED`, `E_GMS_INVALID`, `E_GMS_UNAVAILABLE`, `E_FCM_TOKEN`, `E_FIREBASE_CONFIG`, `E_NOT_INITIALIZED`.

## Dev panel (Android)

Pass `devPanel: true` to `initialize` to show a native overlay at the bottom of the screen with the notification module's event log:

```ts
await initialize({
  project_id: '...',
  mobilesdk_app_id: '...',
  current_key: '...',
  project_number: '...',
  devPanel: true,
});
```

- Collapsed it is a small pill (`PushSignal · N`) with a status dot — green when the last event succeeded, red on an error.
- Tap the pill to expand a scrollable log; use **Копировать / Очистить / Свернуть** in the header. New events auto-scroll.
- It records: initialization, FCM token requests and results, incoming pushes (with foreground/background), the foreground notification, taps, duplicate taps, listener binding and errors (with the provider hint).
- Events are buffered in memory from process start, so a push that arrives before `initialize` — or from the background `FirebaseMessagingService` — is still visible once the panel is enabled. History is not persisted across process restarts.

The panel uses only platform views, adds no dependencies and is a no-op on iOS and web. Intended for development builds.

## Incoming messages vs taps

| App state | iOS | Android (notification payload) | Android (data-only) |
| --- | --- | --- | --- |
| Foreground | `onMessage` + system banner | `onMessage` + tray (if title/body) | `onMessage` + tray (if title/body) |
| Background / killed | System banner. Tap → `onNotificationPress` | System banner. Tap → `onNotificationPress` | No banner. `onMessage` if the process is alive |

- `onMessage` — the push arrived while the app is in the foreground (and Android data messages while the process is alive). Listeners only receive the payload; they do not control the banner.
- `onNotificationPress` — the user opened the notification, including a cold start.
- `onNotificationAction` — the user tapped a notification action button. Receives the full message and the pressed button.
- On iOS, a visible push received in the background or when the app is killed is delivered on tap, not through `onMessage`. That is an OS limit.
- On Android duplicate deliveries are collapsed: the same incoming message within 500 ms and the same tap within 2 s are emitted to subscribers once. On a cold start the tap is buffered until the push module is initialized, then delivered with the tapped notification's payload.

## Images and action buttons

> Step-by-step setup (especially the iOS extension) — see [SETUP_RICH_CONTENT.md](SETUP_RICH_CONTENT.md).

The server can attach an image and buttons with arbitrary payload. Payload format (both platforms):

```jsonc
// push data:
{
  "image": "https://example.com/pic.jpg",         // image URL
  "buttons": "[{\"id\":\"like\",\"title\":\"Like\",\"url\":\"https://example.com/like\"},{\"id\":\"open\",\"title\":\"Open\"}]"
}
```

- `image` — a URL string. Shown as a big picture on Android (BigPictureStyle) and via the extension on iOS.
- `buttons` — a JSON array of buttons. Each button has `id` (unique), `title` (label) and any extra fields (`url`, deep link, action, ...) — they are delivered to JS in full.

Tapping a button fires `onNotificationAction(message, button)`, where `button` is the pressed button with all its fields, and `message.buttons` is the full list of buttons for that notification:

```ts
import { onNotificationAction } from 'react-native-push-signal';

onNotificationAction((message, button) => {
  console.log('message', message);       // full notification (id, title, body, data, buttons)
  console.log('button', button);         // { id: 'like', title: 'Like', url: '...' }
  if (button.id === 'like') {
    // handle this specific button with its payload
  }
});
```

Types:

```ts
export interface PushButton {
  id: string;
  title: string;
  [key: string]: unknown; // any extra fields
}

export interface PushMessage {
  id?: string;
  title?: string;
  body?: string;
  data: Record<string, string>;
  image?: string;
  buttons?: PushButton[];
  action?: string; // pressed button id (only in onNotificationAction)
}
```

iOS specifics:

- Images and payload buttons only work with a connected Notification Service Extension (see [Images and buttons: Notification Service Extension](#images-and-buttons-notification-service-extension)).
- iOS does not pass arbitrary payload through a button — the app only receives the pressed button's `id`. The library reconstructs the full button object from `message.buttons` by `id`, so JS receives the whole button, just like on Android.
- A tap on the notification body (`UNNotificationDefaultActionIdentifier`) still goes to `onNotificationPress`, not `onNotificationAction`.

Android specifics:

- Buttons are drawn on the notification the plugin posts itself. In the foreground it posts for any displayable push; in the background it posts only for a data-only push that carries `buttons`, because the system notification built from an FCM `notification` payload cannot render action buttons. To get buttons in the background, send a data-only message (`priority: high`) with `title`, `body`, `image`, `buttons` in `data`.
- A button tap opens or cold-starts the host app and is re-read from the launch intent, so `onNotificationAction` fires after initialization even when the app was killed — the tap is buffered until JS subscribes. The notification is dismissed on tap, just like a body tap.


## Web

`initialize()` resolves. `getCredentials()` throws. Listeners (`onMessage`, `onNotificationPress`, `onNotificationAction`) are no-ops.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT
