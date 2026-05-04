-- V20: token UUID dédié à l'imprimante virtuelle IPP (différent de qr_token,
-- pour pouvoir révoquer indépendamment l'accès à l'impression sans casser
-- le badge/QR d'accès).

ALTER TABLE members
    ADD COLUMN IF NOT EXISTS print_token UUID UNIQUE DEFAULT gen_random_uuid();

UPDATE members SET print_token = gen_random_uuid() WHERE print_token IS NULL;

ALTER TABLE members ALTER COLUMN print_token SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_members_print_token ON members(print_token);
