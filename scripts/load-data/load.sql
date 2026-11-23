-- Нагрузочные данные микрозаймов: пользователи и заявки (по умолчанию 10 000 × 50 = 500 000).
-- Запуск — через load.ps1 / load.sh (они же показывают прогресс). Вручную:
--   psql -v users=10000 -v per_user=50 -f load.sql
--
-- Путь данных тот же, что у живых заявок: Postgres → outbox → RabbitMQ → Mongo.
--   1. Одной транзакцией: users, профили, паспорта, заявки, журнал loan_events
--      и по одному событию loan.history.changed на заявку в outbox_events.
--   2. Дальше работает само приложение: OutboxRelay публикует события, LoanConsumers.onHistoryChanged
--      собирает документ тем же CreditHistoryAssembler, кладёт в Mongo и ставит credit_history_synced_event_id.
-- Других событий не пишем: loan.history.changed слушает только очередь кредитной истории,
-- поэтому ни писем, ни уведомлений клиентам не будет.
--
-- Повторный запуск ничего не дублирует: id детерминированные (md5 от номера), вставки — ON CONFLICT DO NOTHING.
-- Пользователи: load000001@load.local …, пароль Seed12345, телефоны +375442000001 …
-- Удалить всё: cleanup.sql (+ очистка Mongo в load.ps1 -Cleanup / load.sh --cleanup).

\set ON_ERROR_STOP on
\if :{?users}
\else
  \set users 10000
\endif
\if :{?per_user}
\else
  \set per_user 50
\endif
\timing on
\echo 'Пользователей:' :users ', заявок на каждого:' :per_user

BEGIN;

\echo '1/7 users'
INSERT INTO users (id, email, password_hash, email_verified, provider, created_at)
SELECT md5('load-user-' || n)::uuid,
       'load' || lpad(n::text, 6, '0') || '@load.local',
       '{bcrypt}$2a$10$7WGfNsuR2/FUWbldHoNnkOMzB30eR67B334oouDRupcq4SBRwef3C',   -- Seed12345
       true, 'LOCAL', now() - interval '2000 days'
FROM generate_series(1, :users) AS n
ON CONFLICT DO NOTHING;

INSERT INTO user_roles (user_id, role)
SELECT md5('load-user-' || n)::uuid, 'USER' FROM generate_series(1, :users) AS n
ON CONFLICT DO NOTHING;

-- Имена: 1000 сочетаний «фамилия + имя» подряд, дальше повторяются с другим отчеством.
-- Поиск в бюро только по ФИО обычно даёт 409 (однофамильцы), по ФИО с отчеством или телефону — одного.
\echo '2/7 customer_profile'
INSERT INTO customer_profile (user_id, last_name, first_name, middle_name, phone, phone_verified_at,
                              monthly_income, created_at, updated_at)
SELECT md5('load-user-' || n)::uuid,
       CASE WHEN n % 2 = 1 THEN last_base ELSE last_base || 'а' END,
       CASE WHEN n % 2 = 1 THEN male_first ELSE female_first END,
       CASE WHEN n % 2 = 1 THEN male_middle ELSE female_middle END,
       '+375442' || lpad(n::text, 6, '0'),
       now() - interval '1990 days',
       (700 + (n * 137) % 4300)::numeric(12, 2),
       now() - interval '1990 days', now() - interval '1990 days'
