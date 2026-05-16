package main

// Store thread-safe d'attributs par job, partagé entre handler.go (qui pose
// les valeurs depuis le peek de Print-Job / Create-Job / Send-Document) et
// poll.go (qui les lit au moment de finaliser le job côté Spring).
//
// Pourquoi ce store : sur macOS AirPrint, les attributs print-color-mode /
// sides sont dans le Send-Document, pas dans le Create-Job. Le contexte
// HTTP du Create-Job est terminé quand le Send-Document arrive ; il faut
// donc un état partagé pour transporter les attributs d'une requête à
// l'autre. La Kyocera renvoie des valeurs DEFAULT-machine pour les
// attributs per-job (cf. ipp.go autour de Get-Job-Attributes), donc on
// préfère toujours la source request-side (peek) à la source Kyocera-side.
//
// Cycle de vie d'une entrée :
//   - Print-Job/Create-Job request : attrStore.Set(springID, attrs from peek)
//   - Response parsing : attrStore.LinkKyocera(kyoceraID, springID)
//   - Send-Document request (macOS uniquement) : attrStore.Set(springID, real attrs)
//   - pollAndComplete on state=COMPLETED : attrStore.Get(springID) puis Delete
//
// Garde-fou : un goroutine prune les entrées de plus de 30 min pour éviter
// les fuites si un job n'est jamais finalisé (timeout polling, crash, etc.).

import (
	"log"
	"sync"
	"time"
)

type JobAttrs struct {
	Color     bool
	Duplex    bool
	UpdatedAt time.Time
}

type jobAttrStore struct {
	mu             sync.RWMutex
	bySpring       map[string]JobAttrs // springID → attrs
	kyoceraToSpring map[int]string     // kyoceraID → springID
}

var attrStore = newJobAttrStore()

func newJobAttrStore() *jobAttrStore {
	s := &jobAttrStore{
		bySpring:        make(map[string]JobAttrs),
		kyoceraToSpring: make(map[int]string),
	}
	go s.pruneLoop()
	return s
}

// Set pose ou écrase les attributs pour un springID donné. UpdatedAt est
// stampé automatiquement (utilisé par la prune et le debug log).
func (s *jobAttrStore) Set(springID string, attrs JobAttrs) {
	attrs.UpdatedAt = time.Now()
	s.mu.Lock()
	s.bySpring[springID] = attrs
	s.mu.Unlock()
}

// Get retourne les attributs courants pour un springID, ou false si inconnu.
func (s *jobAttrStore) Get(springID string) (JobAttrs, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	a, ok := s.bySpring[springID]
	return a, ok
}

// Delete retire l'entrée d'un job finalisé. Idempotent. Nettoie aussi le
// mapping kyoceraID→springID associé (parcours linéaire, O(N) mais N est
// petit en pratique : nombre de jobs concurrents).
func (s *jobAttrStore) Delete(springID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.bySpring, springID)
	for k, v := range s.kyoceraToSpring {
		if v == springID {
			delete(s.kyoceraToSpring, k)
		}
	}
}

// LinkKyocera enregistre la correspondance kyoceraID → springID. Appelé
// depuis ippParseReader quand la réponse Print-Job/Create-Job de la
// Kyocera a livré son job-id. Indispensable pour que Send-Document puisse
// remonter au springID.
func (s *jobAttrStore) LinkKyocera(kyoceraID int, springID string) {
	if kyoceraID <= 0 {
		return
	}
	s.mu.Lock()
	s.kyoceraToSpring[kyoceraID] = springID
	s.mu.Unlock()
}

// KyoceraToSpring retourne le springID associé à un kyoceraID, ou "" si
// inconnu (Send-Document arrivé avant qu'on ait parsé la réponse Create-Job,
// ou kyoceraID inconnu — cas rare).
func (s *jobAttrStore) KyoceraToSpring(kyoceraID int) string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.kyoceraToSpring[kyoceraID]
}

// pruneLoop balaie les entrées orphelines toutes les 5 min. Un job qui n'a
// pas été touché depuis 30 min est considéré perdu — son pollAndComplete a
// soit complété (et Delete a été appelé), soit timeouté (10 min) sans
// faire le Delete (cas error path), soit est resté en attente d'un
// Send-Document qui n'est jamais venu (network drop).
const pruneTTL = 30 * time.Minute
const pruneInterval = 5 * time.Minute

func (s *jobAttrStore) pruneLoop() {
	ticker := time.NewTicker(pruneInterval)
	defer ticker.Stop()
	for range ticker.C {
		s.pruneOnce(time.Now())
	}
}

func (s *jobAttrStore) pruneOnce(now time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for springID, a := range s.bySpring {
		if now.Sub(a.UpdatedAt) > pruneTTL {
			log.Printf("[claudine-proxy] attrStore prune stale entry spring=%s (age=%s)",
				springID, now.Sub(a.UpdatedAt).Truncate(time.Second))
			delete(s.bySpring, springID)
			for k, v := range s.kyoceraToSpring {
				if v == springID {
					delete(s.kyoceraToSpring, k)
				}
			}
		}
	}
}
