// claudine-proxy — mini reverse-proxy IPPS qui s'intercale entre Caddy
// (TLS public) et la Kyocera (IPP Everywhere natif sur 192.168.1.10:443).
//
// Le proxy fait trois choses : (1) authentifier le client via Basic auth
// contre coworker-app, (2) forwarder le job IPP brut à la Kyocera en
// streaming (zero buffering du body de requête), (3) poller la Kyocera
// après le job pour récupérer le page count réel et débiter les crédits
// du membre via /api/v1/print/{jobId}/complete.
//
// Tout le travail "IPP Everywhere" (capabilities, conformance, formats
// PDF/PWG-Raster/URF) est délégué à la Kyocera elle-même qui est certifiée
// AirPrint+Mopria. Plus de hack côté serveur.

package main

import (
	"context"
	"crypto/tls"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"os/signal"
	"syscall"
	"time"
)

var (
	appURL      = mustEnv("AMICALE_APP_URL")
	brokerKey   = mustEnv("AMICALE_PRINT_BROKER_KEY")
	printerHost = mustEnv("AMICALE_PRINTER_HOST")
	printerPort = envOr("AMICALE_PRINTER_PORT", "443")
	listenAddr  = envOr("AMICALE_LISTEN_ADDR", "0.0.0.0:8000")
	kyoceraUser = os.Getenv("KYOCERA_USER")
	kyoceraPass = os.Getenv("KYOCERA_PASSWORD")
)

// Transport partagé entre la ReverseProxy et le client de polling Kyocera.
// Le cert TLS de la Kyocera est auto-signé, donc on skip la vérification
// (le tunnel WG entre VPS et coworking est déjà protégé par WireGuard).
//
// DisableKeepAlives = true : le firmware Kyocera ferme parfois les
// connexions HTTP keepalive de manière inattendue, ce qui provoque des
// "EOF" en plein stream du proxy (observé en prod 2026-05-05 sur 4
// requêtes Get-Printer-Attributes). En forçant une connexion fraîche par
// requête, on paie un handshake TLS supplémentaire (~50ms) en échange
// d'une fiabilité accrue. Acceptable vu notre throughput (1 print toutes
// les minutes max).
var insecureTransport = &http.Transport{
	TLSClientConfig:   &tls.Config{InsecureSkipVerify: true},
	DisableKeepAlives: true,
}

// Client HTTP pour le polling de la Kyocera (Get-Job-Attributes).
var kyoceraClient = &http.Client{Transport: insecureTransport, Timeout: 10 * time.Second}

// Client HTTP pour parler à coworker-app (loopback localhost).
var spring = &SpringClient{
	baseURL: appURL,
	key:     brokerKey,
	http:    &http.Client{Timeout: 10 * time.Second},
}

func main() {
	target, err := url.Parse("https://" + printerHost + ":" + printerPort)
	if err != nil {
		log.Fatalf("invalid printer URL: %v", err)
	}

	proxy := &httputil.ReverseProxy{
		Director:       directorFn(target),
		ModifyResponse: modifyResponseFn,
		Transport:      insecureTransport,
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			log.Printf("[claudine-proxy] proxy error %s %s: %v", r.Method, r.URL.Path, err)
			http.Error(w, "upstream error", http.StatusBadGateway)
		},
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/", printHandler(proxy))

	srv := &http.Server{
		Addr:        listenAddr,
		Handler:     mux,
		ReadTimeout: 0, // PDF streams peuvent durer ; pas de timeout global
	}

	go func() {
		log.Printf("[claudine-proxy] listening on %s, forwarding to %s/ipp/print",
			listenAddr, target.String())
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server: %v", err)
		}
	}()

	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGTERM, syscall.SIGINT)
	<-sigs
	log.Print("[claudine-proxy] shutting down")
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
}

func mustEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		log.Fatalf("env %s required", key)
	}
	return v
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
