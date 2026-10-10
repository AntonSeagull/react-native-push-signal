# AGENTS_INSTALL — установка и проверка react-native-push-signal

Инструкция для агента: что сделать в проекте-хосте после добавления зависимости
`react-native-push-signal`, как настроить нативные проекты (Android/iOS) и JS, либо
как проверить, что всё уже настроено.

Область: плагин отдаёт токен устройства и доставляет события `onMessage`,
`onNotificationPress`, `onNotificationAction`. Сами пуши отправляет ваш сервер в
APNs/FCM. Никакой серверный код (PHP и т.п.) здесь не настраивается.

---

## 0. Быстрый чек-лист

Обязательный минимум (базовые пуши и тапы):

- [ ] JS: установлена зависимость, вызывается `initialize(...)` и `getCredentials()`.
- [ ] JS: запрошено разрешение на уведомления (`react-native-permissions`).
- [ ] iOS: включена capability **Push Notifications**.
- [ ] iOS: включён **Background Modes → Remote notifications**.
- [ ] iOS: `pod install` выполнен.
- [ ] Android: есть `google-services.json` **или** вызов `initialize` с 4 полями.
- [ ] Android: уведомления запрашиваются на Android 13+ (`POST_NOTIFICATIONS` — уже в манифесте плагина).

Rich-контент (картинка и кнопки) — дополнительно:

- [ ] Сервер кладёт в data ключи `image` и `buttons`.
- [ ] Android: ничего (работает нативно).
- [ ] iOS: создан и подключён **Notification Service Extension** (см. блок 4).
- [ ] iOS: сервер шлёт `aps.mutable-content = 1`.

---

## 1. JS-часть

1. Проверь, что зависимость установлена:

   ```sh
   npm ls react-native-push-signal
   ```

2. В коде приложения должны быть:

   ```ts
   import {
     getCredentials,
     initialize,
     onMessage,
     onNotificationAction,
     onNotificationPress,
   } from 'react-native-push-signal';
   import {
     checkNotifications,
     requestNotifications,
     RESULTS,
   } from 'react-native-permissions';

   // 1) Инициализация. На Android нужны все 4 поля (из google-services.json),
   //    на iOS конфиг игнорируется, но промис всё равно резолвится.
   await initialize({
     project_id: '...',
     mobilesdk_app_id: '...',
     current_key: '...',
     project_number: '...',
   });

   // 2) Разрешение. Запрашивайте сами через react-native-permissions.
   const { status } = await checkNotifications();
   if (status !== RESULTS.GRANTED) {
     await requestNotifications(['alert', 'badge', 'sound']);
   }

   // 3) Токен на сервер.
   const credentials = await getCredentials(); // { token, environment? }

   // 4) Слушатели.
   onMessage((message) => {});
   onNotificationPress((message) => {});
   onNotificationAction((message, button) => {}); // нажатие кнопки уведомления
   ```

3. **Проверка:** код компилируется (`npx tsc --noEmit` или сборка приложения), в
   рантайме `getCredentials()` не падает.

---

## 2. Android

### Что уже делает плагин сам

Плагин через свой `AndroidManifest.xml` добавляет:

- `FirebaseMessagingService` (`com.pushsignal.PushSignalMessagingService`);
- `ContentProvider` ранней инициализации;
- разрешение `POST_NOTIFICATIONS`.

Ничего вручную в манифест добавлять не нужно.

### Что нужно проверить/сделать

- **Firebase.** Должен быть один из вариантов:
  - `google-services.json` в `android/app/` + плагин `com.google.gms.google-services` в
    `android/app/build.gradle`; **или**
  - вызов `initialize({ project_id, mobilesdk_app_id, current_key, project_number })`.

  Все 4 поля обязательны. Иначе `getCredentials()` бросит ошибку.

- **Свой `FirebaseMessagingService`.** Если в хосте уже объявлен свой сервис на
  `com.google.firebase.MESSAGING_EVENT`, обрабатывать событие может только один.
  Либо оставьте сервис плагина, либо пробрасывайте события в него.

- **Иконка уведомлений (желательно).** Для foreground-уведомлений и кнопок
   используйте монохромную иконку `ic_notification` в `android/app/src/main/res/drawable/`.
   Если её нет — берётся иконка приложения.

- **Кнопки и картинка** работают нативно, доп. настроек нет. Динамическое
  разрешение `POST_NOTIFICATIONS` на Android 13+ запрашивается хостом (см. блок 1).

### Проверка Android

```sh
cd android && ./gradlew :app:compileDebugKotlin
```

Сборка должна пройти без изменений в исходниках хоста.

---

## 3. iOS — базовая настройка

1. В Xcode у app target: **Signing & Capabilities → + Capability → Push Notifications**.
2. **Background Modes → Remote notifications**.
3. Проверять на физическом устройстве (симулятор не регистрируется в APNs).
4. Выполнить `pod install` в `ios/`.

Ничего дописывать в `AppDelegate` не нужно: плагин сам ставит делегат
`UNUserNotificationCenter` до старта JS и подписывается на колбеки APNs-токена.

### Проверка iOS базовой

```sh
cd ios && pod install
grep -c "PushSignal" Podfile.lock
```

