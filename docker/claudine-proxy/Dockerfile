# claudine-proxy — mini reverse-proxy IPPS Go entre Caddy et la Kyocera.
#
# Pourquoi ce remplaçant de PAPPL/CUPS : la Kyocera TASKalfa 352ci est elle-
# même IPP Everywhere conformante (testé : curl http://192.168.1.10:631/ipp/print
# renvoie 200), donc elle parle directement à Windows IPP Class Driver,
# AirPrint macOS/iOS, Mopria Android. On n'a plus qu'à intercaler un mini
# proxy qui authentifie le client et débite les crédits — le reste (capabilities
# IPP Everywhere, formats, conversion) est délégué à la Kyocera.
#
# Multi-stage build : Go static binary + Alpine runtime minimal.

FROM golang:1.22-alpine AS build

WORKDIR /src
COPY go.mod ./
COPY *.go ./

# CGO_ENABLED=0 + -ldflags='-s -w' = binary statique (pas de libc), compact.
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /out/claudine-proxy

FROM alpine:3.19

# ca-certificates : pour les calls HTTPS sortants vers Kyocera (cert
# auto-signé sera skippé via InsecureSkipVerify, mais la chain TLS de base
# est requise par crypto/tls quand même au niveau handshake).
RUN apk add --no-cache ca-certificates

COPY --from=build /out/claudine-proxy /usr/local/bin/claudine-proxy

USER nobody
EXPOSE 8000

ENTRYPOINT ["/usr/local/bin/claudine-proxy"]
