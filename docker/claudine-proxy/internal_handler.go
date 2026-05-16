package main

// Endpoint HTTP interne consommé par le sweeper côté Spring
// (PrinterJobReconciler). Permet d'interroger l'état d'un job côté Kyocera
// pour un PrinterJob orphelin (PRINTING depuis longtemps sans /complete
// reçu), sans déclencher de goroutine de polling.
//
// Auth : même X-Print-Broker-Key que les endpoints Spring. Bind au listener
// public mais non routé par Caddy → uniquement joignable depuis le réseau
// Docker interne.

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"time"
)

type pollJobReq struct {
	KyoceraJobURI string `json:"kyoceraJobUri"`
}

type pollJobResp struct {
	State     int    `json:"state"`     // RFC 8011 §5.3.7 : 3=pending, 5=processing, 7=canceled, 8=aborted, 9=completed
	BillUnit  int    `json:"billUnit"`  // unité de débit (sheets ou impressions, copies incluses)
	UnitLabel string `json:"unitLabel"` // origine : "sheets" / "sheets-from-impressions" / "impressions" / "fallback"
	Color     bool   `json:"color"`
	Duplex    bool   `json:"duplex"`
}

func internalPollJobHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if r.Header.Get("X-Print-Broker-Key") != brokerKey {
		http.Error(w, "broker key invalid", http.StatusUnauthorized)
		return
	}
	var req pollJobReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	if req.KyoceraJobURI == "" {
		http.Error(w, "missing kyoceraJobUri", http.StatusBadRequest)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	state, impressions, sheets, kColor, kDuplex, err := fetchJobState(ctx, req.KyoceraJobURI)
	if err != nil {
		log.Printf("[claudine-proxy] internal poll-job %s: %v", req.KyoceraJobURI, err)
		http.Error(w, "kyocera unreachable", http.StatusBadGateway)
		return
	}

	billUnit, label := computeBillUnit(sheets, impressions, kDuplex)
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(pollJobResp{
		State:     state,
		BillUnit:  billUnit,
		UnitLabel: label,
		Color:     kColor,
		Duplex:    kDuplex,
	})
}
