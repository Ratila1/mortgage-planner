// Удаляет из Mongo кредитную историю нагрузочных пользователей.
// userId те же, что в load.sql: md5('load-user-' || n)::uuid, n = 1..LOAD_USERS.
const crypto = require('crypto');
const users = parseInt(process.env.LOAD_USERS || '0', 10);
const toUuid = (hex) => `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
let deleted = 0;
for (let from = 1; from <= users; from += 1000) {
  const batch = [];
  for (let n = from; n < Math.min(from + 1000, users + 1); n++) {
    batch.push(UUID(toUuid(crypto.createHash('md5').update('load-user-' + n).digest('hex'))));
  }
  deleted += db.credit_history.deleteMany({ userId: { $in: batch } }).deletedCount;
}
print(`credit_history: удалено ${deleted} документов`);
