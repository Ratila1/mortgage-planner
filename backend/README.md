# Ипотека без сюрпризов — Backend

Бэкенд сервиса расчёта ипотеки с калькулятором, аутентификацией, личным кабинетом и админкой.

## Стек технологий

- **Java 25**, **Spring Boot 4.0.0-M4**
- **Spring WebFlux** (реактивный стек)
- **Spring Security** + JWT (access + refresh токены в Redis)
- **OAuth2** (Google, GitHub)
- **PostgreSQL** (R2DBC) — пользователи, роли, предложения, сценарии
- **MongoDB** (Reactive) — графики платежей, история расчётов
- **Redis** (Reactive) — кэш расчётов, refresh-токены, rate limit
- **RabbitMQ** — события изменения ставки, асинхронный PDF-экспорт, DLQ
- **Flyway** — миграции PostgreSQL
- **PDFBox** — генерация PDF
- **Docker Compose** — поднятие всего стека одной командой
- **Actuator + Prometheus** — метрики
- **Testcontainers** — интеграционные тесты

## Архитектура

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │────▶│  Spring     │────▶│  PostgreSQL │
│  (Browser)  │     │  WebFlux    │     │  (R2DBC)    │
└─────────────┘     └──────┬──────┘     └─────────────┘
                           │
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
      ┌─────────┐    ┌───────────┐   ┌──────────┐
      │ MongoDB │    │   Redis   │   │ RabbitMQ │
      │(Schedules)│   │(Cache,    │   │(Events,  │
      └─────────┘    │ Tokens,   │   │ PDF, DLQ)│
                     │ RateLimit)│   └──────────┘
                     └───────────┘
```

## Быстрый старт

### Предварительные требования
- Docker & Docker Compose
- Java 25 (для локальной разработки)
- Gradle (встроенный wrapper)

### Запуск через Docker Compose

```bash
cd radkovich/radkovich-task-99
docker compose up -d
```

Сервисы поднимутся на портах:
- Приложение: http://localhost:8080
- PostgreSQL: localhost:5432
- MongoDB: localhost:27017
- Redis: localhost:6379
- RabbitMQ Management: http://localhost:15672 (guest/guest)
- Mailhog UI: http://localhost:8025
- Actuator Health: http://localhost:8080/actuator/health
- Prometheus Metrics: http://localhost:8080/actuator/prometheus

### Переменные окружения

Создайте файл `.env` в папке модуля:

```bash
# Database
DB_PASSWORD=secure_password

# JWT (минимум 32 символа)
JWT_SECRET=your-very-secure-secret-key-at-least-32-chars-long

# OAuth2 (опционально)
OAUTH2_GOOGLE_CLIENT_ID=your_google_client_id
OAUTH2_GOOGLE_CLIENT_SECRET=your_google_client_secret
OAUTH2_GITHUB_CLIENT_ID=your_github_client_id
OAUTH2_GITHUB_CLIENT_SECRET=your_github_client_secret

# App
APP_BASE_URL=http://localhost:8080
```

### Локальная разработка

```bash
# Из корня репозитория
./gradlew :radkovich:radkovich-task-99:bootRun
```

## API Endpoints

### Калькулятор (без авторизации, rate limit: 10 req/min)

| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/calculator/calculate` | Расчёт графика (аннуитет/дифференцированный) |
| POST | `/api/calculator/early-repayment` | Пересчёт с досрочными погашениями |
| POST | `/api/calculator/floating-rate` | График при росте ставки на N% |

**Пример запроса `/calculate`:**
```json
{
  "propertyPrice": 10000000,
  "downPayment": 2000000,
  "termMonths": 240,
  "rate": 0.12,
  "type": "ANNUITY",
  "earlyRepayments": [
    {"amount": 500000, "month": 12, "mode": "REDUCE_TERM"}
  ]
}
```

### Аутентификация

| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/auth/register` | Регистрация (email, password, name) |
| POST | `/api/auth/login` | Вход (email, password) → access + refresh токены |
| POST | `/api/auth/refresh` | Обновление токенов (refresh token) |
| POST | `/api/auth/logout` | Отзыв refresh токена |
| POST | `/api/auth/confirm` | Подтверждение email (токен из письма) |
| POST | `/api/auth/otp/request` | Запрос OTP кода на email |
| POST | `/api/auth/otp/verify` | Проверка OTP кода и вход |
| GET | `/login/oauth2/code/google` | OAuth2 Google callback |
| GET | `/login/oauth2/code/github` | OAuth2 GitHub callback |

### Профиль пользователя

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/api/me` | Профиль текущего пользователя |
| PUT | `/api/me` | Обновление имени/email |

### Сценарии (требуют авторизации USER)

| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/scenarios` | Сохранить сценарий |
| GET | `/api/scenarios` | Список сценариев пользователя |
| GET | `/api/scenarios/{id}` | Сценарий с графиком |
| DELETE | `/api/scenarios/{id}` | Удалить сценарий |
| GET | `/api/scenarios/compare?id=...&id=...` | Сравнение 2-3 сценариев |

### Админка (требуют роль ADMIN)

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/api/admin/offers` | Список предложений |
| POST | `/api/admin/offers` | Создать предложение |
| PUT | `/api/admin/offers/{id}` | Обновить предложение (при смене ставки → событие в RabbitMQ) |
| DELETE | `/api/admin/offers/{id}` | Удалить предложение |

### PDF экспорт (асинхронный)

| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/pdf/export` | Запуск экспорта графика сценария в PDF |
| GET | `/api/pdf/{taskId}` | Статус/результат экспорта |

### Метрики

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/actuator/health` | Health check |
| GET | `/actuator/prometheus` | Метрики Prometheus |

## Принятые решения

1. **Один Gradle-модуль** — объём задачи позволяет разместить всё в одном модуле.
2. **Реактивный стек (WebFlux, R2DBC, MongoDB Reactive, Redis Reactive)** — соответствует требованиям TASK-99.
3. **JWT в Redis** — refresh-токены хранятся в Redis с TTL, при logout делается revoke, при refresh — ротация.
4. **BigDecimal + ROUND_HALF_UP** — все денежные расчёты строго в BigDecimal с явным округлением.
5. **Flyway для миграций** — стандартная практика для PostgreSQL.
6. **Docker Compose** — всё поднимается одной командой.
7. **PDF асинхронно через RabbitMQ** — тяжёлая операция не блокирует HTTP-поток.
8. **DLQ + Retry** — для обработки ошибок в очередях.
9. **Rate limit на калькулятор** — sliding window в Redis по IP.
10. **Кэш расчётов** — по SHA-256 хэшу параметров в Redis (TTL 5 мин).

## Тестирование

```bash
# Unit тесты
./gradlew :radkovich:radkovich-task-99:test

# Интеграционные тесты (требуют запущенного Docker)
./gradlew :radkovich:radkovich-task-99:integrationTest
```

## Демонстрация работоспособности

1. Зарегистрируйтесь: `POST /api/auth/register`
2. Подтвердите email: `POST /api/auth/confirm` (токен из письма в Mailhog UI)
3. Войдите: `POST /api/auth/login` → получите access/refresh токены
4. Рассчитайте ипотеку: `POST /api/calculator/calculate` (без токена)
5. Сохраните сценарий: `POST /api/scenarios` (с access токеном)
6. Как админ (создайте пользователя с ролью ADMIN в БД) измените ставку: `PUT /api/admin/offers/{id}`
7. Получите уведомление на email (Mailhog UI) с пересчётом

## Лицензия

Образовательный проект Java Lab.