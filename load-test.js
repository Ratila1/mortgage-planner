import http from 'k6/http';
import { check, sleep } from 'k6';
import { b64decode } from 'k6/encoding';

export const options = {
  scenarios: {
    users: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 1 },
        { duration: '1m', target: 2 },
        { duration: '30s', target: 0 },
      ],
      exec: 'userFlow',
    },
    admins: {
      executor: 'constant-vus',
      vus: 2,
      duration: '2m',
      exec: 'adminFlow',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.1'],
    http_req_duration: ['p(95)<3000'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const MAILPIT = __ENV.MAILPIT_URL || 'http://localhost:8025';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@nosurprise.local';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || '';
const LOAN_TYPE_ID = __ENV.LOAN_TYPE_ID || 'a1111111-1111-1111-1111-111111111111';

// 1x1 PNG, чтобы не зависеть от файла на диске
const PNG_B64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO7+X0QAAAAASUVORK5CYII=';
const PNG_BYTES = b64decode(PNG_B64, 'std', 'b');
const PNG_FILE = http.file(PNG_BYTES, 'passport.png', 'image/png');

function verifyEmailToken(email) {
  for (let i = 0; i < 25; i++) {
    const res = http.get(`${MAILPIT}/api/v1/messages`);
    if (res.status === 200) {
      const msgs = (res.json().messages || []);
      const msg = msgs.find((m) => m.To && m.To[0] && m.To[0].Address === email);
      if (msg) {
        const detail = http.get(`${MAILPIT}/api/v1/message/${msg.ID}`);
        const text = (detail.json().Text || detail.json().HTML || '');
        const m = text.match(/token=([A-Za-z0-9_-]+)/);
        if (m) {
          const vr = http.post(`${BASE}/api/auth/verify-email`,
            JSON.stringify({ token: m[1] }),
            { headers: { 'Content-Type': 'application/json' } });
          if (vr.status === 200) return vr.json().accessToken;
        }
      }
    }
    sleep(1);
  }
  return null;
}

export function userFlow() {
  const email = `k6-${__VU}-${__ITER}-${Date.now()}@example.com`;
  const password = 'Strong-pass-123';
  const phone = '+37529' + String(Math.floor(Math.random() * 9000000) + 1000000);

  let res = http.post(`${BASE}/api/auth/register`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'register accepted': (r) => r.status === 202 || r.status === 200 });
  if (res.status !== 202 && res.status !== 200) { sleep(2); return; }

  const token = verifyEmailToken(email);
  if (!token) { sleep(2); return; }

  res = http.put(`${BASE}/api/me/profile`,
    JSON.stringify({ lastName: 'Иванов', firstName: 'K6', middleName: 'Тест', phone, monthlyIncome: 2000 }),
    { headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` } });
  check(res, { 'profile ok': (r) => r.status === 200 });

  res = http.post(`${BASE}/api/me/kyc/passport`,
    { file: PNG_FILE },
    { headers: { 'Authorization': `Bearer ${token}` } });
  check(res, { 'passport uploaded': (r) => r.status === 201 || r.status === 200 });

  const submitBody = {
    loanTypeId: LOAN_TYPE_ID,
    amount: 1000,
    termDays: 14,
    profile: { lastName: 'Иванов', firstName: 'K6', middleName: 'Тест', phone, monthlyIncome: 2000 },
  };
  res = http.post(`${BASE}/api/loans/applications`,
    JSON.stringify(submitBody),
    { headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` } });
  check(res, { 'application created': (r) => r.status === 201 });
  if (res.status === 201) {
    const appId = res.json().id;
    http.post(`${BASE}/api/loans/applications/${appId}/cancel`,
      null,
      { headers: { 'Authorization': `Bearer ${token}` } });
  }
  sleep(3);
}

export function adminFlow() {
  const res = http.post(`${BASE}/api/auth/login`,
    JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 200) return;
  const token = res.json().accessToken;

  let r = http.get(`${BASE}/api/admin/loan-state-metrics/report`,
    { headers: { 'Authorization': `Bearer ${token}` } });
  check(r, { 'report ok': (x) => x.status === 200 });

  r = http.get(`${BASE}/api/admin/loan-applications?size=20`,
    { headers: { 'Authorization': `Bearer ${token}` } });
  check(r, { 'admin list ok': (x) => x.status === 200 });

  sleep(2);
}
