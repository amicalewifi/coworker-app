#!/usr/bin/env bash
# Bootstrap script du container Claudine :
#  1. Lance cupsd en arrière-plan.
#  2. Attend que le socket soit prêt.
#  3. Crée la queue claudine via lpadmin si elle n'existe pas (idempotent).
#  4. Wait sur cupsd → le container vit aussi longtemps que CUPS.
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

# Le container meurt si CUPS sort.
trap 'echo "[claudine] received SIGTERM, stopping cupsd"; kill -TERM ${CUPSD_PID}; wait ${CUPSD_PID}; exit' TERM INT

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

if lpstat -p "${QUEUE}" >/dev/null 2>&1; then
    echo "[claudine] queue ${QUEUE} déjà présente"
else
    echo "[claudine] création de la queue ${QUEUE}"
    # `everywhere` génère un PPD à la volée via IPP — pratique pour notre
    # backend custom qui ne veut pas de PPD vendor. Si ça échoue (réseau,
    # backend pas encore probable), fallback sur le pilote raw.
    lpadmin -p "${QUEUE}" \
        -E \
        -v "amicale-broker:///${QUEUE}" \
        -m everywhere \
        -L "Imprimante virtuelle Claudine — l'Amicale du WiFi" \
    || lpadmin -p "${QUEUE}" \
        -E \
        -v "amicale-broker:///${QUEUE}" \
        -m raw \
        -L "Imprimante virtuelle Claudine — l'Amicale du WiFi"
fi

echo "[claudine] cupsd ready, queue ${QUEUE} configured, waiting for jobs"
wait ${CUPSD_PID}
