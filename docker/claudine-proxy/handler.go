package main

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strconv"
)

type ctxKey int

const (
	ctxJobID ctxKey = iota
	ctxEmail
)

// printHandler authentifie le client (Basic auth → token coworker-app),
// crée un job côté Spring (pages=0, billing déféré), puis délègue le forward
// streaming à la ReverseProxy.
func printHandler(proxy *httputil.ReverseProxy) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// GET / etc. : on forwarde direct (utile pour curl health-check, et
		// pour le endpoint web minimal que la Kyocera expose sur GET).
		if r.Method != http.MethodPost {
			proxy.ServeHTTP(w, r)
			return
		}

		email, token, ok := r.BasicAuth()
		if !ok || email == "" || token == "" {
			w.Header().Set("WWW-Authenticate", `Basic realm="Claudine"`)
			http.Error(w, "auth required", http.StatusUnauthorized)
			return
		}

		// Validate token + create PrinterJob (pages=0, deferred billing).
		// Le coût final sera calculé au /complete avec le page count réel
		// récupéré du polling Kyocera.
		jobID, err := spring.Submit(r.Context(), token, email)
		if err != nil {
			log.Printf("[claudine-proxy] spring submit (email=%s): %v", email, err)
			switch {
			case errors.Is(err, ErrUnauthorized):
				w.Header().Set("WWW-Authenticate", `Basic realm="Claudine"`)
				http.Error(w, "invalid token", http.StatusUnauthorized)
			case errors.Is(err, ErrInsufficientCredits):
				http.Error(w, "insufficient credits", http.StatusPaymentRequired)
			case errors.Is(err, ErrBadRequest):
				http.Error(w, "bad request", http.StatusUnprocessableEntity)
			default:
				http.Error(w, "internal error", http.StatusBadGateway)
			}
			return
		}

		ctx := context.WithValue(r.Context(), ctxJobID, jobID)
		ctx = context.WithValue(ctx, ctxEmail, email)
		log.Printf("[claudine-proxy] forwarding job spring-id=%s email=%s",
			jobID, email)
		proxy.ServeHTTP(w, r.WithContext(ctx))
	}
}

// directorFn réécrit toute requête entrante vers le endpoint canonique
// /ipp/print de la Kyocera, peu importe le path utilisé par le client
// (couvre /printers/claudine, /ipp/print, /ipp/print/claudine).
func directorFn(target *url.URL) func(*http.Request) {
	return func(req *http.Request) {
		req.URL.Scheme = target.Scheme
		req.URL.Host = target.Host
		req.URL.Path = "/ipp/print"
		req.URL.RawQuery = ""
		req.Host = target.Host
		// L'auth Basic du client est destinée à coworker-app, pas à la
		// Kyocera. On la strip pour ne pas que la Kyocera la valide contre
		// ses propres comptes (et potentiellement échoue).
		req.Header.Del("Authorization")
	}
}

// modifyResponseFn lit la réponse IPP de la Kyocera (petite, < 1KB en
// pratique), extrait le job-uri assigned par la Kyocera, et lance le polling
// en goroutine. La réponse est restituée intacte au client.
func modifyResponseFn(resp *http.Response) error {
	const maxRead = 64 * 1024
	buf := &bytes.Buffer{}
	_, err := io.Copy(buf, io.LimitReader(resp.Body, maxRead))
	if err != nil {
		return err
	}
	// Si on n'a pas tout lu (cas hyper rare pour un IPP response), on drain.
	_, _ = io.Copy(io.Discard, resp.Body)
	resp.Body.Close()

	body := buf.Bytes()
	resp.Body = io.NopCloser(bytes.NewReader(body))
	resp.ContentLength = int64(len(body))
	resp.Header.Set("Content-Length", strconv.Itoa(len(body)))

	if len(body) == 0 {
		return nil
	}

	jobURI, kyoceraJobID, err := ExtractJobURI(body)
	if err != nil || jobURI == "" {
		// Pas de job-uri : c'est probablement une réponse non-Print-Job
		// (Get-Printer-Attributes etc.) ou une erreur IPP. Pas d'action.
		return nil
	}

	jobID, _ := resp.Request.Context().Value(ctxJobID).(string)
	if jobID == "" {
		log.Printf("[claudine-proxy] no jobID in context (kyocera-id=%d uri=%s)",
			kyoceraJobID, jobURI)
		return nil
	}

	log.Printf("[claudine-proxy] job dispatched spring-id=%s kyocera-id=%d uri=%s",
		jobID, kyoceraJobID, jobURI)
	go pollAndComplete(jobID, jobURI)
	return nil
}
