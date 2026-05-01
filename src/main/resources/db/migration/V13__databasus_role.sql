-- Read-only role used by Databasus for logical backups.
-- Password injected via Flyway placeholder, set on the host in .env as
-- DATABASUS_PWD and exposed to the app as
-- SPRING_FLYWAY_PLACEHOLDERS_DATABASUSPWD.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'databasus') THEN
        CREATE ROLE databasus LOGIN PASSWORD '${databasuspwd}';
    ELSE
        ALTER ROLE databasus WITH LOGIN PASSWORD '${databasuspwd}';
    END IF;
END
$$;

GRANT CONNECT ON DATABASE amicale_wifi TO databasus;
GRANT USAGE   ON SCHEMA   public        TO databasus;

GRANT SELECT ON ALL TABLES    IN SCHEMA public TO databasus;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO databasus;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO databasus;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON SEQUENCES TO databasus;
