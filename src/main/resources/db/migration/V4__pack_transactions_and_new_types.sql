-- ============================================================
-- V4 — Nouveaux types pack + présence + table pack_transactions
-- ============================================================

-- Nouveaux types membership
ALTER TYPE membership_type ADD VALUE IF NOT EXISTS 'PACK_MATIN';
ALTER TYPE membership_type ADD VALUE IF NOT EXISTS 'PACK_APMIDI';
ALTER TYPE membership_type ADD VALUE IF NOT EXISTS 'PACK_1J';

-- Nouveaux types de présence (unitaires matin/AP-M)
ALTER TYPE presence_type ADD VALUE IF NOT EXISTS 'UNIT_HALF_AM';
ALTER TYPE presence_type ADD VALUE IF NOT EXISTS 'UNIT_HALF_PM';

-- Historique des achats de packs
CREATE TABLE IF NOT EXISTS pack_transactions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id   UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    membership  membership_type NOT NULL,
    units       NUMERIC(5,1),
    amount_chf  NUMERIC(8,2) NOT NULL,
    kind        VARCHAR(20) NOT NULL DEFAULT 'renew',  -- 'create' | 'renew'
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pack_transactions_member  ON pack_transactions(member_id);
CREATE INDEX idx_pack_transactions_created ON pack_transactions(created_at);
