<div align="center">

<img src="frontend/public/favicon.svg" width="88" alt="Логотип — домик на зелёном фоне с золотой дверью" />

# ООО «Тмыв денег» — ипотека без сюрпризов

**Честная цена ипотеки на 25 лет — за один экран, без логина и мелкого шрифта.**

Ежемесячный платёж · реальная переплата · полный график · досрочные погашения · стресс-тест ставки

[![CI](https://github.com/Ratila1/mortgage-planner/actions/workflows/ci.yml/badge.svg)](https://github.com/Ratila1/mortgage-planner/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-25-13603f?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.9-6DB33F?logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)
![TypeScript](https://img.shields.io/badge/TypeScript-5.6-3178C6?logo=typescript&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)

</div>

---

Люди берут ипотеку на 25 лет и не понимают, сколько переплатят и что будет, если ставка вырастет.
Сервис быстро и честно показывает цифры: расчёт на `BigDecimal`, график с точностью до копейки,
тест «а если ставка +3 п.п.?». Ядро — модульный монолит; отправка SMS и Telegram-уведомления
вынесены в два небольших сервиса, которые наружу не публикуются.

## ✨ Возможности

| | Фича | Что умеет |
|---|---|---|
| 🧮 | **Калькулятор без логина** | Аннуитетный и дифференцированный платёж, график (таблица + диаграмма), разовые и регулярные досрочные погашения (уменьшить срок / платёж), «сколько сэкономите» |
| 📈 | **Стресс-тест** | Что будет с платежом и переплатой, если ставка вырастет на 1–5 п.п. |
| 👤 | **Аккаунты** | Email + пароль с подтверждением почты, вход по одноразовому коду (OTP), вход через Google/GitHub, JWT (access + refresh в httpOnly-cookie) |
| 🗂 | **Личный кабинет** | Сохранение сценариев, сравнение 2–3 сценариев бок о бок, история расчётов, уведомления |
| 📄 | **PDF-график** | Экспорт графика сценария в PDF — генерация через очередь, файл хранится в GridFS |
| 🛠 | **Админка** | CRUD банковских предложений; смена ставки запускает пересчёт затронутых сценариев и уведомление пользователей («было → стало») через RabbitMQ |

## 🖥 Экраны

| Путь | Что там |
|---|---|
| `/` | калькулятор (без входа) |
| `/login`, `/register`, `/verify-email` | вход, регистрация, подтверждение почты |
| `/scenarios` | сохранённые сценарии + экспорт PDF |
| `/compare` | сравнение 2–3 сценариев |
| `/notifications` | уведомления |
| `/admin/offers` | админка банковских предложений |

## 🚀 Быстрый старт

> Нужны Docker и Docker Compose. Всё поднимается одной командой.

```bash
cp .env.example .env
# JWT_SECRET обязателен: без него compose не поднимется (§7)
# openssl rand -base64 48  или PowerShell: [Convert]::ToBase64String((1..48 | %{Get-Random -Max 256}))
docker compose up --build
```

| Что | Адрес |
|---|---|
| 💚 Приложение (фронт + API) | [http://localhost:8080](http://localhost:8080) |
| 🐰 RabbitMQ UI | [http://localhost:15672](http://localhost:15672) — `nosurprise` / пароль из `.env` (`RABBITMQ_PASSWORD`) |
| 📬 Письма (Mailpit) | [http://localhost:8025](http://localhost:8025) |
| 📊 Метрики (Prometheus) | [http://localhost:9090](http://localhost:9090) — basic auth `METRICS_USER` / `METRICS_PASSWORD` |
| 📈 Дашборды (Grafana) | [http://localhost:3000](http://localhost:3000) — `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` |

<details>
<summary><b>🔑 Демо-доступ и детали запуска</b></summary>

- `.env` необязателен: у всех переменных в `docker-compose.yml` есть значения по умолчанию.
- Старт упорядочен healthcheck-ами: nginx открывает порт только когда Spring Boot полностью поднялся.
- **Демо-админ** создаётся при старте: `admin@nosurprise.local` / `admin12345`
  (меняется через `APP_ADMIN_EMAIL` / `APP_ADMIN_PASSWORD`).
- Обычный пользователь — регистрация на сайте; письмо с подтверждением приходит в Mailpit.
- Чистый старт с пустыми базами: `docker compose down -v`.
- Кнопки «Войти через Google / GitHub» появляются сами, когда бэкенд запущен с профилем `oauth`:
  фронт спрашивает список провайдеров у `GET /api/auth/oauth/providers`.

</details>

<details>
<summary><b>📨 SMS и уведомления</b></summary>

- `sms-verification-service` (порт `8091` внутри docker-сети) — единственный, кто отправляет SMS.
  nginx не проксирует `/api/v1/sms`: снаружи доступен только backend, сервис вызывается по HTTP
  с service-to-service JWT.
- Повторный запрос кода — не чаще одного раза в 60 секунд, иначе `429 RATE_LIMITED`.
  Это касается и подтверждения телефона, и подписи договора.
- Провайдер задаётся `SMS_VERIFY_PROVIDER`: `sim` (по умолчанию), `vonage` или `twilio`.
- В профиле `sim` SMS реально не уходят. Код лежит в `sms.sms_request.message` — по спеке он хранится
  открытым текстом, чтобы переживать ретраи отправки. Посмотреть последний код:

  ```bash
  docker compose exec postgres psql -U nosurprise -d nosurprise \
    -c "select message from sms.sms_request order by created_at desc limit 1"
  ```

- `notification-service` (порт `8090`, тоже только внутри docker-сети) — Telegram-уведомления и 2FA.
  Контракт и текущий объём — в [notification-service/CONTRACT.md](notification-service/CONTRACT.md).

</details>

<details>
<summary><b>🌍 Публичная ссылка через Cloudflare Tunnel</b></summary>

```bash
docker compose --profile tunnel up --build
docker compose logs cloudflared     # найди https://<...>.trycloudflare.com
```

Вход через Google/GitHub работает только на `localhost:8080` или на именованном туннеле:
redirect URI нужно регистрировать заранее.

</details>

### 🔧 Локальная разработка

```bash
docker compose up -d postgres mongo redis rabbitmq mailpit sms-verification-service
cd backend  && gradle bootRun                 # JDK 25
cd frontend && npm install && npm run dev     # Node 22 → http://localhost:5173
```

### 🧪 Тесты

```bash
cd backend                 && gradle build        # юнит + Testcontainers (нужен Docker)
cd sms-verification-service && gradle test integrationTest  # Testcontainers
cd frontend                && npm test && npm run build
```

<details>
<summary><b>🔐 OAuth: вход через Google и GitHub (необязательно)</b></summary>

1. **Google:** [console.cloud.google.com](https://console.cloud.google.com) → APIs & Services → Credentials →
   Create credentials → OAuth client ID → Web application.
   Authorized redirect URI: `http://localhost:8080/login/oauth2/code/google`.
2. **GitHub:** Settings → Developer settings → OAuth Apps → New OAuth App.
   Homepage URL: `http://localhost:8080`, Authorization callback URL: `http://localhost:8080/login/oauth2/code/github`.
3. В `.env`: `GOOGLE_CLIENT_ID/SECRET`, `GITHUB_CLIENT_ID/SECRET`, `SPRING_PROFILES_ACTIVE=oauth`.
   Нужны ключи **обоих** провайдеров, иначе бэкенд не стартует.
4. `docker compose up --build` и открыть именно `http://localhost:8080` (не `:5173`).

Для другого адреса (туннель, домен) поменяй `APP_BASE_URL` и redirect URI в обеих консолях.

**Как это работает:** кнопка ведёт на `/oauth2/authorization/{google|github}`, после входа бэкенд ставит
refresh-cookie и возвращает на `/`, фронт сам получает access-токен. Если GitHub скрывает почту,
бэкенд берёт основной подтверждённый адрес из `/user/emails`. Ошибки возвращают на `/login?oauthError=…`.

</details>

## 🏗 Архитектура

```mermaid
flowchart LR
  U[Браузер] -->|:8080| N[nginx + React]
  N -->|/api /oauth2 /login/oauth2| API[Spring Boot монолит]
  API --> PG[(PostgreSQL<br/>пользователи, предложения, сценарии)]
  API --> MG[(MongoDB<br/>графики, история расчётов, PDF в GridFS)]
  API --> RD[(Redis<br/>кэш, refresh-токены, rate limit)]
  API -->|события| MQ{{RabbitMQ}}
  MQ -->|consumers| API
  MQ -->|verification.requested| NS[notification-service<br/>Telegram, :8090]
  NS -->|Bot API| TG[Telegram]
  NS -->|X-Service-Token| API
  API --> SMTP[SMTP / Mailpit]
  API -->|HTTP + service JWT| SMS[sms-verification-service<br/>:8091]
  SMS -->|vonage / twilio| GW[SMS-шлюз]
  PROM[Prometheus] -.->|/actuator/prometheus| API
  PROM -.->|/actuator/prometheus| SMS
  GR[Grafana] --> PROM
```

**Один хост.** Фронт и бэк доступны по одному адресу, бэкенд наружу порт не публикует — весь трафик
идёт через nginx. Поэтому нет CORS, а refresh-токен лежит в httpOnly-cookie.
**Модульный монолит + два сервиса:** вся бизнес-логика и consumer-ы RabbitMQ живут в одном приложении.
Отдельно вынесены только `sms-verification-service` (SMS и OTP) и `notification-service` (Telegram):
оба слушают только docker-сеть, наружу их порты не публикуются, `/api/v1/sms` через nginx недоступен.

> 📚 Подробно (модули, схемы данных, ключи Redis, топология RabbitMQ, API-контракт):
> **[INSTRUCTION.md](INSTRUCTION.md)** · журнал прогресса и решений — **[HANDOFF.md](HANDOFF.md)**

## 🧱 Стек

| Слой | Технологии |
|---|---|
| Бэкенд | Java 25 (виртуальные потоки), Spring Boot 4.0.9, Spring Security (JWT + OAuth2 client), OpenPDF |
| Данные | PostgreSQL + Flyway, MongoDB + GridFS, Redis |
| Очереди | RabbitMQ (события: пересчёт сценариев, письма, PDF, verification.requested) |
| Сервисы | `sms-verification-service` (SMS/OTP, vonage/twilio/sim), `notification-service` (Telegram) |
| Фронтенд | React 19, Vite, TypeScript, Tailwind CSS 4, React Router 7, Recharts |
| Мониторинг | Prometheus (basic auth на actuator), Grafana с провижинингом датасорса и дашборда |
| Инфраструктура | Docker Compose, nginx как единая точка входа, Cloudflare Tunnel (опционально) |
| CI | GitHub Actions: backend, sms-verification, notification, frontend, Testcontainers, Docker build, branch policy |

## 📁 Структура

```
backend/          Spring Boot, модульный монолит
  calculator/       чистое расчётное ядро (без Spring и БД)
  calc/             расчёт + кэш + rate limit
  auth/             регистрация, JWT, refresh, OAuth2, OTP
  user/             пользователи и роли
  offer/            банковские предложения + кэш
  scenario/         сохранённые сценарии, сравнение, пересчёт
  notification/     уведомления + consumer-ы очередей
  pdf/              генерация PDF-графика
  customer/         профиль, KYC, подтверждение телефона через sms-verification-service
  loan/             заявки, договоры, подпись, журнал событий
  smsclient/        HTTP-клиент sms-verification-service + service JWT
sms-verification-service/  SMS/OTP: создание, отправка, проверка кода, ретраи, rate limit
notification-service/      Telegram-уведомления и 2FA (контракт в CONTRACT.md)
frontend/         React + Vite + Tailwind, отдаётся через nginx
monitoring/       Prometheus-шаблон и Grafana provisioning (датасорс + дашборд)
postman/          коллекция и окружение для newman
INSTRUCTION.md    архитектура и правила для разработчиков и агентов
HANDOFF.md        журнал прогресса и принятых решений
.github/          CI, CODEOWNERS, шаблон PR
```

## 🤝 Разработка

- Ветка `dev` — основная рабочая, фичи идут через `feature/*` → `dev`.
- PR в `main` принимаются **только из `dev`** (проверяет CI job `branch-policy`).
- На каждый PR гоняются: backend и sms-verification-service (юнит-тесты), notification-service (сборка),
  frontend (vitest + сборка), сборка Docker-образов. Testcontainers-тесты backend и sms-verification-service
  запускаются на push в `main`/`dev` и вручную (`workflow_dispatch`).

---

<div align="center">

Сделано с 💚 командой «Тмыв денег» · [Ratila1/mortgage-planner](https://github.com/Ratila1/mortgage-planner)

</div>
