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

// modifyResponseFn : pour les Print-Job (jobID en context), wrap resp.Body
// avec ippParseReader qui parse on-the-fly et lance le polling dès qu'il
// croise `job-uri`. Pour toutes les autres ops (discovery, status, etc.),
// pass-through total — zéro buffering, zéro touche.
func modifyResponseFn(resp *http.Response) error {
	jobID, _ := resp.Request.Context().Value(ctxJobID).(string)
	if jobID == "" {
		// Pas un Print-Job → on laisse la ReverseProxy stream le body brut
		// vers le client sans intervenir.
		return nil
	}
	resp.Body = &ippParseReader{
		upstream: resp.Body,
		jobID:    jobID,
	}
	return nil
}

// ippParseReader wrap le body d'une réponse Print-Job pour extraire le
// job-uri "au passage" : au fur et à mesure que les bytes circulent
// upstream → client (via la ReverseProxy), on les accumule en RAM jusqu'à
// trouver job-uri. Une fois trouvé, on free l'accum et on lance le polling.
//
// Mémoire bornée : on cap l'accumulation à 64 KB (les réponses Print-Job IPP
// font <1 KB en pratique, mais on garde une marge). Au-delà, on stoppe la
// parse — le client reçoit toujours le body complet car on ne fait que
// regarder les bytes en transit.
type ippParseReader struct {
	upstream io.ReadCloser
	jobID    string
	accum    []byte
	done     bool
}

const ippParseAccumCap = 64 * 1024

func (r *ippParseReader) Read(p []byte) (int, error) {
	n, err := r.upstream.Read(p)
	if n > 0 && !r.done {
		// Accumulate (jusqu'à la cap) et tenter le parse.
		if len(r.accum)+n <= ippParseAccumCap {
			r.accum = append(r.accum, p[:n]...)
			if len(r.accum) >= 8 {
				// ExtractJobURI tolère un body partiel : si l'attribut n'a
				// pas encore été reçu, parseAttributes s'arrête sans erreur
				// et on retentera au prochain Read.
				if jobURI, kyoceraJobID, perr := ExtractJobURI(r.accum); perr == nil && jobURI != "" {
					log.Printf("[claudine-proxy] job dispatched spring-id=%s kyocera-id=%d uri=%s",
						r.jobID, kyoceraJobID, jobURI)
					go pollAndComplete(r.jobID, jobURI)
					r.done = true
					r.accum = nil // free
				}
			}
		} else {
			// Cap atteinte sans trouver job-uri — on abandonne la parse
			// (le client continue de recevoir le body normalement).
			log.Printf("[claudine-proxy] Print-Job response without job-uri after %dB (spring-id=%s)",
				ippParseAccumCap, r.jobID)
			r.done = true
			r.accum = nil
		}
	}
	return n, err
}

func (r *ippParseReader) Close() error {
	if !r.done && r.jobID != "" {
		log.Printf("[claudine-proxy] Print-Job response closed without job-uri (spring-id=%s)", r.jobID)
	}
	return r.upstream.Close()
}
