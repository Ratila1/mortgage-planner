# Code Research: microloan-frontend

## Scope and evidence boundary

Read-only research for a frontend-only microloan feature. The user explicitly prohibits backend changes. No backend files were modified. Current backend behavior below is based on checked-in source; no inference that mock frontend behavior is server-backed.

## Frontend structure and routing

- `frontend/src/App.tsx`: React Router routes. `/` renders `CalculatorPage`; auth routes include `/login`, `/register`, `/verify-email`; authenticated user routes include `/scenarios`, `/compare`, `/notifications`; `/admin/offers` is guarded by `<RequireAuth admin>`.
- `frontend/src/components/Layout.tsx`: shared navigation and page frame; adding a top-level calculator tab or admin section will likely require a navigation link here.
- `frontend/src/components/RequireAuth.tsx`: frontend redirects users who are not authenticated, and non-admin users away from admin screens. This is a UI guard, not server authorization.
- `frontend/src/pages/CalculatorPage.tsx`: existing mortgage calculator has input controls, debounced recalculation, summary, payment chart and payment schedule; includes mortgage-specific offers, stress test and early repayments.
- `frontend/src/pages/AdminOffersPage.tsx`: admin UI for bank offer CRUD, using existing typed API methods.

## API and persistence pattern

- `frontend/src/api/types.ts`: `Api` interface and current types cover mortgage calculations, auth, scenarios, notifications and `BankOffer`; no microloan, applicant profile, passport, or application types/methods exist.
- `frontend/src/api/index.ts`: selects the mock implementation when `VITE_USE_MOCKS=true`; otherwise uses `httpApi`.
- `frontend/src/api/http.ts`: communicates with current `/api/...` backend endpoints. No microloan endpoints exist in this client.
- `frontend/src/api/mock.ts`: local demo mock state is stored in browser `localStorage` under `nosurprise.mock.v2`; demo admin role is assigned according to email containing `admin`. This is browser-local, not a shared server store and not secure authorization.
- `frontend/vite.config.ts`: tests set `VITE_USE_MOCKS=true`; dev server proxies `/api` to `localhost:8080`.
- `frontend/nginx.conf`: proxies API paths; does not create missing backend endpoints.

## Existing mortgage calculator and reusable UI

- `frontend/src/lib/calc.ts`: local calculation for mortgage mocks; `calculateLocally` and `validateParams` implement annuity/differentiated mortgage behavior. Not automatically suitable for a short-term microloan model.
- `frontend/src/components/calculator/ResultSummary.tsx`, `PaymentChart.tsx`, `ScheduleTable.tsx`: reusable presentation patterns for total, principal/interest breakdown and schedule, though some labels/assumptions may be mortgage-specific.
- `frontend/src/components/ui.tsx`: shared Card, Field, inputs, buttons, alerts, badges and page title.
- `frontend/src/lib/format.ts`: money formatting defaults to BYN; mortgage-term formatting is in months.

## Auth and admin

- `frontend/src/pages/AuthPages.tsx`: registration currently asks for email and password, with existing email verification behavior; login and magic-link flows also exist.
- `frontend/src/auth/AuthContext.tsx`: maintains existing auth session and current user.
- `frontend/src/api/types.ts` `Me`: exposes id, email, email verification status and roles; current user API does not expose applicant personal data.
- `frontend/src/pages/AdminOffersPage.tsx`: existing offer administration is specific to mortgage bank offers.

## Backend capabilities checked (read-only)

- Current relevant controllers include `backend/src/main/java/dev/nosurprise/calc/web/CalculatorController.java`, `StressController.java`, `offer/web/OfferController.java`, `offer/web/AdminOfferController.java`, `auth/web/AuthController.java`, `user/web/UserController.java`, and scenario/notification controllers.
- `GET /api/offers` and `/api/admin/offers` manage `BankOffer`, whose fields are mortgage-specific (`bankName`, rate, minimum down payment percent, max term months, active, updatedAt). These are not microloan products.
- No microloan controllers/domain/API or applicant/passport/decision workflow were found under `backend/src/main`; migrations `V1` through `V8` likewise have no microloan schema.
- Existing `/api/me` returns auth identity, not applicant personal information. Existing email/auth endpoints are not loan application notifications or approval emails.

## Frontend-only feasibility and risks

Possible without backend edits:

- Build UI routes, an independently specified client-side calculation, and demonstration flows using static seed data or local mock state.
- Add an explicitly local-only mock application catalogue and admin UI, potentially backed by browser `localStorage`.
- Add form and status screens that are clearly labelled as demo behavior.

Not achievable as a real shared service without backend APIs:

- Globally shared/authoritative microloan products or admin CRUD across users/devices.
- Secure storage and review of applicants' personal data/passport images.
- Persisted loan applications, reliable admin approval/rejection, real email/notification delivery, or payment disbursement.
- A real admin role/security boundary for new operations implemented only in frontend.

Sensitive passport photos and personal data should not be stored in browser-local mock state. Any locally stored demo applications must use synthetic data or otherwise explicitly avoid real sensitive data. Product scope, calculation formula/rates, and whether to omit or simulate passport/application steps need user agreement before drafting.

## Frontend tests

- `frontend/src/App.test.tsx`: existing test file includes app render and mortgage local calculation examples.
- `frontend/package.json`: `npm test` runs Vitest; `npm run build` runs TypeScript and Vite build.
- `frontend/vite.config.ts`: configures Vitest with jsdom and mock API mode.
