-- Suivi quotidien du temps de connexion WiFi par membre.
-- Les unités du pack sont décomptées au fil de la journée :
--   < 30 min            → 0
--   ≥ 30 min et < 5 h   → 0.5
--   ≥ 5 h               → 1.0
-- Multi-appareil : on stocke l'UNION (un membre = une timeline), pas la somme.

CREATE TABLE wifi_daily_usage (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id      UUID         NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    usage_date     DATE         NOT NULL,                          -- date Europe/Zurich
    seconds        INTEGER      NOT NULL DEFAULT 0,                -- cumul (union des appareils)
    units_charged  NUMERIC(2,1) NOT NULL DEFAULT 0.0,              -- 0.0 / 0.5 / 1.0
    last_poll_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_wifi_daily_usage_member_date UNIQUE (member_id, usage_date)
);

CREATE INDEX idx_wifi_daily_usage_date ON wifi_daily_usage(usage_date);
