-- V20: заявка подписана в момент подачи — телефон подтверждён SMS до createMicroloanApplication.
-- Старые заявки получают false и сохраняют ручной путь подписи с кодом из SMS.
ALTER TABLE loan_applications
    ADD COLUMN signed_at_submit boolean NOT NULL DEFAULT false;
