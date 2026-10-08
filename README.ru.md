# react-native-push-signal

[English](README.md) | Русский

Библиотека отдаёт токен устройства для вашего сервера и сообщает о входящем уведомлении или нажатии на него. Сами пуши она не отправляет — в APNs и FCM ходит бэкенд. Разрешение на уведомления запрашивайте сами через [`react-native-permissions`](https://github.com/zoontek/react-native-permissions).

## Установка

```sh
npm install react-native-push-signal
```

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
// POST credentials на сервер: { token, environment? }

const stopMessages = onMessage((message) => {
  console.log('incoming', message);
});

const stopPress = onNotificationPress((message) => {
  console.log('opened from notification', message);
});

const stopAction = onNotificationAction((message, button) => {
  // button — нажатая кнопка целиком, включая её дополнительные поля
  console.log('button pressed', button.id, button);
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

### Картинка и кнопки: Notification Service Extension

Картинка и динамические кнопки на iOS добавляются через Notification Service Extension. Без него iOS проигнорирует `image` и `buttons`, а сам пуш придёт как обычный.

1. В Xcode создайте target **Notification Service Extension** (`File → New → Target`).
2. У extension-таргета укажите bundle id вида `<ваш.bundle.id>.pushsignal`.
3. Подключите исходники расширения. В Podfile добавьте в цель extension:

```ruby
target 'YourNotificationServiceExtension' do
  pod 'PushSignalNotificationServiceExtension', :path => '../node_modules/react-native-push-signal'
end
```

Либо добавьте файлы `ios/NotificationServiceExtension/NotificationService.{h,m}` в extension-таргет вручную.

4. В `Info.plist` extension-таргета задайте `NSExtension` → `NSExtensionPointIdentifier` = `com.apple.usernotifications.service` (Xcode ставит это сам при создании через шаблон).

Сервер должен слать `aps.mutable-content = 1` для пушей с картинкой или кнопками — иначе extension не запустится.

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

Передайте `devPanel: true`, чтобы показать нативную панель отладки — см. [Dev-панель (Android)](#dev-панель-android).

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

### Если не работает на Xiaomi: `SERVICE_NOT_AVAILABLE`

Если `getCredentials()` бросает `Failed to get an FCM token: SERVICE_NOT_AVAILABLE`, значит не удалось получить FCM-токен. Сервер не получает токен и слать пуши некуда — сама доставка здесь ни при чём.

Перед запросом токена библиотека проверяет Google Play services и делает несколько попыток с паузами, но часть причин лечится только на устройстве:

- **Google-аккаунт.** На устройстве должен быть выполнен вход в Google-аккаунт. Без него FCM токен не выдаёт.
- **Google Play services.** Должны быть установлены, включены и обновлены. На китайской прошивке без GMS FCM не заработает в принципе — нужен другой провайдер пушей; см. [Определение не‑Google устройства](#определение-неgoogle-устройства-getdiagnostics-и-pushsignalerror).
- **Сеть.** Проверьте интернет, отключите VPN и приватный DNS.
- **MIUI / HyperOS.** Включите автозапуск и снимите ограничения батареи: Настройки → Приложения → «ваше приложение» → Автозапуск; Батарея → Без ограничений. Разрешите приложению фоновые данные.

Что смотреть в логах:

```sh
adb logcat | grep -iE "SERVICE_NOT_AVAILABLE|FirebaseMessaging|PushSignal"
```

### Определение не‑Google устройства: `getDiagnostics()` и `PushSignalError`

Когда Google Play services не могут обслуживать FCM, библиотека это определяет и подсказывает, какой сервис использовать вместо стандартного — в тексте ошибки и в структурированном виде, чтобы причина была видна в логах React Native.

`initialize()` и `getCredentials()` отклоняются с `PushSignalError`:

```ts
import { getCredentials, PushSignalError } from 'react-native-push-signal';

try {
  const credentials = await getCredentials();
} catch (error) {
  if (error instanceof PushSignalError) {
    console.warn(error.code); // E_GMS_MISSING, E_FCM_TOKEN, ...
    console.warn(error.message); // "... Use HMS Push Kit (Huawei Push) instead of the standard Google service."
    console.warn(error.provider); // 'hms'
    console.warn(error.hint); // готовая подсказка
    console.warn(error.diagnostics); // полный снимок устройства и провайдера
  }
}
```

Диагностику можно получить и без запроса токена:

```ts
import { getDiagnostics } from 'react-native-push-signal';

const diagnostics = await getDiagnostics();
// { platform, gmsAvailable, gmsStatus, manufacturer, brand, model,
//   provider, providerName, providerInstalled?, hint? }
```

Провайдер, который предлагается при недоступных GMS:

| Производитель            | `provider`   | Сервис                             |
| ------------------------ | ------------ | ---------------------------------- |
| HUAWEI / HONOR           | `hms`        | HMS Push Kit (Huawei Push)         |
| Xiaomi / Redmi / POCO    | `mi_push`    | Mi Push (Xiaomi Push)              |
| OPPO / OnePlus / realme  | `oppo_push`  | OPPO Push (HeyTap)                 |
| vivo / iQOO              | `vivo_push`  | vivo Push                          |
| Meizu                    | `meizu_push` | Meizu Push                         |
| остальные                | `unknown`    | общая подсказка «нужен другой»     |

`providerInstalled` равно `true` только если сервис-приложение провайдера действительно найдено на устройстве (`com.huawei.hwid`, `com.xiaomi.xmsf`), иначе поле отсутствует. iOS всегда сообщает `provider: 'apns'`. Библиотека сама ничего не логирует — передавайте данные туда, куда нужно.

Коды ошибок: `E_GMS_MISSING`, `E_GMS_DISABLED`, `E_GMS_UPDATE_REQUIRED`, `E_GMS_INVALID`, `E_GMS_UNAVAILABLE`, `E_FCM_TOKEN`, `E_FIREBASE_CONFIG`, `E_NOT_INITIALIZED`.

## Dev-панель (Android)

Передайте `devPanel: true` в `initialize`, чтобы внизу экрана появилась нативная панель с логом событий модуля уведомлений:

```ts
await initialize({
  project_id: '...',
  mobilesdk_app_id: '...',
  current_key: '...',
  project_number: '...',
  devPanel: true,
});
```

- В свёрнутом виде — маленькая «пилюля» (`PushSignal · N`) со статусной точкой: зелёная, если последнее событие успешно, красная при ошибке.
- Тап по «пилюле» разворачивает прокручиваемый лог; в шапке — **Копировать / Очистить / Свернуть**. Новые события подтягиваются вниз автоматически.
- Записываются: инициализация, запросы и результат получения FCM-токена, приход пуша (foreground/background), показ foreground-уведомления, тапы, отсечённые дубликаты, подключение JS-слушателей и ошибки (с подсказкой по провайдеру).
- События копятся в памяти с запуска процесса, поэтому пуш, пришедший до `initialize` — или из фонового `FirebaseMessagingService`, — тоже будет виден после включения панели. История не сохраняется между перезапусками приложения.

Панель использует только штатные View, не добавляет зависимостей и ничего не делает на iOS и web. Рассчитана на дев-сборки.

## Входящее сообщение и тап

| Состояние | iOS | Android (notification payload) | Android (data-only) |
| --- | --- | --- | --- |
| Foreground | `onMessage` + системный баннер | `onMessage` + tray (если есть title/body) | `onMessage` + tray (если есть title/body) |
| Background / killed | Системный баннер. Тап → `onNotificationPress` | Системный баннер. Тап → `onNotificationPress` | Баннера нет. `onMessage`, если процесс жив |

- `onMessage` — пуш пришёл, пока приложение на переднем плане (и Android data-message, пока процесс жив). Слушатель только получает payload и не управляет баннером.
- `onNotificationPress` — пользователь открыл уведомление, в том числе при холодном старте.
- `onNotificationAction` — пользователь нажал кнопку уведомления. Получает всё сообщение и нажатую кнопку целиком.
- На iOS видимый пуш в фоне или при убитом приложении приходит только в тап, не в `onMessage`. Это ограничение ОС.
- На Android повторные доставки схлопываются: одно и то же входящее сообщение в пределах 500 мс и одно и то же нажатие в пределах 2 с отдаются подписчикам один раз. При холодном старте нажатие буферизуется до инициализации пуш-модуля, а затем доставляется вместе с данными нажатого уведомления.

## Картинка и кнопки в уведомлении

> Пошаговая настройка (особенно iOS-extension) — в [SETUP_RICH_CONTENT.md](SETUP_RICH_CONTENT.md).

Сервер может добавить в пуш картинку и кнопки с произвольным payload. Формат пейлоада (обе платформы):
```jsonc
// данные пуша (data):
{
  "image": "https://example.com/pic.jpg",         // URL картинки
  "buttons": "[{\"id\":\"like\",\"title\":\"Нравится\",\"url\":\"https://example.com/like\"},{\"id\":\"open\",\"title\":\"Открыть\"}]"
}
```

- `image` — строка-URL. На Android показывается как большая картинка (BigPictureStyle), на iOS — через extension.
- `buttons` — JSON-массив кнопок. Каждая кнопка: `id` (уникальный), `title` (подпись) и любые дополнительные поля (`url`, deep link, action и т.д.) — они приходят в JS целиком.

При нажатии кнопки срабатывает `onNotificationAction(message, button)`, где `button` — объект нажатой кнопки со всеми её полями, а `message.buttons` — полный список кнопок этого уведомления:

```ts
import { onNotificationAction } from 'react-native-push-signal';

onNotificationAction((message, button) => {
  console.log('message', message);       // всё уведомление (id, title, body, data, buttons)
  console.log('button', button);         // { id: 'like', title: 'Нравится', url: '...' }
  if (button.id === 'like') {
    // обработка именно этой кнопки, с её payload
  }
});
```

Типы:

```ts
export interface PushButton {
  id: string;
  title: string;
  [key: string]: unknown; // любые дополнительные поля
}

export interface PushMessage {
  id?: string;
  title?: string;
  body?: string;
  data: Record<string, string>;
  image?: string;
  buttons?: PushButton[];
  action?: string; // id нажатой кнопки (только в onNotificationAction)
}
```

Особенности iOS:

- Кнопки и картинка из пейлоада работают **только** с подключённым Notification Service Extension (см. [Картинка и кнопки: Notification Service Extension](#картинка-и-кнопки-notification-service-extension)).
- iOS не передаёт произвольный payload через кнопку — приложение получает лишь `id` нажатой кнопки. Библиотека восстанавливает полный объект кнопки из `message.buttons` по `id`, поэтому в JS вы получаете кнопку целиком, как на Android.
- Нажатие по телу уведомления (`UNNotificationDefaultActionIdentifier`) по-прежнему идёт в `onNotificationPress`, а не в `onNotificationAction`.

## Web

`initialize()` резолвится. `getCredentials()` бросает ошибку. Слушатели (`onMessage`, `onNotificationPress`, `onNotificationAction`) ничего не делают.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT
