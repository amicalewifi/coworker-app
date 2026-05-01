#!/usr/bin/env bash
# Bootstrap an Ubuntu 24.04 (noble) VPS to run the amicale-wifi Docker stack.
# Idempotent — re-running is safe.
#
# Usage (as root):
#   sudo bash bootstrap.sh
#
# The repo is private, so the script generates an ed25519 SSH key for the
# deploy user on first run, prints the public half, and exits. Add it as a
# Deploy Key on the GitHub repo (Settings → Deploy keys → Add deploy key,
# read-only is enough), then re-run.
#
# Optional env vars:
#   REPO_URL  — git URL to clone (default: SSH clone of jreuse-code/amicale)
#   APP_DIR   — install directory (default: /opt/amicale)

set -euo pipefail

REPO_URL="${REPO_URL:-git@github.com:jreuse-code/amicale.git}"
APP_DIR="${APP_DIR:-/opt/amicale}"
REPO_DIR="${APP_DIR}/repo"
DEPLOY_USER="${DEPLOY_USER:-deploy}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Please run as root (sudo bash $0)" >&2
  exit 1
fi

log() { printf '\n\e[1;34m==> %s\e[0m\n' "$*"; }

log "1/8  apt update + base packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl gnupg ufw git

log "2/8  Docker Engine + compose plugin (official repo)"
if ! command -v docker >/dev/null 2>&1; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    | gpg --dearmor --yes -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu noble stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
else
  echo "docker already installed: $(docker --version)"
fi

log "3/8  deploy user + docker group"
if ! id "${DEPLOY_USER}" >/dev/null 2>&1; then
  useradd -m -s /bin/bash "${DEPLOY_USER}"
fi
usermod -aG docker "${DEPLOY_USER}"

log "4/8  UFW (allow 22, 80, 443; deny everything else inbound)"
ufw --force reset >/dev/null
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

log "5/8  SSH key for ${DEPLOY_USER} + GitHub host trust"
DEPLOY_HOME="$(getent passwd "${DEPLOY_USER}" | cut -d: -f6)"
install -d -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" -m 0700 "${DEPLOY_HOME}/.ssh"
if [[ ! -f "${DEPLOY_HOME}/.ssh/id_ed25519" ]]; then
  sudo -u "${DEPLOY_USER}" ssh-keygen -t ed25519 -N '' \
    -f "${DEPLOY_HOME}/.ssh/id_ed25519" \
    -C "${DEPLOY_USER}@$(hostname)"
fi
KNOWN="${DEPLOY_HOME}/.ssh/known_hosts"
touch "${KNOWN}" && chown "${DEPLOY_USER}:${DEPLOY_USER}" "${KNOWN}" && chmod 0644 "${KNOWN}"
if ! grep -q '^github.com ' "${KNOWN}"; then
  ssh-keyscan -t ed25519,rsa github.com >> "${KNOWN}" 2>/dev/null
fi

log "6/8  Clone or update repo at ${REPO_DIR}"
install -d -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" "${APP_DIR}"
if [[ -d "${REPO_DIR}/.git" ]]; then
  sudo -u "${DEPLOY_USER}" git -C "${REPO_DIR}" pull --ff-only
else
  if ! sudo -u "${DEPLOY_USER}" git clone "${REPO_URL}" "${REPO_DIR}"; then
    cat <<EOF >&2

  >>> git clone failed. The repo is private — add the deploy key below
      to https://github.com/jreuse-code/amicale/settings/keys (read-only
      is enough), then re-run this script.

$(cat "${DEPLOY_HOME}/.ssh/id_ed25519.pub")

EOF
    exit 1
  fi
fi

log "7/8  .env file"
ENV_FILE="${REPO_DIR}/.env"
if [[ ! -f "${ENV_FILE}" ]]; then
  install -m 0600 -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" \
    "${REPO_DIR}/ops/.env.example" "${ENV_FILE}"
  cat <<EOF

  >>> Created ${ENV_FILE} from the template. <<<
  Edit it now with real secrets, then re-run this script
  (or run: cd ${REPO_DIR} && sudo -u ${DEPLOY_USER} docker compose up -d --build)

EOF
  exit 0
fi

# Refuse to start if any AMICALE_*_API_KEY is still empty
if grep -E '^AMICALE_(AKUVOX|UNIFI|ZAHLS)_(API_KEY|INSTANCE|DEVICE_PASS)=$' "${ENV_FILE}" >/dev/null; then
  echo "Some required secrets in ${ENV_FILE} are still empty. Fill them in then re-run." >&2
  grep -E '^AMICALE_(AKUVOX|UNIFI|ZAHLS)_(API_KEY|INSTANCE|DEVICE_PASS)=$' "${ENV_FILE}" >&2
  exit 1
fi

log "8/8  docker compose up -d --build"
cd "${REPO_DIR}"
sudo -u "${DEPLOY_USER}" docker compose pull --ignore-buildable || true
sudo -u "${DEPLOY_USER}" docker compose up -d --build

cat <<EOF

  Done.

  Stack:
    https://coworker.amicalewifi.ch        (Caddy → app:8081, auto-TLS)
    127.0.0.1:5432                          (Postgres, localhost only)
    http://127.0.0.1:5050                   (pgAdmin, localhost only)

  Useful commands (run as ${DEPLOY_USER} or via sudo -u ${DEPLOY_USER}):
    docker compose -f ${REPO_DIR}/docker-compose.yml ps
    docker compose -f ${REPO_DIR}/docker-compose.yml logs -f app
    docker compose -f ${REPO_DIR}/docker-compose.yml restart app

EOF
