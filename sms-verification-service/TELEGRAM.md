# Telegram 2FA contract

Telegram verification was moved from the retired `notification-service` into this service.
The service keeps its port `8091`; SMS OTP behavior is unchanged.

## Flow

1. The backend starts a signing request (`POST /api/loans/applications/{id}/signature`). It still sends the
   SMS code and additionally stores a `telegram_verifications` row and publishes `verification.requested`
   to `domain.events`. The HTTP response contains `verificationId`.
2. This service consumes `verification.requested`, generates a 6-digit code, stores its SHA-256 hash in
   Redis and sends the code to the bound Telegram chat. Status becomes `SENT`.
3. The client polls `GET /api/verify/{id}/status` and posts `POST /api/verify/{id}/confirm` with
   `{ "code": "123456" }`. Both endpoints require the backend user JWT.
4. On success this service calls `POST /api/internal/verifications/{id}/complete` on the backend with
   `X-Service-Token`. The backend executes the signing operation idempotently by verification id.

Binding: `/start` sends a `request_contact` keyboard; the contact's phone is resolved through
`GET /api/internal/users/by-phone?phone=...` and stored in `sms.telegram_bindings`. The endpoint
returns 404 for an unknown phone and 409 (`AMBIGUOUS_PHONE`) when several profiles share it; in the
latter case the chat is not bound and the user is asked to contact support.

## HTTP API

| Method | Path | Auth |
|---|---|---|
| POST | `/telegram/webhook` | `X-Telegram-Bot-Api-Secret-Token` |
| GET | `/api/verify/{id}/status` | user JWT |
| POST | `/api/verify/{id}/confirm` | user JWT |

## RabbitMQ

Exchange `domain.events` (DLX `domain.events.dlx`) with queues `telegram.verification.q` and
`telegram.user-sync.q`, plus `.dlq` queues. Consumed routing keys: `verification.requested`,
`user.registered`, `user.phone.changed`, `user.blocked`, `user.unblocked`. Published: `verification.sent`,
`verification.completed`, `telegram.linked`, `telegram.unlinked`.

## Storage

Tables `sms.telegram_bindings` and `sms.verification_requests` are created by Flyway `V2`.
Redis keys: `tg:otp:{id}` (hash, TTL from the event), `tg:otp:used:{id}` (24 h), `tg:update:{id}` (24 h).

## Environment

`TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`, `TELEGRAM_POLLING_ENABLED`,
`TELEGRAM_WEBHOOK_SECRET_TOKEN`, `SERVICE_TOKEN`, `MAIN_BACKEND_URL`, `RABBITMQ_HOST`,
`RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`. `SERVICE_TOKEN` is mandatory: compose refuses
to start with it unset.

Local development uses long polling by default. Set `TELEGRAM_POLLING_ENABLED=false` in production and
configure a Telegram webhook pointing to `/telegram/webhook` with `secret_token` set to the same value as
`TELEGRAM_WEBHOOK_SECRET_TOKEN`. The service rejects the webhook with 401 when the header is missing,
wrong, or no secret is configured. `/api/internal/**` is denied by nginx from the public internet; the
SMS service reaches it directly through the docker network.

## Known gap

There is no frontend UI for the Telegram channel yet. The full service-to-service chain is implemented and
can be driven through the API above; the existing SMS signing UI continues to work unchanged.
