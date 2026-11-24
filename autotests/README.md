# autotests

Автотесты `sms-verification-service` на Kotlin: Retrofit + OkHttp (API), WireMock (стабы внешних
сервисов), Selenide (web UI), Appium + Selenide (mobile), Allure (отчёт).

## Что уже есть

| Тег | Тест | Что проверяет |
|---|---|---|
| `smoke` | `HealthSmokeTest` | стенд поднят: `/actuator/health` = UP, readiness, SMS API без токена → 401 |
| `api` | `SmsCreateIdempotencyApiTest` | T1: 202/200/409/400/401/422/429, отсутствие `phone`/`message` в ответе, одна строка в БД |
| `stub` | `SmsDispatchRetryAndVerifyTest` | T2: WireMock вместо Twilio — 500→201 (DELIVERED за 2 попытки), 3×fail → NOT_DELIVERED, OTP из Redis → verify + идемпотентность |
| `mobile` | `PhoneVerifyMobileTest` | T5: тот же сценарий в мобильном Chrome на Android-эмуляторе через Appium; запускается только с `-Dappium=true` |
| `ui` | `PhoneVerifyUiTest` | T4: Selenide + Chrome — вход в браузере, уведомление об одобрении, панель «Подтвердите телефон» → код из Redis → `phone_verified_at` в БД, подпись разблокирована |
| `stub` | `TelegramOutboxWireMockTest` | T3: WireMock вместо Telegram Bot API — outbox `PROCESSED` после ретрая, `RETRY`→`DEAD` на 10-й попытке, `PERMANENT`→`DEAD` без вызовов, отсутствие токена бота в БД и артефактах Allure |

## Стенд

```bash
# из корня репозитория: поднимаем только нужные сервисы, порты публикует overlay
docker compose -p sms-autotests -f docker-compose.yml -f docker-compose.autotests.yml \
  up -d --build postgres redis rabbitmq sms-verification-service

docker compose -p sms-autotests ps          # ждём healthy у sms-verification-service
docker compose -p sms-autotests logs -f sms-verification-service
```

`--build` обязателен после правок в `sms-verification-service/`: образ не закреплён в overlay,
compose собирает его из текущих исходников (`sms-autotests-sms-verification-service`) — так
стенд не может остаться на устаревшем образе.

Порты: сервис `:8091`, PostgreSQL `:5432`, Redis `:6379`. WireMock для стабов поднимается
внутри JVM тестов на `:18080` (контейнер ходит к нему через `host.docker.internal`).

Для T4 (UI) нужен весь стенд, включая backend и фронт на `:8080`:

```bash
docker compose -p sms-autotests -f docker-compose.yml -f docker-compose.autotests.yml \
  up -d --build postgres mongo redis rabbitmq mailpit backend sms-verification-service frontend
```

T4 открывает Chrome (Selenium Manager сам подбирает драйвер; нужен установленный Chrome).
По умолчанию браузер headless, окно можно показать так:

```bash
gradle test -Ptags=ui -Dselenide.headless=false
```

## T5 — мобильный Chrome (Appium)

Тот же сценарий и те же Page Objects, что в T4, но драйвер — `AndroidDriver` с мобильным Chrome.
Без флага тест скипается, поэтому обычный прогон не требует ни Appium-сервера, ни эмулятора.

Что нужно на машине: Android-эмулятор с Chrome, `appium` на `:4723` и его драйвер `uiautomator2`
(`appium driver install uiautomator2`).

```bash
gradle test -Ptags=mobile -Dappium=true
```

Значения по умолчанию: `-Dappium.url=http://localhost:4723`, `-Dappium.deviceName=emulator-5554`,
`-Dmobile.baseUrl=http://10.0.2.2:8080` — адрес стенда глазами эмулятора (`10.0.2.2` — loopback
хост-машины). Каждое переопределяется своим `-D`.

### Подготовка окружения (проверено на Windows 11 + Android 15)

```bash
# 1. Appium и драйвер
npm i -g appium
appium driver install uiautomator2

# 2. Android SDK (command-line tools) и образ с предустановленным Chrome
sdkmanager "platform-tools" "emulator" "build-tools;35.0.0" \
  "system-images;android-35;google_apis_playstore;x86_64"
avdmanager create avd -n pixel_api35 -k "system-images;android-35;google_apis_playstore;x86_64" -d pixel_6
emulator -avd pixel_api35          # 10.0.2.2 указывает на хост-машину

# 3. Appium: фича нужна, чтобы принять chromedriver, скачанный вручную
appium --address 127.0.0.1 --port 4723 --allow-insecure="*:chromedriver_allow_any_executable"
```

Chrome из `google_apis_playstore`-образа (у нас 124.x) не всегда получается автоматически
подобрать (`chromedriverAutodownload` не находит драйвер под версию). Тогда chromedriver нужной
версии берётся со страницы Chrome for Testing и путь передаётся в тест:

```bash
gradle test -Ptags=mobile -Dappium=true -Dappium.chromedriver=C:/path/to/chromedriver.exe
```

## Запуск

```bash
cd autotests
gradle test                 # все тесты, помеченные активными тегами
gradle test -Ptags=smoke    # только smoke
gradle test -Ptags=api      # T1
gradle test -Ptags=ui       # T4 (нужен весь стенд + Chrome)
gradle test -PexcludeTags=ui  # только API/стабы, если фронт не поднят
```

Конфигурация через `-D` или env: `-Dsms.baseUrl`, `SMS_BASE_URL`, `FRONTEND_BASE_URL`, `JWT_SECRET`
(по умолчанию читается из `../.env`; в отчёт попадает только факт «секрет задан»).

Токен Telegram-бота стенда — `AUTOTESTS_TELEGRAM_TOKEN` (по умолчанию
`123456:AAautotestsTelegramToken`, то же значение подставляет overlay compose). Из него
строится путь стаба `/bot<token>/sendMessage`; T3 проверяет, что он не попал в артефакты отчёта.

## Отчёт Allure

```bash
gradle test

# CLI Allure скачан локально в .tools (в .gitignore): официальный релиз allure2.
# Однофайловый отчёт (открывается как обычный HTML):
sh .tools/allure-2.35.1/bin/allure generate build/allure-results \
  -o build/allure-report --clean --single-file

# Или живой отчёт с автообновлением:
sh .tools/allure-2.35.1/bin/allure serve build/allure-results --port 5252
```

Результаты прогона — `autotests/build/allure-results` (`environment.properties` и `categories.json`
копируются туда автоматически после тестов).
