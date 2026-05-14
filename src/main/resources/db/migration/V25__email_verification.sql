-- Email verification on registration.
-- New accounts created via /register are now flagged email_verified=false
-- until the user clicks the verification link emailed to them. Existing
-- users (who have been using the app for months) are backfilled to TRUE.

ALTER TABLE users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Auto-verify all existing accounts — they pre-date this feature.
UPDATE users SET email_verified = TRUE;

CREATE TABLE email_verification_tokens (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token      TEXT        NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_evt_token ON email_verification_tokens(token);
CREATE INDEX idx_evt_user  ON email_verification_tokens(user_id);
