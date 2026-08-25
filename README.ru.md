# react-native-push-signal

[English](README.md) | Русский

Библиотека отдаёт токен устройства для вашего сервера и сообщает о входящем уведомлении или нажатии на него. Сами пуши она не отправляет — в APNs и FCM ходит бэкенд. Разрешение на уведомления запрашивайте сами через [`react-native-permissions`](https://github.com/zoontek/react-native-permissions).

## Установка

```sh
npm install react-native-push-signal react-native-nitro-modules
```

`react-native-nitro-modules` обязателен: библиотека собрана на [Nitro Modules](https://nitro.margelo.com/).

## Использование

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
// POST credentials на сервер: { platform, token, environment? }

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

Если приложение открыли из уведомления до подписки, `onNotificationPress` всё равно отдаст этот тап сразу после `onNotificationPress(...)`.

### Что отправлять на сервер

| Платформа | `credentials.token`     | Куда сервер шлёт пуш             |
| --------- | ----------------------- | -------------------------------- |
| iOS       | APNs device token (hex) | Apple APNs HTTP/2                |
| Android   | FCM registration token  | Firebase Cloud Messaging HTTP v1 |

Ключи Apple `.p8` и service account Firebase остаются на сервере. Приложение их не видит.

`environment` есть только на iOS: `sandbox` для dev-сборок, `production` для TestFlight и App Store.

## Настройка iOS

1. Включите **Push Notifications** у app target.
2. Включите **Background Modes → Remote notifications**.
3. Проверяйте на физическом устройстве. Симулятор не умеет регистрироваться в APNs.

Библиотека сама ставит делегат `UNUserNotificationCenter` на запуске (до JS) и подписывается на колбеки APNs-токена. В `AppDelegate` хоста ничего дописывать не нужно. Без делегата до конца запуска iOS не вызывает `willPresent`, и пуш в foreground не доходит до JS.

## Настройка Android

Удалённые пуши на Android идут через FCM. Можно передать клиентские поля Firebase в рантайме или положить `google-services.json`.

### Конфиг в рантайме (без файла в APK)

Вызовите `initialize` со значениями из `google-services.json` (не из service account):

```ts
await initialize({
  project_id: '...',
  mobilesdk_app_id: '...',
  current_key: '...',
  project_number: '...',
});
```

Нужны все четыре поля. Если чего-то нет, `initialize` резолвится и ничего не делает. На iOS конфиг игнорируется, промис всё равно резолвится.

Service account Firebase оставляйте на сервере. В приложение его класть нельзя.

### google-services.json

Либо подключите Firebase в хост-приложение:

1. Положите `google-services.json` в `android/app`.
2. Подключите плагин Google Services в `android/app/build.gradle`:

```gradle
apply plugin: "com.google.gms.google-services"
```

3. Добавьте classpath плагина в `android/build.gradle` / `settings.gradle` по [инструкции Firebase для Android](https://firebase.google.com/docs/android/setup).

Без `initialize(...)` или этого файла `getCredentials()` бросит ошибку.

Если в хост-приложении уже есть свой `FirebaseMessagingService`, обработать `com.google.firebase.MESSAGING_EVENT` может только один сервис. Оставьте сервис этой библиотеки или пробрасывайте события в него.

## Входящее сообщение и тап

| Состояние | iOS | Android (notification payload) | Android (data-only) |
| --- | --- | --- | --- |
| Foreground | `onMessage`. Баннер, если слушатель вернул `true` | `onMessage`. Баннер, если слушатель вернул `true` | `onMessage`. Tray только если слушатель вернул `true` и в payload есть title или body |
| Background / killed | Системный баннер. Тап → `onNotificationPress` | Системный баннер. Тап → `onNotificationPress` | Баннера нет. `onMessage`, если процесс жив |

- `onMessage` — пуш пришёл, пока приложение на переднем плане (и Android data-message, пока процесс жив). Верните `true`, чтобы показать системный баннер как обычно. Без return, `false` или ошибка — баннера нет. Если слушателей несколько, баннер показывается, когда любой вернул `true`.
- `onNotificationPress` — пользователь открыл уведомление, в том числе при холодном старте.
- На iOS видимый пуш в фоне или при убитом приложении приходит только в тап, не в `onMessage`. Это ограничение ОС.

Если `onMessage` бросил ошибку или не ответил примерно за 2 секунды, баннер не показывается.

## Web

`initialize()` резолвится. `getCredentials()` бросает ошибку. Слушатели (`onMessage`, `onNotificationPress`) ничего не делают.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT
