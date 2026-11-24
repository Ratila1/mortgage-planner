# Нагрузочные данные микрозаймов

По умолчанию 10 000 пользователей × 50 заявок = **500 000 заявок**, ~1,6 млн строк журнала `loan_events`
и 500 000 документов кредитной истории в Mongo.

## Как данные попадают в Mongo

Тем же путём, что и живые заявки, — без прямой записи в Mongo из скрипта:

```
load.sql (одна транзакция)                          приложение
users, профили, паспорта, заявки, loan_events  ─┐
outbox_events: loan.history.changed × 500 000  ─┴─► OutboxRelay ─► RabbitMQ ─► LoanConsumers.onHistoryChanged
                                                                               └─► CreditHistorySyncService.sync
                                                                                   (CreditHistoryAssembler → Mongo,
                                                                                    credit_history_synced_event_id)
```

- Скрипт пишет в outbox ровно то, что пишет `OutboxPublisher` для `LoanHistoryChanged`.
- `loan.history.changed` слушает только очередь `loan.credit-history.q` — писем и уведомлений клиентам не будет.
- Заявки и события коммитятся вместе: consumer никогда не увидит событие без заявки.
- Готовность измеряется по `credit_history_synced_event_id` — его ставит consumer после записи в Mongo.

## Запуск

Docker compose должен быть поднят.

```powershell
.\scripts\load-data\load.ps1                          # 500 000
.\scripts\load-data\load.ps1 -Users 2000 -PerUser 50  # 100 000
.\scripts\load-data\load.ps1 -Cleanup                 # удалить из Postgres и Mongo
```

```bash
./scripts/load-data/load.sh            # или: load.sh 2000 50
./scripts/load-data/load.sh --cleanup
```

Скрипт заливает Postgres (на тестовой машине 500 000 заявок — ~40 с), затем раз в 10 секунд показывает,
сколько заявок уже в Mongo. Повторный запуск ничего не дублирует.

Пользователи: `load000001@load.local` …, пароль `Seed12345`, телефоны `+375442000001` …
У каждого 50 заявок раз в 35 дней (~5 лет истории); открытой (SUBMITTED/APPROVED/ACTIVE) может быть только последняя.
1000 сочетаний «фамилия + имя» повторяются с разными отчествами: бюро по одному ФИО чаще ответит 409,
по ФИО с отчеством или по телефону — найдёт одного.

## Скорость доставки в Mongo

С настройками по умолчанию relay отправляет 100 событий раз в секунду — 500 000 дойдут примерно за 1,5 часа.
Для заливки можно временно ускорить (в `.env`, затем `docker compose up -d backend`):

```
APP_OUTBOX_BATCHSIZE=1000
APP_OUTBOX_POLLINTERVAL=100ms
RABBIT_CONSUMERS=4
```

После заливки верните значения по умолчанию (удалите строки).

## Что ещё учесть

- `CreditHistoryReconcileJob` (раз в час) может переотправить до 500 ещё не доехавших заявок — это безопасно,
  синхронизация идемпотентна.
- Отправленные события удаляет `OutboxCleanupJob` через 7 дней.
- Договоров (PDF) и подписей у этих займов нет.
