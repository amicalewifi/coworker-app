CREATE TABLE print_credit_transactions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id   UUID        NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    pack_type   VARCHAR(20) NOT NULL,
    credits_added INTEGER   NOT NULL,
    amount_chf  NUMERIC(8,2) NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- Reset print_quota default to 0 (credits must be purchased)
ALTER TABLE members ALTER COLUMN print_quota SET DEFAULT 0;
