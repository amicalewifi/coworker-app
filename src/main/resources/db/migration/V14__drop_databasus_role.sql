-- Drop the 'databasus' role provisioned by V13. Backups are now handled
-- externally (rclone of /var/lib/docker/volumes), so the dedicated read-only
-- PG role is no longer needed.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'databasus') THEN
        ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT ON TABLES    FROM databasus;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT ON SEQUENCES FROM databasus;

        REVOKE SELECT ON ALL TABLES    IN SCHEMA public FROM databasus;
        REVOKE SELECT ON ALL SEQUENCES IN SCHEMA public FROM databasus;
        REVOKE USAGE   ON SCHEMA   public               FROM databasus;
        REVOKE CONNECT ON DATABASE amicale_wifi         FROM databasus;

        DROP ROLE databasus;
    END IF;
END
$$;