FROM (
    SELECT n,
           (ARRAY['Иванов','Петров','Сидоров','Королёв','Соловьёв','Кузнецов','Смирнов','Попов','Васильев',
                  'Новиков','Фёдоров','Морозов','Волков','Алексеев','Лебедев','Семёнов','Егоров','Павлов',
                  'Козлов','Степанов','Николаев','Орлов','Андреев','Макаров','Захаров','Зайцев','Борисов',
                  'Яковлев','Григорьев','Романов','Воробьёв','Сергеев','Кузьмин','Фролов','Александров',
                  'Дмитриев','Королев','Гусев','Киселёв','Ильин'])[((n - 1) % 40) + 1] AS last_base,
           (ARRAY['Иван','Сергей','Андрей','Дмитрий','Павел','Алексей','Николай','Виктор','Максим','Егор',
                  'Артём','Михаил','Владимир','Олег','Юрий','Антон','Кирилл','Роман','Евгений','Игорь',
                  'Денис','Илья','Глеб','Тимофей','Степан'])[((n - 1) / 40 % 25) + 1] AS male_first,
           (ARRAY['Анна','Мария','Елена','Ольга','Наталья','Татьяна','Ирина','Светлана','Дарья','Алёна',
                  'Юлия','Екатерина','Ксения','Полина','Виктория','Анастасия','Марина','Вероника','Софья',
                  'Алина','Людмила','Галина','Надежда','Валерия','Кристина'])[((n - 1) / 40 % 25) + 1] AS female_first,
           (ARRAY['Иванович','Сергеевич','Андреевич','Петрович','Николаевич','Викторович','Алексеевич',
                  'Дмитриевич','Павлович','Олегович','Юрьевич','Михайлович'])[((n - 1) / 1000 % 12) + 1] AS male_middle,
           (ARRAY['Ивановна','Сергеевна','Андреевна','Петровна','Николаевна','Викторовна','Алексеевна',
                  'Дмитриевна','Павловна','Олеговна','Юрьевна','Михайловна'])[((n - 1) / 1000 % 12) + 1] AS female_middle
    FROM generate_series(1, :users) AS n
) names
ON CONFLICT DO NOTHING;

