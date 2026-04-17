ALTER TABLE access_events ADD COLUMN IF NOT EXISTS akuvox_id INTEGER;
CREATE UNIQUE INDEX IF NOT EXISTS ux_access_events_akuvox_id ON access_events(akuvox_id) WHERE akuvox_id IS NOT NULL;
