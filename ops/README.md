# `ops/`

Files in this directory are referenced by `docker-compose.yml` at runtime:

- **`Caddyfile`** — mounted into the `caddy` service.
- **`.env.example`** — template for the `.env` file `docker compose` reads at the
  project root. Copy to `.env` and fill in real values for local development.

## Provisioning, deployment, backups

These live in a separate **private** repo:
[`amicalewifi/coworker-deploy`](https://github.com/amicalewifi/coworker-deploy).

That repo contains the VPS bootstrap script, the daily backup job, and any
ops snippets (cron, logrotate). Keeping them out of this public repo avoids
publishing the VPS layout and keeps a place for any future encrypted secrets.
