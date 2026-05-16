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

// computeBillUnit choisit l'unité de billing à reporter à Spring, par
// ordre de confiance décroissante :
//
//  1. sheets (job-media-sheets-completed) — autoritaire si firmware le supporte
//  2. impressions / 2 (arrondi sup) si duplex — reconstitue les feuilles
//     physiques quand l'attribut PWG dédié manque
//  3. impressions (per-side) — simplex ou duplex inconnu
//  4. 1 (fallback) — jamais de free print silencieux
//
// Partagée par le polling continu (pollAndComplete) et le poll one-shot
// utilisé par le sweeper côté Spring (/internal/poll-job).
func computeBillUnit(sheets, impressions int, duplex bool) (int, string) {
	switch {
	case sheets > 0:
		return sheets, "sheets"
	case duplex && impressions > 0:
		return (impressions + 1) / 2, "sheets-from-impressions"
	case impressions > 0:
		return impressions, "impressions"
	default:
		return 1, "fallback"
	}
}

func pollAndComplete(jobID, kyoceraJobURI string, color, duplex bool) {
	// Libère printerMu sur TOUT chemin de sortie (complétion, error,
	// timeout, panic). Acquis dans handler.go avant le forward.
	defer printerMu.Unlock()

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
			attrStore.Delete(jobID)
			return
		case <-ticker.C:
			state, impressions, sheets, kColor, kDuplex, err := fetchJobState(ctx, kyoceraJobURI)
			if err != nil {
				log.Printf("[claudine-proxy] poll job=%s: %v", jobID, err)
				continue // retry au prochain tick
			}

			switch state {
			case jobStateCompleted:
				// Source de vérité pour color/duplex : le request-side peek
				// stocké dans attrStore (Print-Job inline pour Linux/Windows,
				// Send-Document pour macOS). On ne fait PAS de OR avec
				// kColor/kDuplex (per-job IPP attrs Kyocera) car la Kyocera
				// renvoie ses valeurs DEFAULT-machine pour ces attrs au
				// lieu des actual-job (cf. ipp.go autour de BuildGetJobAttributes).
				finalColor, finalDuplex := color, duplex
				stored, hasStored := attrStore.Get(jobID)
				if hasStored {
					finalColor, finalDuplex = stored.Color, stored.Duplex
				}

				// Counter-delta override : on relit le compteur SNMP
				// "Imprimante Couleur" et on compare au snapshot pris
				// avant le submit. Si le compteur a bougé, le job a
				// consommé du toner couleur (la vérité physique). Si non,
				// c'était mono même si le client a demandé color (cas
				// macOS auto-grayscale, panel "force B&W", etc.).
				// Sentinelle BeforeColor=-1 → la lecture avant a échoué,
				// on skip le delta et on fait confiance au request-side.
				if hasStored && stored.BeforeColor >= 0 {
					// Grace de 3s pour laisser la Kyocera flusher le compteur
					// après job-state=9.
					time.Sleep(3 * time.Second)
					afterColor, err := ReadImprimanteCouleur(ctx)
					if err != nil {
						log.Printf("[claudine-proxy] SNMP after read failed (job=%s): %v — falling back to request-side color",
							jobID, err)
					} else {
						delta := afterColor - stored.BeforeColor
						counterColor := delta > 0
						if counterColor != stored.Color {
							log.Printf("[claudine-proxy] color override job=%s: request=%v counter-delta=%v (Δ=%d before=%d after=%d)",
								jobID, stored.Color, counterColor, delta, stored.BeforeColor, afterColor)
						} else {
							log.Printf("[claudine-proxy] counter-delta confirms request job=%s: color=%v Δ=%d",
								jobID, counterColor, delta)
						}
						finalColor = counterColor
					}
				}

				billUnit, unitLabel := computeBillUnit(sheets, impressions, finalDuplex)
				if unitLabel == "fallback" {
					log.Printf("[claudine-proxy] job=%s completed but no count reported (impressions=%d sheets=%d), defaulting to 1",
						jobID, impressions, sheets)
				}
				log.Printf("[claudine-proxy] job=%s completed: %d %s (color=%v duplex=%v impressions=%d sheets=%d kyocera-color=%v kyocera-duplex=%v)",
					jobID, billUnit, unitLabel, finalColor, finalDuplex, impressions, sheets, kColor, kDuplex)
				reportComplete(jobID, billUnit, finalColor, finalDuplex)
				attrStore.Delete(jobID)
				return
			case jobStateCanceled, jobStateAborted:
				log.Printf("[claudine-proxy] job=%s ended state=%d", jobID, state)
				reportError(jobID, fmt.Sprintf("Kyocera returned job-state=%d", state))
				attrStore.Delete(jobID)
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

	// DEBUG TEMPORAIRE — dump des attributs IPP retournés par la Kyocera
	// pour comprendre quels attributs sont disponibles (notamment
	// print-color-mode, sides, et possiblement des extensions PWG comme
	// pwg-impressions-completed-col). À retirer une fois le diagnostic fait.
	log.Printf("[claudine-proxy] DEBUG Get-Job-Attributes response (%d bytes):\n%s",
		len(respBody), DumpAttrs(respBody))

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

// reportDispatched : pousse à Spring l'URI Kyocera attribuée au job, pour que
// le sweeper côté Spring puisse re-poller si /complete ne nous parvient
// jamais. Best-effort — un échec ne fait que désactiver ce filet pour CE job.
func reportDispatched(jobID, kyoceraJobURI string) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := spring.Dispatched(ctx, jobID, kyoceraJobURI); err != nil {
		log.Printf("[claudine-proxy] spring dispatched job=%s: %v", jobID, err)
	}
}