`PushSignal` должен присутствовать в `Podfile.lock`. В Xcode таргет app
собирается, capability Push Notifications виден.

---

## 4. iOS — картинка и кнопки (Notification Service Extension)

Нужен, только если используются картинка/кнопки. Без него iOS игнорирует ключи
`image` и `buttons`.

Проверь, нет ли уже таргета extension:

```sh
grep -c "NotificationServiceExtension" ios/App.xcodeproj/project.pbxproj
```

Если `0` — создай (шаги ниже). Если таргет есть — убедись, что bundle id
соответствует шаблону `<app.bundle.id>.NotificationServiceExtension` и он встроен
в приложение (**Embed App Extensions**).

### Шаг 4.1 — создать extension-таргет

Вариант через Xcode:

1. `File → New → Target… → Notification Service Extension`.
2. Product Name — `NotificationServiceExtension`, язык **Objective-C**.
3. Сгенерированный `NotificationService.m` можно удалить — исходники берутся из подa.

Вариант через Ruby (если таргет нужно создавать скриптом, например в white-label
приложении). Ключевые моменты:

- `project.new_target(:app_extension, 'NotificationServiceExtension', :ios, ...)`;
- `INFOPLIST_FILE = NotificationServiceExtension/Info.plist`;
- `PRODUCT_BUNDLE_IDENTIFIER = "<bundle>.NotificationServiceExtension"`;
- встроить в приложение (**Embed App Extensions**, `dst_subfolder_spec = '13'`);
- `app_target.add_dependency(ext_target)`.

Примечание: если bundle id правится скриптом между сборками, основной таргет
заменяй с отрицательным lookahead (`PRODUCT_BUNDLE_IDENTIFIER = (?!\S*NotificationServiceExtension)...`),
а extension — отдельной заменой. Рабочий пример — `update/src/utils.ts` в
`converzilla-app`.

### Шаг 4.2 — Info.plist extension

В таргете extension `Info.plist`:

```xml
<key>NSExtension</key>
<dict>
  <key>NSExtensionPointIdentifier</key>
  <string>com.apple.usernotifications.service</string>
  <key>NSExtensionPrincipalClass</key>
  <string>NotificationService</string>
</dict>
```

### Шаг 4.3 — подключить исходники расширения (pod)

В `ios/Podfile` добавь отдельный target:

```ruby
target 'NotificationServiceExtension' do
  pod 'PushSignalNotificationServiceExtension', :path => '../node_modules/react-native-push-signal'
end
```

Альтернатива (без pod): вручную добавить в extension-таргет файлы
`node_modules/react-native-push-signal/ios/NotificationServiceExtension/NotificationService.{h,m}`.

### Шаг 4.4 — signing и deployment target

- Deployment target extension ≤ deployment target приложения.
- Подпись тем же Apple-аккаунтом/командой (`DEVELOPMENT_TEAM`), что и app.

### Шаг 4.5 — `mutable-content`

Сервер должен слать `aps.mutable-content = 1` для пушей с картинкой/кнопками,
иначе extension не запустится.

### Проверка iOS extension

```sh
cd ios && pod install
grep -c "PushSignalNotificationServiceExtension" Podfile.lock
grep -c "app-extension" App.xcodeproj/project.pbxproj
```

Оба значения должны быть ≥ 1. В Xcode таргет `NotificationServiceExtension`
выбирается и компилируется.

---

## 5. Формат пейлоада для rich-контента

Данные пуша (общие для обеих платформ):

```jsonc
{
  "image": "https://example.com/pic.jpg",
  "buttons": "[{\"id\":\"like\",\"title\":\"Нравится\",\"url\":\"https://example.com/like\"}]"
}
```

- `image` — строка-URL.
- `buttons` — JSON-строка-массив объектов `{ "id", "title", ...любые поля }`.
- На iOS дополнительно `aps.mutable-content = 1`.

В JS кнопка приходит целиком, вместе с доп. полями:

```ts
onNotificationAction((message, button) => {
  // message.buttons — все кнопки, button — нажатая, с её payload
});
```

Подробнее — `SETUP_RICH_CONTENT.md`.

---

## 6. Итоговая проверка (что смотреть)

| Что проверяем | Как |
| --- | --- |
| Зависимость стоит | `npm ls react-native-push-signal` |
| JS собирается | `npx tsc --noEmit` / сборка приложения |
| Android компилируется | `cd android && ./gradlew :app:compileDebugKotlin` |
| iOS поды на месте | `grep PushSignal ios/Podfile.lock` |
| iOS extension (если нужен) | `grep NotificationServiceExtension ios/App.xcodeproj/project.pbxproj` |
| Разрешение запрошено | проверка `checkNotifications()` в рантайме |
| Токен приходит | `await getCredentials()` не падает, возвращает `{ token }` |
| Тап | `onNotificationPress` срабатывает |
| Кнопка (если нужна) | `onNotificationAction(message, button)` срабатывает |

Если что-то из JS-части не настроено (нет `initialize`, разрешения, слушателей) —
это настраивается в приложении; если нет extension на iOS — картинка/кнопки не
появятся, но базовые пуши продолжат работать.
