package main

// SNMP helper pour lire le compteur "Imprimante / Couleur" de la Kyocera,
// utilisé en before/after autour de chaque job pour déterminer si le job a
// réellement consommé du toner couleur (delta > 0 = couleur, sinon mono).
//
// Pourquoi ce compteur précis :
//   OID 1.3.6.1.4.1.1347.42.3.1.2.1.1.1.2 = lifetime impressions Imprimante
//   en couleur. Vérifié contre l'admin web ("Compteur / Pages imprimées par
//   fonction" → ligne Imprimante, colonne Couleur).
//
// Ce compteur ne capture QUE les jobs réseau (Imprimante). Les pages issues
// du panneau (Copier, USB walk-up) vont sur une autre ligne du même tableau,
// donc elles ne polluent pas le delta. Cf. plan #117 / #115 et la doc SNMP
// du TASKalfa 352ci.

import (
	"context"
	"fmt"
	"time"

	"github.com/gosnmp/gosnmp"
)

const (
	oidImprimanteCouleur = "1.3.6.1.4.1.1347.42.3.1.2.1.1.1.2"
	snmpCommunity        = "public"
	snmpTimeout          = 5 * time.Second
	snmpRetries          = 1
)

// ReadImprimanteCouleur ouvre une connexion SNMP éphémère, lit la valeur
// courante du compteur "Imprimante Couleur" et la retourne. Échoue en
// remontant l'erreur au caller : on ne fait pas de retry interne, c'est à
// l'appelant de décider de fallback (cf. pollAndComplete).
func ReadImprimanteCouleur(ctx context.Context) (int, error) {
	client := &gosnmp.GoSNMP{
		Target:    printerHost,
		Port:      161,
		Community: snmpCommunity,
		Version:   gosnmp.Version2c,
		Timeout:   snmpTimeout,
		Retries:   snmpRetries,
		Context:   ctx,
	}
	if err := client.Connect(); err != nil {
		return 0, fmt.Errorf("snmp connect: %w", err)
	}
	defer client.Conn.Close()

	resp, err := client.Get([]string{oidImprimanteCouleur})
	if err != nil {
		return 0, fmt.Errorf("snmp get: %w", err)
	}
	if len(resp.Variables) == 0 {
		return 0, fmt.Errorf("snmp get: no variables in response")
	}
	v := resp.Variables[0]
	if v.Type == gosnmp.NoSuchObject || v.Type == gosnmp.NoSuchInstance {
		return 0, fmt.Errorf("snmp get: OID %s not found", oidImprimanteCouleur)
	}
	val, ok := v.Value.(int)
	if !ok {
		// gosnmp peut retourner uint, uint32, int32 selon le ASN.1 tag. On
		// gère les variantes courantes ; sinon, on tente une coercion via
		// gosnmp.ToBigInt qui couvre tous les cas numériques.
		bi := gosnmp.ToBigInt(v.Value)
		if bi == nil {
			return 0, fmt.Errorf("snmp get: unexpected value type %T", v.Value)
		}
		return int(bi.Int64()), nil
	}
	return val, nil
}
