-- ============================================================
-- V1__schema.sql  —  l'Amicale du WiFi
-- Flyway exécute ce fichier automatiquement au démarrage
-- ============================================================

-- ── TYPES ──────────────────────────────────────────────────

CREATE TYPE user_role AS ENUM ('ADMIN','MEMBER','TERMINAL');

CREATE TYPE membership_type AS ENUM (
    'PACK_5J',        -- Fr. 109.-/3 mois  5.0 unités  2h conf
    'PACK_10J',       -- Fr. 199.-/3 mois 10.0 unités  4h conf
    'PACK_15J',       -- Fr. 279.-/3 mois 15.0 unités  6h conf
    'PERMANENT',      -- Fr. 329.-/mois   illimité 24/7 10h conf
    'JOURNEE_ESSAI',  -- Gratuit, sans engagement
    'UNITAIRE',       -- À la journée / demi-journée hors pack
    'DOMICILIATION'   -- Fr. 79.-/mois, adresse postale
);

-- RÈGLE MÉTIER: 1 journée = 1.0 unité / 1 demi-journée = 0.5 unité
CREATE TYPE presence_type AS ENUM (
    'HALF_AM',    -- 07:00-12:30 → -0.5 unité
    'HALF_PM',    -- 12:30-19:00 → -0.5 unité
    'FULL_DAY',   -- 07:00-19:00 → -1.0 unité
    'TRIAL',      -- essai gratuit → 0.0 unité
    'UNIT_HALF',  -- unitaire demi-j. hors pack → 0.5 unité
    'UNIT_FULL'   -- unitaire journée hors pack  → 1.0 unité
);

CREATE TYPE presence_status   AS ENUM ('ACTIVE','COMPLETED','CANCELLED');
CREATE TYPE room_type         AS ENUM ('SALLE_CONFERENCE','CABINE_REUNION');
CREATE TYPE booking_status    AS ENUM ('CONFIRMED','CANCELLED','COMPLETED','NO_SHOW');
CREATE TYPE print_job_status  AS ENUM ('QUEUED','PRINTING','COMPLETED','CANCELLED','ERROR');
CREATE TYPE access_event_type AS ENUM (
    'ENTRY_GRANTED','ENTRY_DENIED','EXIT','QR_SCAN','NEW_MEMBER_CREATED'
);

-- ── FONCTION utilitaire ─────────────────────────────────────

CREATE OR REPLACE FUNCTION presence_units(ptype presence_type)
RETURNS NUMERIC(3,1) LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE ptype
        WHEN 'HALF_AM'   THEN 0.5
        WHEN 'HALF_PM'   THEN 0.5
        WHEN 'FULL_DAY'  THEN 1.0
        WHEN 'UNIT_HALF' THEN 0.5
        WHEN 'UNIT_FULL' THEN 1.0
        WHEN 'TRIAL'     THEN 0.0
        ELSE 1.0
    END;
$$;

-- ── USERS ──────────────────────────────────────────────────

CREATE TABLE users (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    email         TEXT        NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,
    role          user_role   NOT NULL DEFAULT 'MEMBER',
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    last_login    TIMESTAMPTZ
);
CREATE INDEX idx_users_email ON users(email);

-- ── MEMBERS ────────────────────────────────────────────────

