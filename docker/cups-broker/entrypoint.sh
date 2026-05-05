#!/usr/bin/env bash
# Bootstrap script du container Claudine :
#  1. Lance cupsd en arrière-plan.
#  2. Attend que le socket soit prêt.
#  3. Crée la queue claudine via lpadmin si elle n'existe pas (idempotent).
#  4. Lance ipp-shim (middleware HTTP/IPP qui absorbe l'op Microsoft 0x4000).
#  5. Wait sur les deux process — le container vit tant que les deux tournent.
set -euo pipefail

QUEUE="${AMICALE_QUEUE_NAME:-claudine}"

# Persiste les AMICALE_* dans un fichier que amicale-broker.py charge au
# démarrage. Workaround pour un bug observé : `PassEnv` dans cupsd.conf est
# correctement parsé par cupsd 2.4 mais les vars ne sont pas effectivement
# transmises aux backends spawned par cupsd. Le fichier garantit que le
# backend voit toujours les valeurs courantes du container.
ENV_FILE=/etc/cups/amicale.env
umask 077
env | grep '^AMICALE_' > "${ENV_FILE}"
chmod 0600 "${ENV_FILE}"
echo "[claudine] persisted $(wc -l <"${ENV_FILE}") AMICALE_* vars to ${ENV_FILE}"

# cupsd en foreground dans le subshell ; on capture son PID pour relayer
# proprement les signaux (SIGTERM du `docker stop` arrête CUPS proprement).
/usr/sbin/cupsd -f &
CUPSD_PID=$!

# Trap initial — on l'enrichira avec SHIM_PID après son démarrage.
trap 'echo "[claudine] received SIGTERM, stopping cupsd"; kill -TERM ${CUPSD_PID} 2>/dev/null; wait ${CUPSD_PID} 2>/dev/null; exit' TERM INT

# Attente courte que le socket soit dispo.
for _ in 1 2 3 4 5 6 7 8 9 10; do
    [[ -S /var/run/cups/cups.sock ]] && break
    sleep 0.5
done

if ! [[ -S /var/run/cups/cups.sock ]]; then
    echo "[claudine] cupsd ne répond pas — abort" >&2
    kill -TERM ${CUPSD_PID} 2>/dev/null || true
    exit 1
fi

# lpadmin -p est create-or-update : si la queue existe déjà, ses propriétés
# (notamment le PPD) sont remplacées. On applique systématiquement plutôt que
# de skip si présente, sinon une mise à jour de claudine.ppd dans l'image
# (ex: ajout du *1284DeviceID requis pour Windows 11) ne se propage pas tant
# que le volume CUPS est conservé.
if lpstat -p "${QUEUE}" >/dev/null 2>&1; then
    echo "[claudine] queue ${QUEUE} présente — réapplication du PPD"
else
    echo "[claudine] création de la queue ${QUEUE}"
fi
lpadmin -p "${QUEUE}" \
    -E \
    -v "amicale-broker:///${QUEUE}" \
    -P /usr/share/ppd/claudine.ppd \
    -L "Imprimante virtuelle Claudine — l'Amicale du WiFi"

# ipp-shim : intercepte l'op Microsoft 0x4000 ("windows-ext") qui bloque
# l'install Windows 11 et proxifie tout le reste vers cupsd. Caddy doit
# reverse-proxy vers le port 6311 (et plus 6310). Voir ipp-shim en tête de
# fichier pour le détail.
/usr/local/bin/ipp-shim &
SHIM_PID=$!
trap 'echo "[claudine] received SIGTERM, stopping cupsd + ipp-shim"; kill -TERM ${CUPSD_PID} ${SHIM_PID} 2>/dev/null; wait 2>/dev/null; exit' TERM INT

echo "[claudine] cupsd + ipp-shim ready, queue ${QUEUE} configured, waiting for jobs"
# Sort dès qu'un des deux process meurt (Bash 4.3+, dispo sur bookworm).
wait -n ${CUPSD_PID} ${SHIM_PID}
echo "[claudine] one of cupsd/ipp-shim exited — terminating container"
kill -TERM ${CUPSD_PID} ${SHIM_PID} 2>/dev/null || true
wait 2>/dev/null || true
