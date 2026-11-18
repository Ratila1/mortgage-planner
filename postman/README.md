# Postman: локальный сквозной сценарий

Коллекция и окружение для ручной проверки стека `docker compose` (регистрация → подтверждение почты → профиль → подтверждение телефона по SMS).

Файлы:

- `mortgage-planner.postman_collection.json` — 3 папки: `1. Auth (email)`, `2. Profile`, `3. Phone verification`.
- `mortgage-planner.postman_environment.json` — `baseUrl`, `mailpitUrl`, `email`, `password`, `accessToken`, `emailToken`, `mailId`, `otpCode`.

## Импорт

1. Postman → **Import** → перетащить оба файла.
2. Справа сверху выбрать окружение **Mortgage Planner (local)**.
3. Убедиться, что стек поднят: `docker compose ps` (нужны backend, sms-verification-service, notification-service, frontend, mailpit).

## Порядок запуска

Всё идёт через фронтовый nginx `http://localhost:8080`: backend и SMS-сервис наружу не опубликованы.

1. **1. Auth (email) → Register** — `202`. Скрипт сам генерирует новый `email` на каждый запуск: на уже зарегистрированный адрес письмо не придёт.
2. **Mailpit: find confirmation email** — читает `http://localhost:8025/api/v1/search`. Письмо доставляется асинхронно через outbox, поэтому при пустом ответе подождите 1–2 секунды и отправьте запрос ещё раз (повторный запуск `Register` создаст нового пользователя — лучше просто повторить поиск).
3. **Mailpit: read email token** — достаёт `token` из текста письма в переменную `emailToken`.
4. **Verify email (login)** — `200`, `accessToken` сохраняется в окружение, `refresh_token` Postman кладёт в cookie jar.
5. **2. Profile → Save profile** — `200`. Телефон строго в формате `+375291234567`, доход строкой (`"1500.00"`).
6. **3. Phone verification → Request SMS code** — `202`.
7. Прочитать код из БД (см. ниже) и записать 6 цифр в переменную `otpCode`.
8. **Confirm phone** — `204`. Проверка: **2. Profile → Get profile** → `"phoneVerified": true`.

Дополнительно в папке Auth: `Login`, `Refresh`, `Logout` (работают через cookie), `Request login link` → `Mailpit: find login link email` → `Mailpit: read login link token` → `Consume login link` (письмо ищется по теме «Ссылка для входа»).

## Код из SMS

Sim-провайдер не пишет код в лог. Код хранится в тексте SMS-запроса:

```powershell
docker exec nosurprise-postgres-1 psql -U nosurprise -d nosurprise -tAc "select message from sms.sms_request order by created_at desc limit 1"
```

Вывод: `Код подтверждения: 226237` (для номеров вне +375 — `Verification code: ...`).

Полезная диагностика:

```powershell
docker exec nosurprise-postgres-1 psql -U nosurprise -d nosurprise -c "select phone, status, attempts, verify_attempts, max_verify_attempts, left(message, 30) from sms.sms_request order by created_at desc limit 3"
```

## Ограничения, о которые легко споткнуться

- Лимиты backend на IP: register 5/мин, login 10/мин, refresh/logout 30/мин, consume 10/мин, login-link 5/мин. Превышение → `429`.
- SMS-сервис: 3 запроса в минуту на номер, 30 в минуту на IP.
- Код живёт 5 минут; 3 неверных попытки сжигают его.
- Sim-провайдер успешен примерно в 70% случаев. `400` с `"SMS not yet sent, please wait"` — это `NOT_READY`, подождите 15–30 секунд (ретраи 15s/30s) и повторите.
- Ошибки в формате RFC 7807: `{"status":400,"code":"OTP_INVALID","detail":"...","instance":"..."}`.

## Повторный запрос кода

Повторный `POST /api/me/phone/verification` (и запрос кода подписи) разрешён не чаще одного раза
в 60 секунд; раньше — `429 RATE_LIMITED`. На каждый запрос backend генерирует новый
`Idempotency-Key`, поэтому после сгорания кода или истечения 5 минут можно запросить новый код.
Если отправка не удалась (SMS-сервис недоступен или ответил ошибкой), cooldown снимается и запрос
можно повторить сразу.

Чтобы не ждать 60 секунд вручную, можно удалить ключ cooldown в Redis:

```powershell
docker exec nosurprise-redis-1 redis-cli del "phone-otp-cooldown:<userId>"
```

Для подписи договора ключ называется `sign-otp:cooldown:<userId>`.

## Примечания

- Коллекция не запускается в Newman «из коробки»: шаг с кодом из БД ручной.
- Значение `accessToken` — secret-переменная, в экспортированном файле окружения она пустая.
