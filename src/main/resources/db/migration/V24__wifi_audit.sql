-- Journal des événements liés à l'accès WiFi (portail captif UniFi).
-- Sert au support : « pourquoi mon WiFi ne marche pas », conflits de MAC, kicks de minuit, etc.

CREATE TABLE wifi_audit (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id    UUID         REFERENCES members(id) ON DELETE SET NULL,
    mac          VARCHAR(17),
    event        VARCHAR(32)  NOT NULL,    -- BOUND, REASSIGNED, AUTHORIZED, UNAUTHORIZED, DENIED_NO_PACK, KICK_MIDNIGHT, CHARGED_HALF, CHARGED_FULL
    detail       TEXT,
    occurred_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_wifi_audit_member ON wifi_audit(member_id, occurred_at DESC);
