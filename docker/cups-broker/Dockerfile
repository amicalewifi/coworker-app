# Container Claudine — broker CUPS pour l'imprimante virtuelle de l'Amicale.
# Reçoit les jobs IPP des members, compte les pages localement (pdfinfo),
# autorise le job auprès de coworker-app, puis pousse le PDF en RAW/PJL
# vers la Kyocera 192.168.1.10:9100 via le tunnel WireGuard du host.
#
# Pas d'olbat/cupsd : non maintenu depuis 2020 et x86-only — on construit
# nous-mêmes sur debian:bookworm-slim, qui supporte amd64 et arm64.

FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive \
    AMICALE_PRINTER_HOST=192.168.1.10 \
    AMICALE_PRINTER_PORT=9100 \
    AMICALE_APP_URL=http://127.0.0.1:8081 \
    AMICALE_QUEUE_NAME=claudine

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        cups \
        cups-filters \
        cups-bsd \
        poppler-utils \
        ghostscript \
        python3 \
        python3-requests \
        ca-certificates \
 && rm -rf /var/lib/apt/lists/*
# ghostscript : convertir PostScript → PDF dans le backend. Quand la queue
# CUPS du broker est en mode raw (cas par défaut, IPP Everywhere ne peut pas
# self-probe notre backend custom), macOS envoie du PostScript directement.
# La Kyocera attend du PDF (PJL ENTER LANGUAGE=PDF), donc le backend convertit.

# Notre backend Python custom — invoqué par cupsd quand un job arrive sur la
# queue claudine. Doit être exécutable et appartenir à root (CUPS le refuse sinon).
COPY claudine.ppd /usr/share/ppd/claudine.ppd
COPY backend/amicale-broker /usr/lib/cups/backend/amicale-broker
RUN chmod 0700 /usr/lib/cups/backend/amicale-broker \
 && chown root:root /usr/lib/cups/backend/amicale-broker

COPY cupsd.conf /etc/cups/cupsd.conf
# PAM permissif — CUPS doit valider HTTP basic-auth pour exposer les creds
# au backend, mais on ne veut pas qu'il valide contre /etc/passwd. Le backend
# fait la vraie auth contre coworker-app via le print_token.
COPY pam-cups /etc/pam.d/cups

# ipp-shim — middleware HTTP/IPP qui absorbe l'op Microsoft 0x4000
# ("windows-ext"). Voir le commentaire en tête du script. Caddy reverse-proxy
# vers le shim (port 6311) au lieu de cupsd directement (6310).
COPY ipp-shim /usr/local/bin/ipp-shim
RUN chmod 0755 /usr/local/bin/ipp-shim

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh

EXPOSE 631

# Pas de USER — cupsd a besoin de root pour binder /var/run/cups et passer
# les credentials aux backends. Il drop privileges lui-même pour les filtres.
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
