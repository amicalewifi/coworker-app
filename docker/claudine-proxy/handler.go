package main

import (
	"bytes"
	"context"
	"encoding/binary"
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

// IPP operation IDs (RFC 8011).
// Notre proxy fait Submit + auth uniquement pour les ops qui soumettent un
// job réel ; les autres (discovery, validation, status) sont pass-through
// transparent vers la Kyocera. Sans cette distinction, on créerait un
// PrinterJob orphelin pour chaque Get-Printer-Attributes envoyé par
// Windows pendant l'install (et l'auth Basic forcée casserait le probe
// initial qui doit pouvoir réussir sans creds).
const (
	opPrintJob   = 0x0002
	opCreateJob  = 0x0005
)

// printHandler : auth + Spring Submit pour Print-Job/Create-Job, pass-through
// pour le reste.
func printHandler(proxy *httputil.ReverseProxy) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// GET et autres méthodes : pass-through (la Kyocera sert un endpoint
		// web minimal sur GET utile pour les health checks).
		if r.Method != http.MethodPost {
			proxy.ServeHTTP(w, r)
			return
		}

		// Peek les 8 premiers octets du body pour lire l'op-id IPP. On
		// reconstitue ensuite le body complet pour le forward (peek + reste
		// via MultiReader). Cela ne touche pas au streaming des PDF lourds —
		// on lit juste 8 octets, le reste reste en stream.
		header := make([]byte, 8)
		n, _ := io.ReadFull(r.Body, header)
		header = header[:n]
		r.Body = io.NopCloser(io.MultiReader(bytes.NewReader(header), r.Body))

		var opID uint16
		if n >= 4 {
			opID = binary.BigEndian.Uint16(header[2:4])
		}

		// Ops de discovery/status : pass-through transparent. Pas d'auth, pas
		// de Submit. La Kyocera répond directement (capabilities, état, etc.)
		// et Windows IPP Class Driver est satisfait sans qu'on intervienne.
		if opID != opPrintJob && opID != opCreateJob {
			proxy.ServeHTTP(w, r)
			return
		}

		// Ici : opID = Print-Job ou Create-Job. On exige l'auth Basic et on
		// crée le PrinterJob côté Spring avant de forwarder.
		email, token, ok := r.BasicAuth()
		if !ok || email == "" || token == "" {
			w.Header().Set("WWW-Authenticate", `Basic realm="Claudine"`)
			http.Error(w, "auth required", http.StatusUnauthorized)
			return
		}

		jobID, err := spring.Submit(r.Context(), token, email)
		if err != nil {
			log.Printf("[claudine-proxy] spring submit (email=%s op=0x%04x): %v",
				email, opID, err)
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
		log.Printf("[claudine-proxy] forwarding Print-Job spring-id=%s email=%s op=0x%04x",
			jobID, email, opID)
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

// modifyResponseFn lit la réponse IPP de la Kyocera et, si c'est une réponse
// Print-Job (présence de job-uri), lance le polling en goroutine. Les
// réponses Get-Printer-Attributes peuvent peser plusieurs MB (capabilities
// IPP Everywhere complètes) — d'où la limite haute à 4 MB. La réponse est
// restituée intacte au client (Windows IPP Class Driver lit ces capabilities
// pour s'auto-configurer).
func modifyResponseFn(resp *http.Response) error {
	const maxRead = 4 * 1024 * 1024
	buf := &bytes.Buffer{}
	_, err := io.Copy(buf, io.LimitReader(resp.Body, maxRead))
	if err != nil {
		return err
	}
	// Drain résiduel improbable (réponse > 4MB) — on logue pour audit.
	if extra, _ := io.Copy(io.Discard, resp.Body); extra > 0 {
		log.Printf("[claudine-proxy] response truncated: %d extra bytes discarded "
			+ "(consider raising maxRead)", extra)
	}
	resp.Body.Close()

	body := buf.Bytes()
	resp.Body = io.NopCloser(bytes.NewReader(body))
	resp.ContentLength = int64(len(body))
	resp.Header.Set("Content-Length", strconv.Itoa(len(body)))

	// Pas de jobID dans le contexte = c'était une op autre que Print-Job
	// (discovery, status, etc.) → rien à faire.
	jobID, _ := resp.Request.Context().Value(ctxJobID).(string)
	if jobID == "" || len(body) == 0 {
		return nil
	}

	jobURI, kyoceraJobID, err := ExtractJobURI(body)
	if err != nil || jobURI == "" {
		log.Printf("[claudine-proxy] Print-Job response without job-uri (spring-id=%s): %v",
			jobID, err)
		// Le job côté Spring restera en PRINTING ; pas de complete, pas de
		// débit. Optionnel : on pourrait reportError ici.
		return nil
	}

	log.Printf("[claudine-proxy] job dispatched spring-id=%s kyocera-id=%d uri=%s",
		jobID, kyoceraJobID, jobURI)
	go pollAndComplete(jobID, jobURI)
	return nil
}
