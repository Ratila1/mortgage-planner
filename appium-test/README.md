# appium-test/ — базовый сценарий на Appium + Selenide

Отдельный JVM-проект на Kotlin. Тест запускает **Chrome в Android-эмуляторе через Appium**,
а действия и проверки делает через **Selenide** (`selenide-appium`).

## Что делает тест

`src/test/kotlin/dev/nosurprise/mobile/AppiumBaseScenarioTest.kt`:

1. открывает `http://10.0.2.2:8080/`;
2. проверяет, что страница и первый расчёт отрисовались;
3. меняет поля (цена 300000, взнос 60000, срок 20 лет, ставка 10%) через нативные сеттеры React;
4. дожидается пересчёта, проверяет платёж, переплату, Recharts-график и таблицу платежей;
5. нажимает кнопку «Развернуть всё» (Selenide `click`) и скроллит страницу;
6. сохраняет 4 скриншота: `01-home`, `02-result`, `03-chart`, `04-schedule`.

## Подготовка

Нужны: запущенный `docker compose up -d`, запущенный эмулятор `mobile_site_api36` и
запущенный сервер Appium.

```powershell
# Appium 2+/4 и драйвер Android
npm install -g appium@4
appium driver install uiautomator2

# Appium передаёт SDK-путь драйверу через переменные окружения
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# ChromeDriver под версию Chrome в эмуляторе (смотрите chrome://version в эмуляторе)
# качается с https://googlechromelabs.github.io/chrome-for-testing/
$env:CHROMEDRIVER_PATH = "$env:LOCALAPPDATA\Android\Sdk\chromedriver\chromedriver.exe"

# сервер Appium — держать запущенным в отдельном терминале
appium --port 4723
```

На этой Machine `ANDROID_HOME` и `CHROMEDRIVER_PATH` уже выставлены, Appium-сервер и
эмулятор запущены — достаточно шага «Запуск».

Переопределить адрес сервера/устройство можно переменными `APPIUM_URL` (по умолчанию
`http://127.0.0.1:4723/`) и `ANDROID_DEVICE` (по умолчанию `emulator-5554`).

## Запуск

```powershell
cd appium-test
.\gradlew.bat test
```

- Отчёт: `appium-test/build/reports/tests/test/index.html`
- Скриншоты: `appium-test/build/reports/screenshots/*.png`
- Если Chrome в эмуляторе другой версии — положите подходящий `chromedriver.exe` и обновите `CHROMEDRIVER_PATH`.
