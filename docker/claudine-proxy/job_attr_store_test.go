package main

import (
	"testing"
	"time"
)

func TestJobAttrStore_SetGet(t *testing.T) {
	s := newJobAttrStore()
	s.Set("spring-1", JobAttrs{Color: true, Duplex: false})
	got, ok := s.Get("spring-1")
	if !ok {
		t.Fatal("expected entry to exist")
	}
	if !got.Color || got.Duplex {
		t.Errorf("expected color=true duplex=false, got %+v", got)
	}
	if got.UpdatedAt.IsZero() {
		t.Error("expected UpdatedAt to be stamped")
	}
}

func TestJobAttrStore_GetUnknown(t *testing.T) {
	s := newJobAttrStore()
	if _, ok := s.Get("does-not-exist"); ok {
		t.Error("expected ok=false for unknown springID")
	}
}

func TestJobAttrStore_Overwrite(t *testing.T) {
	s := newJobAttrStore()
	s.Set("spring-1", JobAttrs{Color: false, Duplex: false})
	s.Set("spring-1", JobAttrs{Color: true, Duplex: true})
	got, _ := s.Get("spring-1")
	if !got.Color || !got.Duplex {
		t.Errorf("expected overwrite to apply, got %+v", got)
	}
}

func TestJobAttrStore_LinkKyocera(t *testing.T) {
	s := newJobAttrStore()
	s.Set("spring-1", JobAttrs{Color: true, Duplex: true})
	s.LinkKyocera(42, "spring-1")
	if got := s.KyoceraToSpring(42); got != "spring-1" {
		t.Errorf("expected spring-1, got %q", got)
	}
	if got := s.KyoceraToSpring(99); got != "" {
		t.Errorf("expected empty for unknown kyocera id, got %q", got)
	}
}

func TestJobAttrStore_LinkKyocera_IgnoresZero(t *testing.T) {
	s := newJobAttrStore()
	s.LinkKyocera(0, "spring-1")
	if got := s.KyoceraToSpring(0); got != "" {
		t.Errorf("expected empty (zero kyoceraID ignored), got %q", got)
	}
}

func TestJobAttrStore_Delete(t *testing.T) {
	s := newJobAttrStore()
	s.Set("spring-1", JobAttrs{Color: true})
	s.LinkKyocera(42, "spring-1")
	s.Delete("spring-1")

	if _, ok := s.Get("spring-1"); ok {
		t.Error("expected entry to be deleted")
	}
	if got := s.KyoceraToSpring(42); got != "" {
		t.Errorf("expected kyocera→spring mapping to be cleared, got %q", got)
	}
}

func TestJobAttrStore_PruneStaleEntries(t *testing.T) {
	s := newJobAttrStore()
	// Entrée vieille (UpdatedAt forcé dans le passé)
	s.bySpring["old"] = JobAttrs{Color: true, UpdatedAt: time.Now().Add(-1 * time.Hour)}
	s.kyoceraToSpring[100] = "old"
	// Entrée récente
	s.Set("fresh", JobAttrs{Color: false})

	s.pruneOnce(time.Now())

	if _, ok := s.Get("old"); ok {
		t.Error("expected old entry to be pruned")
	}
	if got := s.KyoceraToSpring(100); got != "" {
		t.Error("expected kyocera→spring mapping for pruned entry to be cleared")
	}
	if _, ok := s.Get("fresh"); !ok {
		t.Error("expected fresh entry to be kept")
	}
}
