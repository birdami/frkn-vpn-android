# FRKN VPN — Android App

Минимальное VPN-приложение на Hysteria2 через sing-box (libbox).

## Архитектура

```
┌──────────────────────────────────────┐
│           MainActivity               │
│   ┌─────────┐  ┌──────────────┐     │
│   │ Connect │  │ Status text  │     │
│   │  Button │  │              │     │
│   └────┬────┘  └──────────────┘     │
└────────┼────────────────────────────┘
         │
    ┌────▼──────────┐
    │  VpnManager   │ ← координирует всё
    │  - register() │
    │  - connect()  │
    └───┬──────┬────┘
        │      │
   ┌────▼──┐ ┌─▼──────────────┐
   │  API  │ │ VpnTunnelService│
   │Client │ │ (VpnService)    │
   │       │ │                 │
   │ POST  │ │  libbox/        │
   │/register│  sing-box       │
   │GET    │ │  ┌───────────┐  │
   │/config│ │  │ Hysteria2 │  │
   └───────┘ │  │  outbound │  │
             │  └─────┬─────┘  │
             └────────┼────────┘
                      │
                 UDP :443
                      │
              ┌───────▼────────┐
              │   VPN Server   │
              │ 109.234.39.45  │
              └────────────────┘
```

## Сборка

### 1. Получить libbox.aar

**Вариант A — скачать готовый (рекомендуется):**

Скачай из releases sing-box-for-android:
https://github.com/SagerNet/sing-box-for-android/actions

Или собери сам (вариант B).

**Вариант B — собрать из исходников:**

Нужны: Go 1.22+, Android NDK, gomobile.

```bash
# Установить gomobile
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init

# Клонировать sing-box
git clone https://github.com/SagerNet/sing-box.git
cd sing-box

# Собрать libbox.aar
gomobile bind -v -androidapi 26 -tags with_quic,with_utls,with_gvisor \
  -o libbox.aar ./experimental/libbox
```

### 2. Положить libbox.aar

```bash
cp libbox.aar /path/to/project/app/libs/
```

### 3. Собрать APK

```bash
# В Android Studio: Build → Build APK
# Или из командной строки:
./gradlew assembleDebug
```

APK будет в: `app/build/outputs/apk/debug/app-debug.apk`

## Файлы проекта

```
app/src/main/java/com/vpn/app/
├── api/
│   └── ApiClient.kt          # HTTP клиент → riga.baby API
├── service/
│   ├── SingBoxConfig.kt       # Генерация sing-box JSON из API ответа
│   ├── VpnManager.kt          # Оркестрация: регистрация + подключение
│   └── VpnTunnelService.kt    # Android VpnService + libbox
└── ui/
    └── MainActivity.kt        # UI: кнопка Connect
```

## Конфигурация

API URL задаётся в `ApiClient.kt`:
```kotlin
class ApiClient(private val baseUrl: String = "https://riga.baby")
```

## Поток данных

1. **Первый запуск** → `VpnManager.ensureRegistered()` → `POST /api/v1/devices/register`
   → JWT токен сохраняется в SharedPreferences

2. **Нажатие Connect** → `VpnManager.connect()`:
   - `GET /api/v1/devices/config` (с JWT) → получаем Hysteria2 параметры
   - `SingBoxConfig.build(server)` → JSON конфиг для sing-box
   - `VpnTunnelService.start(config)` → запуск VPN

3. **libbox** создаёт TUN интерфейс, поднимает Hysteria2 outbound → трафик идёт через сервер

## Требования

- Android 8.0+ (API 26)
- Android Studio Ladybug+
- JDK 17
