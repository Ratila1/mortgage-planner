# credit-policy — сервис кредитной политики (скоринг)

Спецификация и все решения — в [CREDIT_POLICY.md](../CREDIT_POLICY.md).

Считает скоринг каждой заявки на микрозайм: класс A1–E3/F, скорбалл 0–400, вероятность невозврата (PD)
и рекомендацию `RECOMMEND` / `REVIEW` / `AUTO_REJECT`. Статусы заявок меняет монолит, сервис только советует.

```
монолит: заявка SUBMITTED ── outbox ── loan.scoring.requested ──► credit-policy
                                                                   стоп-факторы → факторы → score → класс → PD
монолит: итог в заявке ◄──────────── loan.scoring.completed ───────┘
         AUTO_REJECT → REJECTED + уведомление; иначе админ видит класс и решает
```

- Порт 8092, context path `/credit-policy`, снаружи — только через nginx: http://localhost:8080/credit-policy/
- Своя БД `nosurprise_scoring` (её создаёт `db-init` в docker-compose). В БД монолита и Mongo сервис не ходит.
- Swagger UI: http://localhost:8080/credit-policy/swagger-ui.html — кнопка Authorize, access-токен админа монолита
  (`POST /api/auth/login` под `admin@nosurprise.local`).

## Модель коротко

| Фактор | Вес | Баллы |
|---|---|---|
| История | 30 % | нет истории 0.5; +0.1 за каждый заём, погашенный вовремя (до +0.4); −0.25 за погашенный с просрочкой; −0.1 за отказ за 12 мес. |
| Цель | 20 % | балл цели у типа займа в монолите (`loan_types.purpose_score`), приходит в событии |
| Возраст | 20 % | 18–39 → 0.3; 40–60 → 0.5; 61–90 → 0.4; вне 18–90 — стоп-фактор |
| Город | 15 % | Минск 0.9; Гомель 0.2; другие города Беларуси 0.15; другие страны 0.5 |
| Пол | 15 % | женщина 1.0; мужчина 0.6 |

`score = Σ вес × балл / Σ весов`, `скорбалл = round(score × 400)`, `PD = 1 / (1 + e^(a + b·score))`
(при score 0.5 PD = 10 %, каждые +0.1 вдвое уменьшают шансы невозврата).
Скорбалл ≤ 125 → автоотказ, 126–200 → к админу «высокий риск», > 200 → к админу «рекомендовано».
Стоп-факторы (класс F): возраст вне 18–90, чёрный список, текущая просрочка.

Веса, баллы, пороги и калибровка — версия правил в таблице `rule_sets`; новая версия создаётся и включается
через API, каждый результат помнит свою версию.

## Админский API (роль ADMIN)

```bash
TOKEN=$(curl -s -H 'Content-Type: application/json' -d '{"email":"admin@nosurprise.local","password":"admin12345"}' \
  http://localhost:8080/api/auth/login | jq -r .accessToken)
P=http://localhost:8080/credit-policy
curl -s -H "Authorization: Bearer $TOKEN" $P/api/admin/scorings/<applicationId>   # расчёт с расшифровкой
curl -s -H "Authorization: Bearer $TOKEN" $P/api/admin/rules                      # активные правила
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"phone":"+375291234567","reason":"Не выходит на связь, просрочка 45 дней"}' $P/api/admin/blacklist
```

## Тесты

```bash
cd credit-policy
gradle test              # unit: модель, классы, PD, валидация правил (без Docker)
gradle integrationTest   # Testcontainers: Postgres + RabbitMQ, путь запрос → результат
```