\echo '3/7 kyc_documents'
INSERT INTO kyc_documents (id, user_id, type, content, content_type, size_bytes, sha256, uploaded_at)
SELECT md5('load-kyc-' || n)::uuid, md5('load-user-' || n)::uuid, 'PASSPORT',
       decode('89504e470d0a1a0a0000000d4948445200000140000000c808020000001666affc000008fb4944415478daeddd6d5014f71dc0f1df1d1c1af10ce203d831010f3c41cb015a1e8b800f8d9d514c6a1194567c8810c5e06363d5aad487a84d75940a626dad4f638c09698d64da3155468d0a5143442b301405c418d02822581feebceb8ba54859384d435b1fbe9ff1c5b2b7b7ffddbdfbbabbe3c86a2aab6f0a80a793964300103000020640c0000103206000040c8080010206f06470b4fff288d8148e11f07f74207b136760804b68004fd725f4639ec701b4afc7bc7be50c0c70090d808001103040c0000818000103206080800110300002060818000103206000040c103000020640c0000818206000040c808001020640c0000818000103f2dc3f5ae591ce155d78de8e9d5f7f2fbe40e00c0c808001020640c0000818000103c23f23e15b0a1f3ce6bb038ca2d1582c0fe6cd491ad0bfaf88ecfbe8c09a77b2fe9cb3ddd5d545444a4a2efc2673bbc562717470485b32dbcdadbb7a8e7a3dfb730eeefd2047e7a8335bcce3e3478f1a39ac69389b4d6effe3cebc3949b76ed5ef79ef2311f9e24c5160407f11891b1b337cd8f75b1d54bd61611163220707ff6af502655f162f5d7b28f744deb13fb6ba5f5143e38fe4ee6d65df4544243232f4a709aff17d20e0a78c4ea7dbb2798d889495552c5b91be6bc77a1139fae9c971f1a38f1d3f3d3a66b8882c5bb161c3fa34b79edd0fe51e5f9fbe75cdaa9fabe7b4584fcaf409fbf67fb23973955eef5c5f7f7bd6dc653d7a740b090e68bed82f96aeddfb6ec690e83011891a1aafcc6fa21e42bd614e4ebacacacb56ab55abd5da6cb6cb97ab9d9c7476f6abad7d0797d04f3d6f6fcf2faf548bc8ddbbf7eedcb9fba3575ff9f4d849e5a51bb575f7ef9945242a32243e6e54ab735aac67e7ae0f67cf9ca2d73b8b885eef3c2b75f28e9dd9cd17f3f2f2b87af5ba9ded510fa1de3011f1f1f13e5f542a22a5a517fbf6f5b4b35f20e067d9c95385fd8c0611c9cb2f080f1be4e1d1fbca573566b34544de4c497c3d79fef295e9670a8b020306b43aa7c57aca2baa7cfa199a66fafa785d2cbfd47cb1fccfbe080e32d9d91ef510ea0d1391b0d081797905caab61a103edec17b8847e0699cde6e4690b6c36e9dcb9d392c53345e4f091fcd2d28b07738f5fbb76a3a0e05c484860cca8e15151a1870fe7af5db765c890f0379212d4735aac67cad4b79a8f62b38946344dc3592c0fca2b2e67efdd6467c3d443a8374c44c24203dfcffe383929e1e4e9b3636347dad9afb6f6bdf1ef8b19934c7e3e7c1f08f869bd075658add64b97bedcb37ba3724e3b7aec94d168b85475c5dfe43b3a66f8e088a0b8f133e26247b698f34652428bf518fabc5c5c72c1dfe4abfc5852526630bcdc7cb81dbb3eccf9f8e0a489635bddaadadaba164324bd3eaec586290177e9a2d76ab435355f8b88b373a76f747fcb3d3097d0cf9a338545c6be7d94e9c08001f99f1568349a050bd72885d4d5d5f772efa19ea35e4fe2841fa76fdcd6d0705b44eaeb6fa7676c9f9818db7c81d0e080bf9d2f6d6b33d443a837ac69e1f0b04199593b438203f8f838033fef8e1cc90f0af257a63b76ece0dad5a5f666dde245a9f317aeeed0c1c941ab4d5b32dbc5a54b8b39eaf5848604d6d47c9d3c7d91934e67b698c7c5c504ff6bb50a0f8fde7f2fabb05a6d5aad46fd76f510fb73feda62c3ca2baa941f23228232b376ee7d37e391370b5392e62bd30126df99a9939b5f42fbf9f9a6ce98c817a05d682aab6fda7979446c8a3271207b93f0df0985ff4e88ff91c74c8f4b68807b6000040c808001020640c0000818000103040c808001103040c00008180001032060808001103000020640c0000103206000c2af957dc2551f3e54f1de6e11b971a6c03560a088788c1d5ff8cb452e03fc94057a46461b1212eb4a8a4b3237d82c168d838369c9f2bae2f3ea77f51af60311a9cad957f9c11e8da3ce66317bc6ffa4f7c8d122f29788a0ae7ea6d0acadca3a0f0c0d1f917ba2f984a2a1fc42c1a2b706efced668b522722c719c7fda4abd97371f1301a375eed1c3dca387292d856efe8332f3ecdb694dd38d73562c0d5a9fd1b1a75b75eec1e2f4750357fd5afd2e11b9967fa26aff9f42327fafd3ebcdf5f5a7e6bed9b147cfeec1a15a279ded81e5fae7a7ba0d0ab2b3319dfb78e9bdbcbf3af8c9775ef9e1d5e3473bf57e897ab984463bb8577be3c1bd7b22e21619ed1937beadc52eeedae63b73ae4eaf17119d5eef9b3ae7c2cec6bc8dc9334ab76c7ae4407da74e2bdbb6c566b5966ddf6a4c9ac6912760b4039f949979c993ceae4cbb51d878cddcaa868a8b2ff67bf87cb0177d7c1bca1b7fb17eb7ef058bc8f5cf4fda1fa8b3a741ef6d3cb77af90beeee9dfbf01bea0918df9ccd6cce9f3645f9537bae50447a8f7a35eafd7d5dfd038bd6bd53fabbacc75d8f4d441e3e36c5989c52fadb479f848d53a75dced9679c3a9d0f827b60fc27343a5df33bdbfbb5b5b7ab2abb9a025e8a79cd2d22eae8f831c6a4e96dddc4d695147735353e64ec564991def0f026b6dba0208d83c3f5d38f38093b7b783a74eae4ece1c907c11918ed12b4a660e1cfeed4548bc8fdba9b2fb8f76a6b41c38449c51bd75b1a1a44c45c5f5f9cb1c19038f9dfceaec929a55b3239a29c81f15fbf8456a65dfcfc7d66ccf25b9456b0709e43878e1aadd6b464795b6fec11127eb7a6266ffa14adcec966317bc625740f0a69be806be0208da3ce7adfdc34d089a4c60702ba9a027c52e770f0793aa1f07442be40109e4e08807b60808001103000020640c0000103206000040c808001020640c0000818206000040c808001103040c0000818000103040c80800110300002060818000103206000040c103000020640c0000103206000040c808001020640c0000818000103040c808001103040c00008180001032060808001103000020640c0000103206000040c103000020640c0000818206000040c808001020640c0000818000103040ce0c9e5d85e2bf2ebefc5d1043803032060808001103000020640c0000103206000040c103000020640c0000818206000040c808001103040c0000818000103040c80800110300002060818000103206000040c103000020640c0000103206000040c808081678be3632e37223685830570060640c000443495d537390a00676000040c808001020640c0000818000103040c8080017c1bff04a38773fc3419b61d0000000049454e44ae426082', 'hex'), 'image/png', 2356,
       '2c2b71337120672cdd17123fb6e8b6a1aea0c805338e1ee39d668718adf6b819', now() - interval '1990 days'
