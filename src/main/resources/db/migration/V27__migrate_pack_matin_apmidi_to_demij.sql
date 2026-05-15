-- ============================================================
-- V27 — Re-mappe les lignes PACK_MATIN/PACK_APMIDI vers PACK_DEMIJ
-- ============================================================
-- Fusion des deux demi-journées (matin / après-midi) en un pack unique
-- PACK_DEMIJ — l'horaire n'était pas réellement contraint côté business,
-- la distinction matin/AP-M ajoutait de la complexité sans valeur.

UPDATE members
SET membership = 'PACK_DEMIJ'
WHERE membership IN ('PACK_MATIN', 'PACK_APMIDI');

UPDATE pack_transactions
SET membership = 'PACK_DEMIJ'
WHERE membership IN ('PACK_MATIN', 'PACK_APMIDI');

-- Note : les valeurs PACK_MATIN et PACK_APMIDI restent dans l'enum PG
-- (la suppression d'une valeur d'enum exigerait de recréer le type
-- complet). Plus aucune ligne ni aucun code Java ne les référence.
