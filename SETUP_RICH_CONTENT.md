# Настройка картинки и кнопок в уведомлениях

Пошаговое руководство, как подключить rich-уведомления — картинку и кнопки с
произвольным payload — к `react-native-push-signal`.

Общая схема:

- Сервер кладёт в данные пуша ключи `image` (URL картинки) и `buttons`
  (JSON-массив кнопок).
- **Android** — работает сразу, ничего настраивать не нужно.
- **iOS** — нужен отдельный Notification Service Extension и флаг
  `mutable-content: 1` в пейлоаде.

---

## 1. Формат пейлоада

Сервер отправляет в data-часть пуша два ключа:

```jsonc
{
  "image": "https://example.com/pic.jpg",
  "buttons": "[{\"id\":\"like\",\"title\":\"Нравится\",\"url\":\"https://example.com/like\"},{\"id\":\"open\",\"title\":\"Открыть\"}]"
}
```

- `image` — строка-URL. Необязательно.
- `buttons` — JSON-строка-массив кнопок. Каждая кнопка:
  - `id` — уникальный идентификатор (обязательно);
  - `title` — подпись на кнопке (обязательно);
  - любые дополнительные поля (`url`, deep link, action, …) — приходят в JS
    целиком.

Ключи `image` и `buttons` кладутся в data-часть пуша. Для iOS в пейлоаде
дополнительно нужен флаг `mutable-content: 1` (иначе extension не запустится) —
см. [Шаг 3.6](#шаг-36--проверьте-mutable-content).

---

## 2. Android

Настраивать ничего не нужно: картинка показывается как большая картинка
(BigPictureStyle), кнопки — как action-кнопки уведомления. Убедитесь только, что
у приложения есть иконка уведомлений (`ic_notification`), иначе используется
иконка приложения.

---

## 3. iOS

Картинка и кнопки на iOS добавляются через **Notification Service Extension**.
Без него iOS проигнорирует `image` и `buttons`, и пуш придёт как обычный.

### Шаг 3.1 — создайте extension-таргет

В Xcode:

1. `File → New → Target…`.
2. Выберите **Notification Service Extension**.
3. Product Name — например `PushSignalNotificationService`, язык — Objective-C.
4. Нажмите **Finish**. Xcode сам создаст таргет и файл
   `NotificationService.m` (его можно удалить — мы подключим свой).

### Шаг 3.2 — проверьте bundle id

У extension-таргета bundle id должен начинаться с bundle id основного приложения:

```
<ваш.bundle.id>.PushSignalNotificationService
```

Xcode подставляет такой автоматически. Оставляйте как есть.

### Шаг 3.3 — подключите исходники расширения

**Вариант A (рекомендуется) — через CocoaPods.** В `Podfile` добавьте цель
extension:

```ruby
target 'PushSignalNotificationServiceExtension' do
  pod 'PushSignalNotificationServiceExtension', :path => '../node_modules/react-native-push-signal'
end
```

Затем выполните `pod install`.

**Вариант B — вручную.** Удалите шаблонный `NotificationService.m` из таргета и
добавьте в extension-таргет два файла:

```
node_modules/react-native-push-signal/ios/NotificationServiceExtension/NotificationService.h
node_modules/react-native-push-signal/ios/NotificationServiceExtension/NotificationService.m
```

### Шаг 3.4 — проверьте Info.plist extension

В `Info.plist` extension-таргета должна быть запись:

```xml
<key>NSExtension</key>
<dict>
  <key>NSExtensionPointIdentifier</key>
  <string>com.apple.usernotifications.service</string>
  <key>NSExtensionPrincipalClass</key>
  <string>NotificationService</string>
</dict>
```

При создании через шаблон Xcode ставит это сам. Если класс расширения называется
иначе — поправьте `NSExtensionPrincipalClass`.

### Шаг 3.5 — deployment target и signing

- У extension-таргета deployment target должен быть не выше, чем у основного
  приложения.
- Подписывайте extension тем же Apple-аккаунтом/командой, что и приложение.

### Шаг 3.6 — проверьте `mutable-content`

Для пушей с картинкой или кнопками сервер должен слать `aps.mutable-content = 1`,
иначе extension не запустится. Добавьте флаг сами:

```jsonc
{
  "aps": {
    "alert": { "title": "...", "body": "..." },
    "sound": "default",
    "mutable-content": 1
  },
  "image": "https://example.com/pic.jpg",
  "buttons": "[{\"id\":\"like\",\"title\":\"Like\"}]"
}
```

---

## 4. JS — подписка на кнопки

```ts
import { onNotificationAction } from 'react-native-push-signal';

const stop = onNotificationAction((message, button) => {
  // message — всё уведомление (id, title, body, data, image, buttons)
  // button  — нажатая кнопка целиком, включая доп. поля
  if (button.id === 'like') {
    console.log('url кнопки:', button.url);
  }
});

// отписка
// stop();
```

Тап по телу уведомления по-прежнему приходит в `onNotificationPress`, а не в
`onNotificationAction`.

---

## 5. Проверка

- **Android**: отправьте пуш с `image` и `buttons` — увидите картинку и кнопки;
  тап по кнопке вызовет `onNotificationAction`.
- **iOS**: отправьте пуш с `mutable-content: 1`, `image` и `buttons` на физическое
  устройство. Extension качает картинку (нужен доступ к сети у extension) и
  показывает кнопки.

### Частые проблемы

- **Кнопки/картинка не видны на iOS** — не подключён extension-таргет или сервер
  не шлёт `mutable-content: 1`.
- **Картинка не показывается** — extension не смог скачать URL (проверьте
  доступность ссылки и что она отдаёт `image/*`).
- **`onNotificationAction` не срабатывает** — проверьте, что подписка выполнена
  до тапа (при холодном старте событие буферизуется и достанется после
  инициализации).
