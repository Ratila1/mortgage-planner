# ООО «Тмыв денег» — ипотека без сюрпризов

Сервис показывает честную цену ипотеки на 25 лет: ежемесячный платёж, реальную переплату, полный график
и что будет при досрочных погашениях или росте ставки.

Репозиторий: [Ratila1/mortgage-planner](https://github.com/Ratila1/mortgage-planner)

## Возможности

- **Калькулятор без логина:** аннуитетный и дифференцированный платёж, график, разовые и регулярные досрочные погашения (уменьшить срок / платёж).
- **Аккаунты:** email + пароль с подтверждением почты, вход через Google/GitHub, JWT (access + refresh).
- **Личный кабинет:** сохранение сценариев, сравнение 2–3 сценариев бок о бок.
- **Админка:** CRUD банковских предложений; смена ставки запускает пересчёт сценариев и уведомление пользователей.

## Быстрый старт

Нужны Docker и Docker Compose.

```bash
cp .env.example .env
docker compose up --build
```

| Что | Адрес |
|---|---|
| Приложение (фронт + API) | http://localhost:8080 |
| RabbitMQ UI | http://localhost:15672 (guest / guest) |
| Письма (Mailpit) | http://localhost:8025 |

Публичная ссылка через Cloudflare Tunnel:
```bash
docker compose --profile tunnel up --build
docker compose logs cloudflared     # найди https://<...>.trycloudflare.com
```
Вход через Google/GitHub работает только на `localhost:8080` или на именованном туннеле: redirect URI нужно регистрировать заранее.

### Локальная разработка
```bash
docker compose up -d postgres mongo redis rabbitmq mailpit
cd backend  && gradle bootRun         # JDK 25
cd frontend && npm install && npm run dev   # Node 22, http://localhost:5173
```

### Тесты
```bash
cd backend  && gradle build           # юнит + Testcontainers (нужен Docker)
cd frontend && npm test && npm run build
```

### OAuth (необязательно)
Заполни в `.env` ключи `GOOGLE_*` / `GITHUB_*`, укажи `SPRING_PROFILES_ACTIVE=oauth` и `APP_BASE_URL`.
Redirect URI: `{APP_BASE_URL}/login/oauth2/code/google` и `/github`.

## Архитектура

```mermaid
flowchart LR
  U[Браузер] -->|:8080| N[nginx + React]
  N -->|/api /oauth2| API[Spring Boot монолит]
  API --> PG[(PostgreSQL<br/>пользователи, предложения, сценарии)]
  API --> MG[(MongoDB<br/>графики, история расчётов)]
  API --> RD[(Redis<br/>кэш, refresh-токены, rate limit)]
  API -->|события| MQ{{RabbitMQ}}
  MQ -->|consumers| API
  API --> SMTP[SMTP / Mailpit]
```

Подробно (модули, схемы данных, ключи Redis, топология RabbitMQ, API-контракт, roadmap): **[AGENTS.md](AGENTS.md)**.

## Стек
Java 25 (виртуальные потоки), Spring Boot 4.0.9, Spring Security, PostgreSQL + Flyway, MongoDB, Redis, RabbitMQ,
React + Vite + TypeScript, Docker Compose, GitHub Actions.

## Структура
```
backend/    Spring Boot, модульный монолит (calculator, auth, user, offer, scenario, notification)
frontend/   React + Vite, отдаётся через nginx
AGENTS.md   архитектура и правила для разработчиков и агентов
.github/    CI, CODEOWNERS, шаблон PR
```

##