FROM generate_series(1, :users) AS n
ON CONFLICT DO NOTHING;

-- План заявок: раз в 35 дней (дольше самого длинного займа), последняя — свежая.
-- Открытой (SUBMITTED/APPROVED/ACTIVE) может быть только последняя: так требует uq_loan_applications_one_active.
\echo '4/7 план заявок'
CREATE TEMP TABLE load_apps ON COMMIT DROP AS
WITH plan AS (
    SELECT n, k,
           CASE
               WHEN k = :per_user THEN (ARRAY['ACTIVE','SUBMITTED','APPROVED','ACTIVE','REPAID','REJECTED'])[(n % 6) + 1]
               ELSE (ARRAY['REPAID','REPAID','REPAID','REPAID','REJECTED','CANCELLED','EXPIRED'])[((n + k) % 7) + 1]
           END AS status,
           CASE WHEN (n + k) % 3 = 0 THEN 'a1111111-1111-1111-1111-111111111111'::uuid
                ELSE 'a2222222-2222-2222-2222-222222222222'::uuid END AS type_id
    FROM generate_series(1, :users) AS n
    CROSS JOIN generate_series(1, :per_user) AS k
), terms AS (
    SELECT p.*, t.daily_rate, t.modifier,
           round(t.min_amount + (t.max_amount - t.min_amount) * ((p.n * 31 + p.k * 17) % 21) / 20.0, 0) AS amount,
           t.min_term_days + (p.n + p.k) % (t.max_term_days - t.min_term_days + 1) AS term_days,
           now() - make_interval(days => (:per_user - p.k) * 35
                 + CASE WHEN p.k = :per_user AND p.status = 'REPAID' THEN 40 ELSE 3 END,
                                 secs => (p.n % 86400)) AS created_at
    FROM plan p JOIN loan_types t ON t.id = p.type_id
)
SELECT md5('load-app-' || n || '-' || k)::uuid AS id,
       md5('load-user-' || n)::uuid AS user_id,
       md5('load-kyc-' || n)::uuid AS kyc_id,
       n, k, status, type_id, daily_rate, modifier, amount, term_days, created_at,
       round(amount * daily_rate / 100 * term_days, 2) AS interest,
       CASE WHEN status IN ('SUBMITTED', 'CANCELLED') THEN NULL ELSE created_at + interval '1 day' END AS decided_at,
       CASE WHEN status = 'APPROVED' THEN now() + interval '5 days'
            WHEN status IN ('ACTIVE', 'REPAID', 'EXPIRED') THEN created_at + interval '6 days' END AS offer_expires_at,
       CASE status
           WHEN 'REPAID'    THEN created_at + make_interval(days => term_days + 2)
           WHEN 'REJECTED'  THEN created_at + interval '1 day'
           WHEN 'CANCELLED' THEN created_at + interval '2 hours'
           WHEN 'EXPIRED'   THEN created_at + interval '6 days' END AS closed_at
FROM terms;

