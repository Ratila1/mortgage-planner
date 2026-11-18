-- V12: KYC-документы (MICROLOANS.md §3.11). Фото паспорта — bytea в отдельной таблице,
-- чтобы списки и профиль не тянули мегабайты. content = null после удаления по сроку хранения.
CREATE TABLE kyc_documents (
    id           uuid PRIMARY KEY,
    user_id      uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type         varchar(16)  NOT NULL CHECK (type IN ('PASSPORT')),
    content      bytea,
    content_type varchar(32)  NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png')),
    size_bytes   integer      NOT NULL CHECK (size_bytes > 0),
    sha256       varchar(64)  NOT NULL,
    uploaded_at  timestamptz  NOT NULL DEFAULT now(),
    deleted_at   timestamptz
);

CREATE INDEX idx_kyc_documents_user_uploaded ON kyc_documents (user_id, uploaded_at DESC);
