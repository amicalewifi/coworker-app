-- ============================================================
-- V7 — Intégration Koalendar
--   · rooms.koalendar_slug  : identifiant du calendrier dans Koalendar
--   · room_bookings.koalendar_uid : UID de réservation Koalendar (pour màj/annul.)
-- ============================================================

ALTER TABLE rooms
    ADD COLUMN IF NOT EXISTS koalendar_slug TEXT UNIQUE;

ALTER TABLE room_bookings
    ADD COLUMN IF NOT EXISTS koalendar_uid TEXT UNIQUE;

COMMENT ON COLUMN rooms.koalendar_slug IS
    'Slug du calendrier Koalendar (ex: "salle-a" pour koalendar.com/e/salle-a). '
    'Webhook URL : /api/v1/koalendar/webhook/{slug}';

COMMENT ON COLUMN room_bookings.koalendar_uid IS
    'UID de la réservation côté Koalendar — permet la mise à jour et l''annulation via webhook.';
