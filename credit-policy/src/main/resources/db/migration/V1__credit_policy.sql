-- V1: схема сервиса кредитной политики (CREDIT_POLICY.md §7.1).

-- Версии правил: веса, баллы, пороги, калибровка PD. Активна ровно одна версия.
CREATE TABLE rule_sets (
    version    integer      PRIMARY KEY,
    rules      jsonb        NOT NULL,
    active     boolean      NOT NULL DEFAULT false,
    comment    varchar(500),
    created_by uuid,
    created_at timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_rule_sets_active ON rule_sets (active) WHERE active;

-- Справочник целей кредита. Тип займа в монолите хранит code (loan_types.purpose_code).
CREATE TABLE purposes (
    code       varchar(32)  PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    name       varchar(100) NOT NULL,
    score      numeric(3,2) NOT NULL CHECK (score >= 0 AND score <= 1),
    active     boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    version    bigint       NOT NULL DEFAULT 0
);

-- Чёрный список (§7.4): запрещает только новые займы, не вход в аккаунт. Записи не удаляются, а снимаются.
CREATE TABLE blacklist_entries (
    id               uuid         PRIMARY KEY,
    phone            varchar(20),
    last_name_norm   varchar(100),
    first_name_norm  varchar(100),
    middle_name_norm varchar(100),
    birth_date       date,
    reason           varchar(500) NOT NULL,
    added_by         uuid         NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    expires_at       timestamptz,
    removed_at       timestamptz,
    removed_by       uuid,
    -- ключ поиска: телефон или фамилия + имя + дата рождения
    CHECK (phone IS NOT NULL
        OR (last_name_norm IS NOT NULL AND first_name_norm IS NOT NULL AND birth_date IS NOT NULL))
);

CREATE INDEX idx_blacklist_phone ON blacklist_entries (phone) WHERE removed_at IS NULL;
CREATE INDEX idx_blacklist_names ON blacklist_entries (last_name_norm, first_name_norm, birth_date)
    WHERE removed_at IS NULL;

-- Журнал расчётов: вход, расшифровка по факторам, версия правил. request_id — идемпотентность.
CREATE TABLE scoring_results (
    id             uuid         PRIMARY KEY,
    request_id     uuid         NOT NULL UNIQUE,
    application_id uuid         NOT NULL,
    user_id        uuid         NOT NULL,
    status         varchar(16)  NOT NULL CHECK (status IN ('COMPLETED', 'NOT_CALCULATED')),
    score          numeric(5,4),
    points         integer,
    rating_class   varchar(2),
    pd             numeric(7,6),
    decision       varchar(16)  NOT NULL CHECK (decision IN ('RECOMMEND', 'REVIEW', 'AUTO_REJECT')),
    reasons        jsonb        NOT NULL,
    breakdown      jsonb        NOT NULL,
    input          jsonb        NOT NULL,
    rules_version  integer      NOT NULL REFERENCES rule_sets (version),
    created_at     timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_scoring_results_application ON scoring_results (application_id, created_at DESC);

-- Стартовый справочник целей (§7.3).
INSERT INTO purposes (code, name, score) VALUES
    ('TECH',      'Покупка техники',        0.60),
    ('PAYDAY',    'До зарплаты',            0.30),
    ('MEDICAL',   'Лечение',                0.80),
    ('EDUCATION', 'Образование',            0.80),
    ('HOME',      'Ремонт и дом',           0.60),
    ('TRAVEL',    'Отдых и путешествия',    0.40),
    ('OTHER',     'Другое',                 0.40);

INSERT INTO rule_sets (version, active, comment, rules) VALUES (1, true, 'Стартовая экспертная модель', '{
  "weights": { "history": 0.30, "purpose": 0.20, "age": 0.20, "city": 0.15, "sex": 0.15 },
  "age": {
    "min": 18, "max": 90,
    "bands": [
      { "from": 18, "to": 39, "score": 0.3 },
      { "from": 40, "to": 60, "score": 0.5 },
      { "from": 61, "to": 90, "score": 0.4 }
    ]
  },
  "city": {
    "homeCountry": "BY",
    "cities": { "минск": 0.9, "minsk": 0.9, "гомель": 0.2, "gomel": 0.2, "homel": 0.2 },
    "otherHomeCountry": 0.15,
    "foreign": 0.5
  },
  "sex": { "FEMALE": 1.0, "MALE": 0.6 },
  "history": {
    "noHistory": 0.5, "base": 0.5,
    "perRepaidOnTime": 0.1, "maxRepaidBonus": 0.4,
    "perRepaidLate": 0.25, "perRejectedLast12Months": 0.1
  },
  "decision": { "autoRejectMaxPoints": 125, "reviewMaxPoints": 200 },
  "pd": { "anchorScore": 0.5, "anchorPd": 0.10, "doubleOddsStep": 0.1 }
}'::jsonb);
