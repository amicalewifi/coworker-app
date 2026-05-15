-- ============================================================
-- V26 — Ajoute PACK_DEMIJ à l'enum membership_type
-- ============================================================
-- Pré-requis pour la migration V27 qui re-mappe les lignes existantes
-- PACK_MATIN/PACK_APMIDI vers PACK_DEMIJ. Doit être en migration séparée
-- car PostgreSQL n'autorise pas l'usage d'une nouvelle valeur d'enum dans
-- la même transaction qu'ALTER TYPE ADD VALUE.

ALTER TYPE membership_type ADD VALUE IF NOT EXISTS 'PACK_DEMIJ';
