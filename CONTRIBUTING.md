# Contributing

Спасибо за интерес к проекту.

## Перед изменениями

- Прочитайте [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) и [docs/SECURITY.md](docs/SECURITY.md).
- Не добавляйте root/Shizuku-зависимости без отдельного обсуждения.
- Не коммитьте APK, `dist/`, `local.properties`, `settings*.apk` и временные файлы.
- Для изменений helper-кода увеличьте версию helper (`HelperLauncher.VERSION`, `HelperMain.HELPER_VERSION`).

## Сборка

```text
./gradlew :app:assembleDebug
```

## Pull request

Опишите:

- что изменено;
- как проверено;
- на каком устройстве/версии Android;
- есть ли изменения в helper-протоколе.

## Стиль

- Kotlin, 4 пробела.
- Пользовательские строки — в `values/strings.xml` и `values-ru/strings.xml`.
- UI — через компоненты `ui/Kit.kt`.
- Не логируйте токен helper и содержимое ADB-ключей.

## Лицензия

Отправляя изменения, вы соглашаетесь, что они распространяются под GPL-3.0.
