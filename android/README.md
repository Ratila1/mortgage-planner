# android/ — мобильная проверка сайта в Android-эмуляторе

Отдельный Gradle-проект (не входит в сборку бэкенда и фронтенда). Внутри — минимальное
приложение-обёртка над `WebView`, которое открывает сайт, и один инструментальный тест
на Kotlin, проверяющий мобильную вёрстку и реальный расчёт с бэкенда.

## Что проверяет тест

`app/src/androidTest/java/dev/nosurprise/mobile/MobileSiteTest.kt`:

1. страница открылась и React отрисовал `h1`;
2. пришёл ответ `POST /api/calc` (на странице есть «Ежемесячный платёж» и «Переплата банку»);
3. вёрстка мобильная: виден бургер-«Меню», ширина окна 320–600 px;
4. нет горизонтального скролла (`scrollWidth <= innerWidth + 1`);
5. на странице нет `role="alert"` с ошибкой.

Сайт открывается по `http://10.0.2.2:8080/` — это адрес хост-машины из эмулятора,
поэтому нужен запущенный `docker compose up` (nginx на `:8080`).

## Установка Android SDK и эмулятора (Windows)

```powershell
# 1. cmdline-tools
curl.exe -L -o "$env:TEMP\cmdline-tools.zip" `
  https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip
New-Item -ItemType Directory -Force "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools" | Out-Null
tar -xf "$env:TEMP\cmdline-tools.zip" -C "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools"
Move-Item "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\cmdline-tools" `
  "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest"

# 2. пакеты
$sdk = "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat"
1..20 | ForEach-Object { "y" } | & $sdk --licenses
& $sdk "platform-tools" "emulator" "platforms;android-36" "build-tools;36.0.0" `
  "system-images;android-36;google_apis;x86_64"

# 3. AVD
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat" `
  create avd -n mobile_site_api36 -k "system-images;android-36;google_apis;x86_64" -d pixel_6
```

Путь к SDK прописан в `local.properties` (файл машинно-зависимый и в git не попадает).
Если SDK в другом месте — поправьте `sdk.dir` или задайте `ANDROID_HOME`.

## Запуск

```powershell
# окружение сайта
docker compose up -d

# эмулятор (без окна — для CI/агента; уберите -no-window, чтобы видеть экран)
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd mobile_site_api36 `
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect

# ждём загрузки
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" wait-for-device

# тест
cd android
.\gradlew.bat :app:connectedDebugAndroidTest
```

Отчёт: `android/app/build/reports/androidTests/connected/debug/index.html`.
Остановить эмулятор: `adb emu kill`.

Требуется аппаратное ускорение (WHPX/Hyper-V на Windows или KVM на Linux) —
проверка: `emulator -accel-check`.
