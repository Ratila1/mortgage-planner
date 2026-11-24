#!/usr/bin/env bash
# Нагрузочные данные: ./scripts/load-data/load.sh [users] [per_user]    (по умолчанию 10000 × 50 = 500 000 заявок)
#                     ./scripts/load-data/load.sh --cleanup             (удалить их из Postgres и Mongo)
# Запускать из любой папки при поднятом docker compose.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR/../.."

pg() { docker compose exec -T postgres sh -c 'psql -X -At -v ON_ERROR_STOP=1 -U $POSTGRES_USER -d $POSTGRES_DB'; }

if [[ "${1:-}" == "--cleanup" ]]; then
  max=$(echo "SELECT coalesce(max(substring(email from 5 for 6)::int), 0) FROM users WHERE email LIKE '%@load.local';" | pg)
  echo "Postgres: удаляю нагрузочных пользователей ($max) и их заявки…"
  docker compose cp "$DIR/cleanup.sql" postgres:/tmp/load-cleanup.sql
  docker compose exec -T postgres sh -c 'psql -X -v ON_ERROR_STOP=1 -U $POSTGRES_USER -d $POSTGRES_DB -f /tmp/load-cleanup.sql'
  echo "Mongo: удаляю их кредитную историю…"
  docker compose cp "$DIR/cleanup-mongo.js" mongo:/tmp/load-cleanup.js
  docker compose exec -T -e LOAD_USERS="$max" mongo mongosh --quiet nosurprise /tmp/load-cleanup.js
  exit 0
fi

USERS="${1:-10000}"; PER_USER="${2:-50}"; EXPECTED=$((USERS * PER_USER))
echo "Postgres: $USERS пользователей × $PER_USER заявок = $EXPECTED…"
docker compose cp "$DIR/load.sql" postgres:/tmp/load.sql
docker compose exec -T postgres sh -c "psql -X -v ON_ERROR_STOP=1 -v users=$USERS -v per_user=$PER_USER -U \$POSTGRES_USER -d \$POSTGRES_DB -f /tmp/load.sql"

echo "Mongo: события идут через outbox → RabbitMQ → consumer. Ctrl+C прерывает только ожидание, не загрузку."
start=$(date +%s)
while true; do
  read -r synced pending < <(echo "SELECT (SELECT count(*) FROM loan_applications a JOIN users u ON u.id = a.user_id
      WHERE u.email LIKE '%@load.local' AND a.credit_history_synced_event_id IS NOT NULL),
      (SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND event_type = 'loan.history.changed');" | pg | tr '|' ' ')
  elapsed=$(( $(date +%s) - start ))
  rate=$(( elapsed > 0 ? synced / elapsed : 0 ))
  printf '\r  в Mongo: %d / %d   в outbox: %d   %d/с   %ds ' "$synced" "$EXPECTED" "$pending" "$rate" "$elapsed"
  [[ "$synced" -ge "$EXPECTED" ]] && break
  sleep 10
done
echo
echo -n "Документов в credit_history: "
docker compose exec -T mongo mongosh --quiet nosurprise --eval 'db.credit_history.countDocuments()'
