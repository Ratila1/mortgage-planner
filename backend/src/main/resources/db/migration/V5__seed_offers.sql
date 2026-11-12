-- V5: демо-данные банков (вымышленные, §6.1).
INSERT INTO bank_offers (id, bank_name, rate, min_down_payment_percent, max_term_months, active)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'Aurora Bank',  9.50, 10.00, 360, true),
    ('22222222-2222-2222-2222-222222222222', 'Boreas Bank',  10.20, 15.00, 300, true),
    ('33333333-3333-3333-3333-333333333333', 'Cetus Bank',   8.90, 20.00, 240, true),
    ('44444444-4444-4444-4444-444444444444', 'Draco Bank',   11.00,  5.00, 360, true);