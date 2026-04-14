-- V5: QR token unique par membre + champs adresse postale

ALTER TABLE members
    ADD COLUMN IF NOT EXISTS qr_token    UUID UNIQUE DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS address     TEXT,
    ADD COLUMN IF NOT EXISTS city        TEXT,
    ADD COLUMN IF NOT EXISTS postal_code TEXT,
    ADD COLUMN IF NOT EXISTS country     TEXT NOT NULL DEFAULT 'Suisse';

CREATE INDEX IF NOT EXISTS idx_members_qr_token ON members(qr_token);

-- Backfill des membres existants sans token
UPDATE members SET qr_token = gen_random_uuid() WHERE qr_token IS NULL;
