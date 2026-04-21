CREATE TABLE conf_credit_transactions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id   UUID        NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    pack_type   VARCHAR(10) NOT NULL,
    hours_added NUMERIC(5,2) NOT NULL,
    amount_chf  NUMERIC(8,2) NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conf_credit_tx_member ON conf_credit_transactions(member_id);
