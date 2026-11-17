# Разработка

## Требования

- JDK 17+ (проверено на JDK 25).
- Android SDK platform 37.
- Gradle wrapper 9.8.2.
- Телефон Android 16 для проверки на устройстве.

## Сборка

```powershell
cd C:\Work\HotspotToggler
.\gradlew.bat :app:assembleDebug
```

Debug APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Release APK:

```powershell
.\gradlew.bat :app:assembleRelease
```

```text
app\build\outputs\apk\release\app-release.apk
```

Release подписывается debug-ключом для личной установки. Если нужен собственный ключ, добавьте `signingConfigs.release` в `app/build.gradle.kts`.

## Проверка на устройстве

```text
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am force-stop com.hongkilldong.hotspottoggler.debug
adb shell am start -n com.hongkilldong.hotspottoggler.debug/com.hongkilldong.hotspottoggler.MainActivity
```

Логи:

```text
adb logcat | Select-String -Pattern "HotspotToggler|HotspotHelper|HotspotOps"
```

Проверка helper:

```text
adb shell ps -A | Select-String HelperMain
adb shell "tail -c 2000 /data/local/tmp/hotspot-toggler.log"
```

## Основные инварианты

- Выбранное Bluetooth-устройство хранится в `Prefs`.
- `TogglerState` — единственный источник UI-состояния.
- Helper общается только по loopback и только по токену.
- При изменении helper-кода увеличивайте `HelperLauncher.VERSION` и `HelperMain.HELPER_VERSION`.
- Helper запускается из копии APK в `/data/local/tmp`, чтобы переживать обновления приложения.
- Wi-Fi-отладка используется только для запуска/перезапуска helper; USB-отладка удерживает adbd.
- Не добавляйте секреты, `local.properties`, APK и временные файлы в git.

## Стиль

- Kotlin, отступ 4 пробела.
- UI строится программно через `ui/Kit.kt`; новые элементы должны следовать существующей палитре и размерам.
- Тексты — в `res/values/strings.xml` и `res/values-ru/strings.xml`.
- Не добавляйте пользовательские строки прямо в код, кроме диагностических сообщений.
- Для пользовательских ошибок используйте `AppLog.e` и понятный текст в UI.

## CI

`.github/workflows/android.yml` собирает debug APK на GitHub Actions и загружает artifact.

## Релиз

1. Обновите `versionCode` / `versionName` в `app/build.gradle.kts`.
2. Обновите `CHANGELOG.md`.
3. Соберите release APK.
4. Проверьте установку и основные сценарии.
5. Создайте тег `vX.Y` и GitHub Release с APK и SHA-256.