CREATE TABLE members (
    id           UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    first_name   TEXT            NOT NULL,
    last_name    TEXT            NOT NULL,
    email        TEXT            NOT NULL UNIQUE,
    phone        TEXT,
    company      TEXT,
    badge_uid    TEXT            UNIQUE,
    badge_active BOOLEAN         NOT NULL DEFAULT TRUE,
    badge_expires DATE,
    membership   membership_type NOT NULL DEFAULT 'PACK_5J',
    -- Pack (0.5 = demi-journée, 1.0 = journée)
    pack_units_total    NUMERIC(5,1),
    pack_units_used     NUMERIC(5,1)   NOT NULL DEFAULT 0.0,
    pack_expires        DATE,
    -- Crédits salle conférence inclus dans le pack (heures)
    conf_credits_total_h NUMERIC(5,2)  NOT NULL DEFAULT 0.0,
    conf_credits_used_h  NUMERIC(5,2)  NOT NULL DEFAULT 0.0,
    -- Domiciliation
    has_domiciliation   BOOLEAN        NOT NULL DEFAULT FALSE,
    logo_url            TEXT,
    has_mailbox         BOOLEAN        NOT NULL DEFAULT FALSE,
    -- Imprimante cafétéria
    print_quota         INTEGER        NOT NULL DEFAULT 50,
    print_used          INTEGER        NOT NULL DEFAULT 0,
    -- Auth
    user_id             UUID           REFERENCES users(id) ON DELETE SET NULL,
    is_active           BOOLEAN        NOT NULL DEFAULT TRUE,
    notes               TEXT,
    CONSTRAINT chk_pack_pos CHECK (pack_units_used >= 0),
    CONSTRAINT chk_pack_max CHECK (pack_units_total IS NULL OR pack_units_used <= pack_units_total),
    CONSTRAINT chk_conf_max CHECK (conf_credits_used_h <= conf_credits_total_h)
);
CREATE INDEX idx_members_email     ON members(email);
CREATE INDEX idx_members_badge_uid ON members(badge_uid) WHERE badge_uid IS NOT NULL;
CREATE INDEX idx_members_active    ON members(is_active);

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN NEW.updated_at = NOW(); RETURN NEW; END; $$;

CREATE TRIGGER members_updated_at
    BEFORE UPDATE ON members FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── ACCESS EVENTS ──────────────────────────────────────────

CREATE TABLE access_events (
    id             UUID              PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at    TIMESTAMPTZ       NOT NULL DEFAULT NOW(),
    member_id      UUID              REFERENCES members(id) ON DELETE SET NULL,
    badge_uid      TEXT,
    event_type     access_event_type NOT NULL,
    presence_type  presence_type,
    units_consumed NUMERIC(3,1),
    denied_reason  TEXT,
    terminal_id    TEXT              DEFAULT 'borne'
);
CREATE INDEX idx_access_occurred ON access_events(occurred_at DESC);
CREATE INDEX idx_access_member   ON access_events(member_id, occurred_at DESC);

-- ── PRESENCES ──────────────────────────────────────────────

CREATE TABLE presences (
    id             UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    member_id      UUID            NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    date           DATE            NOT NULL DEFAULT CURRENT_DATE,
    presence_type  presence_type   NOT NULL,
    status         presence_status NOT NULL DEFAULT 'ACTIVE',
    checked_in_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    checked_out_at TIMESTAMPTZ,
    units_consumed NUMERIC(3,1)    NOT NULL DEFAULT 0.0,
    is_unitaire    BOOLEAN         NOT NULL DEFAULT FALSE,
    unit_price_chf NUMERIC(8,2),
    UNIQUE(member_id, date, presence_type)
);
CREATE INDEX idx_presences_date   ON presences(date, status);
CREATE INDEX idx_presences_member ON presences(member_id, date DESC);

-- Trigger: calcule les unités et décrémente le pack automatiquement
CREATE OR REPLACE FUNCTION before_presence_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.units_consumed := presence_units(NEW.presence_type);
    NEW.is_unitaire    := NEW.presence_type IN ('UNIT_HALF','UNIT_FULL','TRIAL');
    IF NEW.units_consumed > 0 AND NOT NEW.is_unitaire THEN
        UPDATE members
           SET pack_units_used = pack_units_used + NEW.units_consumed
         WHERE id = NEW.member_id
           AND membership IN ('PACK_5J','PACK_10J','PACK_15J');
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER presence_before_insert
    BEFORE INSERT ON presences FOR EACH ROW EXECUTE FUNCTION before_presence_insert();

