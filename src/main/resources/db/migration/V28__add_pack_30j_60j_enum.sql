-- ============================================================
-- V28 — Ajoute PACK_30J et PACK_60J à l'enum membership_type
-- ============================================================
-- Nouveaux paliers longs (30 / 60 jours) ajoutés au catalogue.

ALTER TYPE membership_type ADD VALUE IF NOT EXISTS 'PACK_30J';
ALTER TYPE membership_type ADD VALUE IF NOT EXISTS 'PACK_60J';
