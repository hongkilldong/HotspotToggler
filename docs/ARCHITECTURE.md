# Архитектура

## Общая схема

```text
┌─────────────────────────────────────────────┐
│ Hotspot Toggler (UID приложения)            │
│                                             │
│  MainActivity / экраны                      │
│  TogglerService (foreground)                │
│  BluetoothMonitor (ACL broadcast)           │
│  TogglerState (StateFlow)                   │
└──────────────────────┬──────────────────────┘
                       │ TCP 127.0.0.1:<port>
                       │ JSON + случайный token
┌──────────────────────▼──────────────────────┐
│ Helper (UID 2000, shell)                    │
│                                             │
│  HelperMain → HelperServer → HotspotOps     │
│  ITetheringConnector / TetheringManager     │
└──────────────────────┬──────────────────────┘
                       │
                       ▼
             Системная точка доступа
```

## Компоненты приложения

### `TogglerService`

Foreground-служба с типом `connectedDevice`. Отвечает за:

- регистрацию Bluetooth-приёмника;
- реакцию на `ACL_CONNECTED` / `ACL_DISCONNECTED`;
- запуск и остановку hotspot через helper;
- задержку автоматического выключения;
- периодический `status` helper (клиенты, скорость, состояние);
- уведомление службы.

### `BluetoothMonitor`

Принимает системные broadcast-события:

- `ACTION_ACL_CONNECTED`;
- `ACTION_ACL_DISCONNECTED`;
- `ACTION_BOND_STATE_CHANGED`;
- `ACTION_STATE_CHANGED`.

Состояние «подключено» также определяется через профили Bluetooth (A2DP, HEADSET, HEARING_AID, LE_AUDIO, GATT), потому что публичный `BluetoothDevice.isConnected(int)` отсутствует в Bluetooth APEX на части устройств Android 16 и выбрасывает `NoSuchMethodError`.

### `HotspotRepository`

Единая точка общения с helper:

- `status` / `ping`;
- `start` / `stop`;
- `forget`;
- `ensureHelper` — проверка версии и перезапуск через ADB;
- расчёт скорости по счётчикам `rx`/`tx`.

### `ActivationService`

Одноразовая настройка доступа:

- mDNS-обнаружение портов adbd;
- сопряжение по 6-значному коду;
- запуск helper через `Kadb`;
- уведомление с полем ввода кода;
- сохранение ADB-ключа и порта.

### Helper

`HelperMain` запускается как shell-процесс:

```text
CLASSPATH=<helper.apk> app_process /system/bin \
  com.hongkilldong.hotspottoggler.helper.HelperMain \
  --package ... --socket ... --token ... --port ... --version 2
```

`HelperServer` слушает TCP только на loopback. Каждый запрос — одна JSON-строка, ответ — одна JSON-строка. Токен сравнивается через `MessageDigest.isEqual`.

Команды:

| Команда | Назначение |
|---|---|
| `ping` | проверка helper |
| `status` | hotspot, SSID, интерфейс, клиенты, счётчики |
| `start` | включить tethering |
| `stop` | выключить tethering |
| `forget` | удалить Bluetooth-сопряжение |
| `shutdown` | остановить helper |

## Почему нужен shell UID

Включение системной точки доступа на Android 16 требует:

```text
android.permission.TETHER_PRIVILEGED
```

Уровень защиты:

```text
signature|privileged
```

Обычному приложению это разрешение выдать нельзя. Shell UID 2000 имеет его. Поэтому helper запускается через adbd.

## Ограничение cgroup

Helper запускается из adb shell и наследует cgroup adbd-сессии. Даже после `setsid`/`nohup` он остаётся в этой cgroup:

```text
/proc/<pid>/cgroup → /system/uid_0/pid_...
```

Когда отладка полностью выключается, Android завершает adbd-сессию и helper останавливается. Это не ошибка приложения, а поведение платформы.

Отсюда гибридный режим:

- Wi-Fi-отладка — запуск/перезапуск helper;
- USB-отладка — удерживает adbd/helper живым;
- после запуска Wi-Fi-отладку можно выключить.

## Почему не используется механизм Headswap

Headswap запускает instrumentation внутри собственного app-процесса. Такой процесс живёт в cgroup приложения и переживает отключение adbd, но работает под UID приложения и не имеет `TETHER_PRIVILEGED`.

Для tethering нужен именно shell/system UID. Механизм Headswap не даёт этой привилегии.

## Состояние и UI

`TogglerState` — единый `StateFlow<UiState>`:

```text
serviceRunning / serviceEnabled
selected Bluetooth device
activation: paired, helperReady, busy, wirelessDebug, usbDebug, port
hotspot: on, starting, ssid, iface, clients, owned, internet
speed
error
```

Экраны читают состояние и обновляют только изменившиеся элементы, чтобы не пересоздавать списки на каждом тике.

## Файлы

| Путь | Назначение |
|---|---|
| `app/src/main/java/.../service/TogglerService.kt` | служба, триггеры, авто-выключение |
| `.../bt/BluetoothMonitor.kt` | ACL и профили Bluetooth |
| `.../activation/ActivationService.kt` | сопряжение и запуск helper |
| `.../activation/AdbController.kt` | Kadb, mDNS, выбор порта |
| `.../helper/HelperMain.kt` | точка входа helper |
| `.../helper/HelperServer.kt` | TCP-протокол |
| `.../helper/HotspotOps.kt` | tethering, клиенты, счётчики |
| `.../ui/` | экраны |
