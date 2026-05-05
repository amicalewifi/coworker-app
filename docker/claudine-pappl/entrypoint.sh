#!/usr/bin/env bash
# Entrypoint du container Claudine (PAPPL) :
#   1. Démarre dbus + avahi-daemon (requis par PAPPL pour le mDNS/DNS-SD).
#   2. Persiste les AMICALE_* dans /etc/claudine/amicale.env (filet de
#      sécurité pour amicale-broker.py s'il est invoqué hors du contexte
#      PAPPL — debug, dry-run, etc.).
#   3. exec /usr/local/bin/claudine en foreground (PAPPL gère SIGTERM/SIGINT).
set -euo pipefail

# ─── 1. dbus + avahi pour mDNS ───────────────────────────────────────────
# avahi a besoin de dbus pour fonctionner. Sur un container où systemd n'est
# pas présent, on les démarre manuellement. Tous deux écoutent en local et
# font le broadcast mDNS sur l'interface réseau du container (= host, vu que
# network_mode: host dans docker-compose.yml).
mkdir -p /run/dbus /var/run/avahi-daemon
# Cleanup pidfiles résiduels — au restart du container, les fichiers persistent
# si /run est sur un tmpfs partagé avec network_mode: host (ou si le précédent
# arrêt a été non-graceful). Sans ce cleanup, dbus-daemon refuse de démarrer
# avec "pid file exists".
rm -f /run/dbus/pid /run/avahi-daemon/pid
dbus-daemon --system --fork
avahi-daemon -D --no-rlimits

# ─── 2. Persistance des AMICALE_* pour le backend Python ────────────────
ENV_FILE=/etc/claudine/amicale.env
umask 077
mkdir -p /etc/claudine
env | grep '^AMICALE_' > "${ENV_FILE}"
chmod 0600 "${ENV_FILE}"
echo "[claudine] persisted $(wc -l <"${ENV_FILE}") AMICALE_* vars to ${ENV_FILE}"

# ─── 3. Lance le serveur PAPPL en foreground ────────────────────────────
# `claudine server` = sous-commande PAPPL standard pour rester en avant-plan.
# Trap SIGTERM/SIGINT relayés au binaire via exec (pas de subshell).
echo "[claudine] starting PAPPL server on :8000"
exec /usr/local/bin/claudine server