\echo '5/7 loan_applications'
INSERT INTO loan_applications (id, user_id, loan_type_id, amount, term_days, daily_rate, modifier, interest, total,
                               applicant_last_name, applicant_first_name, applicant_middle_name, applicant_phone,
                               applicant_monthly_income, kyc_document_id, status, decided_at, decision_comment,
                               offer_expires_at, closed_at, created_at, updated_at)
SELECT a.id, a.user_id, a.type_id, a.amount, a.term_days, a.daily_rate, a.modifier, a.interest,
       a.amount + a.interest + a.modifier,
       cp.last_name, cp.first_name, cp.middle_name, cp.phone, cp.monthly_income,
       a.kyc_id, a.status, a.decided_at,
       CASE a.status
           WHEN 'REJECTED' THEN (ARRAY['Высокая долговая нагрузка','Доход не подтверждён',
                                       'Неполные данные паспорта'])[(a.n + a.k) % 3 + 1]
           WHEN 'SUBMITTED' THEN NULL
           WHEN 'CANCELLED' THEN NULL
           ELSE 'Нагрузочные данные' END,
       a.offer_expires_at, a.closed_at, a.created_at, coalesce(a.closed_at, a.decided_at, a.created_at)
FROM load_apps a
JOIN customer_profile cp ON cp.user_id = a.user_id
ON CONFLICT DO NOTHING;

\echo '6/7 loan_events'
INSERT INTO loan_events (application_id, type, from_status, to_status, actor_id, occurred_at)
SELECT a.id, 'STATUS_CHANGED', p.steps[i - 1], p.steps[i],
       CASE WHEN p.steps[i] IN ('SUBMITTED', 'CANCELLED', 'ACTIVE') THEN a.user_id END,
       CASE p.steps[i]
           WHEN 'SUBMITTED' THEN a.created_at
           WHEN 'APPROVED'  THEN a.decided_at
           WHEN 'REJECTED'  THEN a.decided_at
           WHEN 'ACTIVE'    THEN a.decided_at + interval '2 hours'
           ELSE a.closed_at END
FROM load_apps a
JOIN (VALUES ('SUBMITTED', ARRAY[NULL, 'SUBMITTED']),
             ('APPROVED',  ARRAY[NULL, 'SUBMITTED', 'APPROVED']),
             ('ACTIVE',    ARRAY[NULL, 'SUBMITTED', 'APPROVED', 'ACTIVE']),
             ('REPAID',    ARRAY[NULL, 'SUBMITTED', 'APPROVED', 'ACTIVE', 'REPAID']),
             ('REJECTED',  ARRAY[NULL, 'SUBMITTED', 'REJECTED']),
             ('CANCELLED', ARRAY[NULL, 'SUBMITTED', 'CANCELLED']),
             ('EXPIRED',   ARRAY[NULL, 'SUBMITTED', 'APPROVED', 'EXPIRED'])) AS p(status, steps)
     ON p.status = a.status
CROSS JOIN LATERAL generate_series(2, array_length(p.steps, 1)) AS i
WHERE NOT EXISTS (SELECT 1 FROM loan_events e WHERE e.application_id = a.id)
ORDER BY a.created_at, i;

-- Ровно то, что пишет OutboxPublisher для LoanHistoryChanged: routing key, агрегат и JSON записи.
\echo '7/7 outbox_events (loan.history.changed)'
INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, created_at)
SELECT md5('load-history-' || a.id)::uuid, 'loan_application', a.id::text, 'loan.history.changed',
       jsonb_build_object('eventId', md5('load-history-' || a.id)::uuid,
                          'occurredAt', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                          'applicationId', a.id),
       now()
FROM load_apps a
ORDER BY a.created_at
ON CONFLICT (event_id) DO NOTHING;

COMMIT;

ANALYZE users;
ANALYZE customer_profile;
ANALYZE loan_applications;
ANALYZE loan_events;
ANALYZE outbox_events;

SELECT (SELECT count(*) FROM users WHERE email LIKE '%@load.local') AS users,
       (SELECT count(*) FROM loan_applications a JOIN users u ON u.id = a.user_id
         WHERE u.email LIKE '%@load.local') AS applications,
       (SELECT count(*) FROM outbox_events WHERE published_at IS NULL) AS outbox_pending;
