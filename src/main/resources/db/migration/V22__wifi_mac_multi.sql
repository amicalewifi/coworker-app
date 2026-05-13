-- Passe d'un wifi_mac unique par membre à une liste de MACs (multi-appareil).
-- Le portail captif UniFi nous fournit la MAC à chaque association, donc
-- chaque membre peut enregistrer plusieurs appareils (téléphone + laptop, etc.).

CREATE TABLE member_wifi_macs (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id    UUID         NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    mac          VARCHAR(17)  NOT NULL UNIQUE,        -- format normalisé aa:bb:cc:dd:ee:ff
    label        VARCHAR(64),                          -- libellé optionnel (hostname / nom donné par l'user)
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMP
);

CREATE INDEX idx_member_wifi_macs_member ON member_wifi_macs(member_id);

-- Reprise des données existantes : un seul MAC par membre dans members.wifi_mac.
INSERT INTO member_wifi_macs (member_id, mac, created_at)
SELECT id, lower(wifi_mac), created_at
FROM   members
WHERE  wifi_mac IS NOT NULL;

ALTER TABLE members DROP COLUMN wifi_mac;
