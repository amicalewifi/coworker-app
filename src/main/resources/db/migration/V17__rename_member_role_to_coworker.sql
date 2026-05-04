-- Rename user_role enum value MEMBER -> COWORKER
-- TERMINAL had no users (cleaned by V16) and cannot be dropped from a PG enum,
-- so it remains in the type definition but is unused.

ALTER TYPE user_role RENAME VALUE 'MEMBER' TO 'COWORKER';
ALTER TABLE users ALTER COLUMN role SET DEFAULT 'COWORKER';
