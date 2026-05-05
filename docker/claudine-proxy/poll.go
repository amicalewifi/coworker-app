package main

// pollAndComplete : goroutine lancée après chaque Print-Job qui poll la
// Kyocera via Get-Job-Attributes jusqu'à ce que le job se termine (ou
// timeout 10 min), puis appelle /complete ou /error côté Spring.
//
// IPP job-state values (RFC 8011 §5.3.7) :
//   3 = pending
//   4 = pending-held
//   5 = processing
//   6 = processing-stopped
//   7 = canceled
//   8 = aborted
//   9 = completed

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"sync/atomic"
	"time"
)

const (
	jobStatePending          = 3
	jobStatePendingHeld      = 4
	jobStateProcessing       = 5
	jobStateProcessingStopped = 6
	jobStateCanceled         = 7
	jobStateAborted          = 8
	jobStateCompleted        = 9
)

const (
	pollInterval = 5 * time.Second
	pollDeadline = 10 * time.Minute
)

// reqIDCounter : on incrémente atomiquement pour chaque requête IPP envoyée
// à la Kyocera (request-id IPP, doit être > 0 et unique par session).
var reqIDCounter uint32

func nextReqID() uint32 {
	return atomic.AddUint32(&reqIDCounter, 1)
}

func pollAndComplete(jobID, kyoceraJobURI string, color, duplex bool) {
	ctx, cancel := context.WithTimeout(context.Background(), pollDeadline)
	defer cancel()

	ticker := time.NewTicker(pollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			log.Printf("[claudine-proxy] poll timeout job=%s uri=%s",
				jobID, kyoceraJobURI)
			reportError(jobID, fmt.Sprintf(
				"polling Kyocera timeout après 10 min, état job inconnu (kyocera-uri=%s)",
				kyoceraJobURI))
			return
		case <-ticker.C:
			state, impressions, sheets, kColor, kDuplex, err := fetchJobState(ctx, kyoceraJobURI)
			if err != nil {
				log.Printf("[claudine-proxy] poll job=%s: %v", jobID, err)
				continue // retry au prochain tick
			}

			switch state {
			case jobStateCompleted:
				// Source de vérité pour color/duplex : la Kyocera (kColor /
				// kDuplex) prime sur ce qu'on a peeké côté handler — celui-ci
				// peut être faux pour les flows Create-Job + Send-Document
				// (macOS AirPrint) où les attributs sont dans le
				// Send-Document qu'on n'intercepte pas. On garde le OR pour
				// le cas inverse théorique (peek a vu, Kyocera n'expose pas).
				color = color || kColor
				duplex = duplex || kDuplex

				// Choix de l'unité de billing :
				//   1. sheets si la Kyocera le rapporte (job-media-sheets-completed)
				//   2. sinon : impressions / 2 (arrondi sup) si duplex →
				//      reconstitue le nombre de feuilles physiques quand le
				//      firmware ne supporte pas l'attribut PWG dédié
				//   3. sinon impressions (per-side, recto)
				//   4. en dernier recours 1 (jamais de free print silencieux)
				var billUnit int
				var unitLabel string
				switch {
				case sheets > 0:
					billUnit = sheets
					unitLabel = "sheets"
				case duplex && impressions > 0:
					billUnit = (impressions + 1) / 2
					unitLabel = "sheets-from-impressions"
				case impressions > 0:
					billUnit = impressions
					unitLabel = "impressions"
				default:
					log.Printf("[claudine-proxy] job=%s completed but no count reported (impressions=%d sheets=%d), defaulting to 1",
						jobID, impressions, sheets)
					billUnit = 1
					unitLabel = "fallback"
				}
				log.Printf("[claudine-proxy] job=%s completed: %d %s (color=%v duplex=%v impressions=%d sheets=%d)",
					jobID, billUnit, unitLabel, color, duplex, impressions, sheets)
				reportComplete(jobID, billUnit, color, duplex)
				return
			case jobStateCanceled, jobStateAborted:
				log.Printf("[claudine-proxy] job=%s ended state=%d", jobID, state)
				reportError(jobID, fmt.Sprintf("Kyocera returned job-state=%d", state))
				return
			default:
				// pending/processing/etc. : on continue à poll
			}
		}
	}
}

// fetchJobState envoie Get-Job-Attributes à la Kyocera et parse la réponse.
// Retourne sheets (peut être 0 si non supporté par firmware), color, duplex
// (vrai état du job côté Kyocera, autoritaire) en plus de state +
// impressions.
func fetchJobState(ctx context.Context, jobURI string) (state, impressions, sheets int, color, duplex bool, err error) {
	body := BuildGetJobAttributes(jobURI, nextReqID())
	req, err := http.NewRequestWithContext(ctx, "POST",
		"https://"+printerHost+":"+printerPort+"/ipp/print",
		bytes.NewReader(body))
	if err != nil {
		return 0, 0, 0, false, false, err
	}
	req.Header.Set("Content-Type", "application/ipp")
	if kyoceraUser != "" {
		req.SetBasicAuth(kyoceraUser, kyoceraPass)
	}

	resp, err := kyoceraClient.Do(req)
	if err != nil {
		return 0, 0, 0, false, false, err
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return 0, 0, 0, false, false, err
	}
	return ExtractJobState(respBody)
}

// reportComplete + reportError utilisent leur propre context. Timeout
// généreux (45s) pour laisser le retry exponential backoff de spring.do()
// faire son travail : 3 tentatives × ~10s chacune (timeout HTTP) +
// ~3.5s de backoff cumulé (500ms+1s+2s). Sans cette marge, un Spring
// momentanément lent (GC pause, redéploiement, etc.) provoquait un job
// imprimé physiquement mais non débité côté coworker-app — observé en
// prod le 2026-05-05 sur le job 01b3cb9c (free print accidentel).
//
// pages = unité de billing déjà calculée (sheets ou impressions, copies
// incluses dans le total côté Kyocera). copies passé à 1 côté Spring car
// le multiplicateur copies est déjà appliqué par la Kyocera.
func reportComplete(jobID string, pages int, color, duplex bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	if err := spring.Complete(ctx, jobID, pages, 1, color, duplex); err != nil {
		log.Printf("[claudine-proxy] spring complete job=%s: %v", jobID, err)
	}
}

func reportError(jobID, message string) {
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	if err := spring.Error(ctx, jobID, message); err != nil {
		log.Printf("[claudine-proxy] spring error job=%s: %v", jobID, err)
	}
}
