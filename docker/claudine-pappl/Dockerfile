# Container Claudine — printer-application PAPPL pour l'imprimante virtuelle
# de l'Amicale du WiFi.
#
# Pourquoi PAPPL et pas CUPS : CUPS n'est pas un serveur IPP Everywhere
# conformant (manque media-col-database, urf-supported, pwg-raster-document-*,
# etc.) → install Windows 11 impossible. PAPPL (par l'auteur original de CUPS)
# est conçu exactement pour ce cas d'usage : exposer en IPP Everywhere une
# imprimante non-IPP, avec auth + accounting custom. Voir docker/claudine-pappl/
# claudine.c pour l'intégration.
#
# Build multi-stage : on compile PAPPL depuis source dans `build`, puis on
# copie le binaire + libs dans une image runtime minimale.

# ─── Stage 1 : build PAPPL + claudine.c ─────────────────────────────────
FROM debian:bookworm-slim AS build

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        autoconf \
        pkg-config \
        libcups2-dev \
        libcupsimage2-dev \
        libpng-dev \
        libjpeg62-turbo-dev \
        libusb-1.0-0-dev \
        libpam0g-dev \
        zlib1g-dev \
        libssl-dev \
        libavahi-client-dev \
        ca-certificates \
        curl \
 && rm -rf /var/lib/apt/lists/*

# Pin sur la dernière stable PAPPL au moment de la rédaction du plan
# (2026-05-05). Vérifier https://github.com/michaelrsweet/pappl/releases pour
# bumper. La 1.4.x est stable depuis 2024, branche supportée long terme.
ARG PAPPL_VERSION=1.4.10
WORKDIR /src/pappl
RUN curl -fsSL "https://github.com/michaelrsweet/pappl/archive/refs/tags/v${PAPPL_VERSION}.tar.gz" \
    | tar xz --strip-components=1
RUN ./configure \
        --enable-libjpeg \
        --enable-libpng \
        --enable-libusb \
        --with-dnssd=avahi \
        --prefix=/usr/local
RUN make -j"$(nproc)" && make install && ldconfig

# Build du binaire claudine custom (utilise l'API PAPPL fraîchement installée)
WORKDIR /work
COPY claudine.c Makefile ./
RUN make claudine

# ─── Stage 2 : runtime minimal ──────────────────────────────────────────
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive \
    AMICALE_PRINTER_HOST=192.168.1.10 \
    AMICALE_PRINTER_PORT=9100 \
    AMICALE_APP_URL=http://127.0.0.1:8081 \
    AMICALE_QUEUE_NAME=claudine

RUN apt-get update && apt-get install -y --no-install-recommends \
        libcups2 \
        libcupsimage2 \
        libpng16-16 \
        libjpeg62-turbo \
        libusb-1.0-0 \
        libavahi-client3 \
        avahi-daemon \
        dbus \
        ca-certificates \
        python3 \
        python3-requests \
        poppler-utils \
        ghostscript \
 && rm -rf /var/lib/apt/lists/*
# poppler-utils → pdfinfo pour le comptage de pages
# ghostscript   → conversion PostScript → PDF dans amicale-broker
# avahi/dbus    → mDNS broadcast par PAPPL (LAN discovery)

# Copie des libs PAPPL + du binaire compilé
COPY --from=build /usr/local/lib/libpappl.* /usr/local/lib/
COPY --from=build /usr/local/include/pappl /usr/local/include/pappl
COPY --from=build /work/claudine /usr/local/bin/claudine
RUN ldconfig

# Backend Python (logique broker inchangée — mêmes endpoints Spring,
# même PJL wrapping, même push raw 9100 — voir backend/amicale-broker)
COPY backend/amicale-broker /usr/local/bin/amicale-broker
RUN chmod 0755 /usr/local/bin/amicale-broker

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh

# Spool dir (volume monté par docker-compose pour persistance jobs)
RUN install -d -m 0755 /var/lib/pappl /etc/claudine

EXPOSE 8000

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
