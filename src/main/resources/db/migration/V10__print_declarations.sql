CREATE TABLE print_declarations (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id    UUID        NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    pages_bw     INTEGER     NOT NULL DEFAULT 0,
    pages_color  INTEGER     NOT NULL DEFAULT 0,
    credits_used INTEGER     NOT NULL,
    notes        TEXT,
    declared_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_print_declarations_member ON print_declarations(member_id);