-- ── ROOMS ──────────────────────────────────────────────────

CREATE TABLE rooms (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT        NOT NULL UNIQUE,
    room_type       room_type   NOT NULL,
    capacity        INTEGER     NOT NULL,
    equipment       TEXT[],
    hourly_rate_chf NUMERIC(6,2) NOT NULL DEFAULT 19.00,
    qr_code_token   TEXT        UNIQUE DEFAULT encode(gen_random_bytes(16),'hex'),
    description     TEXT,
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE
);

INSERT INTO rooms (name, room_type, capacity, equipment, description) VALUES
    ('Salle de conférence','SALLE_CONFERENCE',12,
     ARRAY['Flipchart Samsung WMR 65"','WiFi','Climatisation'],
     'Max 12 personnes. 8h–21h. Fr. 19.-/h ou crédits pack.'),
    ('Cabine de réunion','CABINE_REUNION',6,
     ARRAY['Écran Samsung Q60B 75" 4K','WiFi','Climatisation'],
     'Max 6 personnes. 8h–21h. Fr. 19.-/h ou crédits pack.');

-- ── ROOM BOOKINGS ──────────────────────────────────────────

CREATE TABLE room_bookings (
    id                  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    room_id             UUID           NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    member_id           UUID           REFERENCES members(id) ON DELETE SET NULL,
    organizer_name      TEXT,
    date                DATE           NOT NULL,
    start_time          TIME           NOT NULL CHECK (start_time >= '08:00' AND start_time < '21:00'),
    end_time            TIME           NOT NULL CHECK (end_time > '08:00' AND end_time <= '21:00'),
    participants        INTEGER        NOT NULL DEFAULT 1,
    status              booking_status NOT NULL DEFAULT 'CONFIRMED',
    title               TEXT,
    notes               TEXT,
    billed_from_credits BOOLEAN        NOT NULL DEFAULT TRUE,
    billed_amount_chf   NUMERIC(8,2),
    CONSTRAINT no_room_overlap EXCLUDE USING gist (
        room_id WITH =,
        tsrange(date + start_time, date + end_time,'[)') WITH &&
    ) WHERE (status = 'CONFIRMED')
);
CREATE TRIGGER room_bookings_updated_at
    BEFORE UPDATE ON room_bookings FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE INDEX idx_bookings_room   ON room_bookings(room_id, date);
CREATE INDEX idx_bookings_member ON room_bookings(member_id, date DESC);

-- ── PRINTER ────────────────────────────────────────────────

CREATE TABLE printer_jobs (
    id             UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    member_id      UUID             REFERENCES members(id) ON DELETE SET NULL,
    filename       TEXT             NOT NULL,
    pages          INTEGER          NOT NULL DEFAULT 1,
    copies         INTEGER          NOT NULL DEFAULT 1,
    color          BOOLEAN          NOT NULL DEFAULT FALSE,
    duplex         BOOLEAN          NOT NULL DEFAULT TRUE,
    status         print_job_status NOT NULL DEFAULT 'QUEUED',
    printer_job_id TEXT,
    error_message  TEXT,
    completed_at   TIMESTAMPTZ,
    cost_per_page  NUMERIC(4,3)     NOT NULL DEFAULT 0.05,
    total_pages    INTEGER          GENERATED ALWAYS AS (pages * copies) STORED,
    total_cost     NUMERIC(8,2)     GENERATED ALWAYS AS (pages * copies * cost_per_page) STORED
);

CREATE TABLE printer_status (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    checked_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    online           BOOLEAN     NOT NULL DEFAULT TRUE,
    toner_black_pct  INTEGER,
    drum_pct         INTEGER,
    paper_tray1_pct  INTEGER,
    paper_tray2_pct  INTEGER,
    model            TEXT        DEFAULT 'Kyocera ECOSYS M2635dn',
    printer_ip       INET        DEFAULT '192.168.1.45'
);
