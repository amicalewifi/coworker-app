package main

// Client HTTP minimal pour les endpoints d'accounting de coworker-app.
// Contrat : tous les calls injectent X-Print-Broker-Key, body JSON.
//
// Endpoints utilisés :
//   POST /api/v1/print/submit               (deferred billing : pages=0)
//   POST /api/v1/print/{jobId}/complete     (body riche : pages, copies, color, duplex)
//   POST /api/v1/print/{jobId}/error        (body : message)

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"
)

var (
	ErrUnauthorized        = errors.New("invalid token")
	ErrInsufficientCredits = errors.New("insufficient credits")
	ErrBadRequest          = errors.New("bad request")
	ErrTransient           = errors.New("transient error")
)

type SpringClient struct {
	baseURL string
	key     string
	http    *http.Client
}

type submitReq struct {
	Token             string `json:"token"`
	Pages             int    `json:"pages"`
	Filename          string `json:"filename"`
	Copies            int    `json:"copies"`
	Color             bool   `json:"color"`
	Duplex            bool   `json:"duplex"`
	SubmittedUsername string `json:"submittedUsername"`
}

type submitResp struct {
	JobID       string `json:"jobId"`
	CreditsCost int    `json:"creditsCost"`
}

// Submit crée un PrinterJob côté Spring avec pages=0 (deferred billing).
// Le coût final sera calculé au /complete avec les vraies métadonnées.
// Spring valide le token et crée le job en status=PRINTING sans débit.
func (s *SpringClient) Submit(ctx context.Context, token, email string) (string, error) {
	body, _ := json.Marshal(submitReq{
		Token:             token,
		Pages:             0,
		Filename:          "ipp-job",
		Copies:            1,
		Color:             false,
		Duplex:            false,
		SubmittedUsername: email,
	})
	var resp submitResp
	if err := s.do(ctx, "POST", "/api/v1/print/submit", body, &resp); err != nil {
		return "", err
	}
	return resp.JobID, nil
}

type completeReq struct {
	Pages  int  `json:"pages"`
	Copies int  `json:"copies"`
	Color  bool `json:"color"`
	Duplex bool `json:"duplex"`
}

// Complete finalise le job et débite le membre selon les pages réelles
// rapportées par la Kyocera.
func (s *SpringClient) Complete(ctx context.Context, jobID string, pages, copies int, color, duplex bool) error {
	body, _ := json.Marshal(completeReq{Pages: pages, Copies: copies, Color: color, Duplex: duplex})
	return s.do(ctx, "POST", "/api/v1/print/"+jobID+"/complete", body, nil)
}

type errorReq struct {
	Message string `json:"message"`
}

// Error marque le job en erreur (timeout polling, état canceled/aborted, etc.).
// Aucun débit n'est effectué côté Spring.
func (s *SpringClient) Error(ctx context.Context, jobID, msg string) error {
	body, _ := json.Marshal(errorReq{Message: msg})
	return s.do(ctx, "POST", "/api/v1/print/"+jobID+"/error", body, nil)
}

// do exécute un POST avec retry exponential backoff (3 tentatives max). Les
// erreurs réseau (EOF, context deadline, connection refused, etc.) et les
// 5xx sont retentées. 4xx sont retournés immédiatement (mappés vers les
// sentinel errors). Le backoff est ctx-aware : on bail proprement si le
// parent context expire pendant le sleep.
func (s *SpringClient) do(ctx context.Context, method, path string, body []byte, out any) error {
	backoff := 500 * time.Millisecond
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		req, err := http.NewRequestWithContext(ctx, method, s.baseURL+path, bytes.NewReader(body))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Print-Broker-Key", s.key)

		resp, err := s.http.Do(req)
		if err != nil {
			// Erreur transport (EOF, connection refused, context deadline, etc.)
			// → retry. C'est le cas observé en prod où un Spring momentanément
			// lent renvoyait EOF/timeout et le job restait orphelin sans débit.
			lastErr = err
			if waitErr := sleepCtx(ctx, backoff); waitErr != nil {
				return waitErr
			}
			backoff *= 2
			continue
		}

		switch {
		case resp.StatusCode == 401:
			resp.Body.Close()
			return ErrUnauthorized
		case resp.StatusCode == 402:
			resp.Body.Close()
			return ErrInsufficientCredits
		case resp.StatusCode == 422:
			resp.Body.Close()
			return ErrBadRequest
		case resp.StatusCode >= 200 && resp.StatusCode < 300:
			defer resp.Body.Close()
			if out != nil {
				return json.NewDecoder(resp.Body).Decode(out)
			}
			_, _ = io.Copy(io.Discard, resp.Body)
			return nil
		case resp.StatusCode >= 500:
			_, _ = io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			lastErr = fmt.Errorf("%w: HTTP %d", ErrTransient, resp.StatusCode)
			if waitErr := sleepCtx(ctx, backoff); waitErr != nil {
				return waitErr
			}
			backoff *= 2
			continue
		default:
			resp.Body.Close()
			return fmt.Errorf("unexpected status %d", resp.StatusCode)
		}
	}
	if lastErr == nil {
		lastErr = ErrTransient
	}
	return lastErr
}

// sleepCtx dort `d` sauf si le context expire avant — dans ce cas retourne
// l'erreur du context. Évite de gaspiller le budget temps du parent en
// time.Sleep aveugle quand le retry n'aboutira de toute façon plus.
func sleepCtx(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}
