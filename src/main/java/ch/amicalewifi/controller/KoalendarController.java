package ch.amicalewifi.controller;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/**
 * Webhook Koalendar — reçoit les événements de réservation et les synchronise
 * dans la base locale (table room_bookings) pour visibilité dans le dashboard admin.
 *
 * Chaque salle a un slug Koalendar configuré dans l'admin.
 * URL webhook à configurer dans Koalendar (Settings > Webhooks) :
 *   https://votre-domaine.ch/api/v1/koalendar/webhook/{slug}
 *
 * Le {slug} identifie la salle — exemple :
 *   Salle A     → /api/v1/koalendar/webhook/salle-a
 *   Conf Valais → /api/v1/koalendar/webhook/conf-valais
 *
 * Événements traités :
 *   event.created      → crée un RoomBooking CONFIRMED
 *   event.tentative    → crée un RoomBooking CONFIRMED (traité identiquement)
 *   event.rescheduled  → met à jour l'horaire du booking existant
 *   event.canceled     → passe le booking en CANCELLED
 */
@RestController
@RequestMapping("/api/v1/koalendar")
@RequiredArgsConstructor
@Slf4j
public class KoalendarController {

    private final RoomRepository        roomRepo;
    private final RoomBookingRepository bookingRepo;
    private final MemberRepository      memberRepo;

    private static final ZoneId CH_ZONE = ZoneId.of("Europe/Zurich");

    @PostMapping("/webhook/{slug}")
    @Transactional
    public ResponseEntity<Void> webhook(@PathVariable String slug,
                                        @RequestBody Map<String, Object> payload) {
        // Log complet du premier appel pour ajustement si besoin
        log.info("Koalendar webhook [{}]: {}", slug, payload);

        try {
            // ── Identifier la salle ────────────────────────────────
            Room room = roomRepo.findByKoalendarSlug(slug).orElse(null);
            if (room == null) {
                log.warn("Koalendar webhook: aucune salle avec slug='{}' — configurer le slug dans l'admin", slug);
                return ResponseEntity.ok().build();
            }

            // ── Type d'événement (event_type ou type) ─────────────
            String eventType = str(payload, "event_type");
            if (eventType == null) eventType = str(payload, "type");
            if (eventType == null) {
                log.warn("Koalendar webhook [{}]: champ event_type introuvable dans {}", slug, payload.keySet());
                return ResponseEntity.ok().build();
            }

            switch (eventType) {
                case "event.created", "event.tentative" -> handleCreated(room, payload);
                case "event.rescheduled"                -> handleRescheduled(payload);
                case "event.canceled"                   -> handleCanceled(payload);
                default -> log.debug("Koalendar webhook [{}]: event '{}' ignoré", slug, eventType);
            }

        } catch (Exception e) {
            // Toujours 200 pour éviter les retentatives Koalendar
            log.error("Koalendar webhook [{}] erreur: {}", slug, e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }

    // ── Handlers ────────────────────────────────────────────────────────────

    private void handleCreated(Room room, Map<String, Object> payload) {
        String uid       = extractUid(payload);
        String name      = extractInviteeName(payload);
        String email     = extractInviteeEmail(payload);
        LocalDateTime start = extractDateTime(payload, "start_at");
        LocalDateTime end   = extractDateTime(payload, "end_at");

        if (start == null || end == null) {
            log.warn("Koalendar handleCreated: start_at ou end_at manquant — payload={}", payload);
            return;
        }

        // Éviter les doublons si le webhook est rejoué
        if (uid != null && bookingRepo.findByKoalendarUid(uid).isPresent()) {
            log.debug("Koalendar: booking {} déjà présent — ignoré", uid);
            return;
        }

        // Retrouver le membre par email si possible
        Member member = (email != null) ? memberRepo.findByEmail(email).orElse(null) : null;

        RoomBooking booking = RoomBooking.builder()
                .room(room)
                .member(member)
                .organizerName(name)
                .date(start.toLocalDate())
                .startTime(start.toLocalTime())
                .endTime(end.toLocalTime())
                .status(BookingStatus.CONFIRMED)
                .title("Koalendar")
                .billedFromCredits(member != null)
                .koalendarUid(uid)
                .build();

        bookingRepo.save(booking);
        log.info("Koalendar: réservation créée — salle={} le={} {}–{} par={}",
                room.getName(), start.toLocalDate(), start.toLocalTime(), end.toLocalTime(), name);
    }

    private void handleRescheduled(Map<String, Object> payload) {
        String uid = extractUid(payload);
        if (uid == null) { log.warn("Koalendar rescheduled: uid manquant"); return; }

        bookingRepo.findByKoalendarUid(uid).ifPresentOrElse(b -> {
            LocalDateTime start = extractDateTime(payload, "start_at");
            LocalDateTime end   = extractDateTime(payload, "end_at");
            if (start == null || end == null) return;
            b.setDate(start.toLocalDate());
            b.setStartTime(start.toLocalTime());
            b.setEndTime(end.toLocalTime());
            b.setUpdatedAt(LocalDateTime.now());
            bookingRepo.save(b);
            log.info("Koalendar: réservation {} déplacée → {} {}–{}", uid, start.toLocalDate(), start.toLocalTime(), end.toLocalTime());
        }, () -> log.warn("Koalendar rescheduled: booking uid='{}' introuvable", uid));
    }

    private void handleCanceled(Map<String, Object> payload) {
        String uid = extractUid(payload);
        if (uid == null) { log.warn("Koalendar canceled: uid manquant"); return; }

        bookingRepo.findByKoalendarUid(uid).ifPresentOrElse(b -> {
            b.setStatus(BookingStatus.CANCELLED);
            b.setUpdatedAt(LocalDateTime.now());
            bookingRepo.save(b);
            log.info("Koalendar: réservation {} annulée", uid);
        }, () -> log.warn("Koalendar canceled: booking uid='{}' introuvable", uid));
    }

    // ── Helpers d'extraction ────────────────────────────────────────────────

    /** Extrait le UID depuis plusieurs emplacements possibles selon la version de Koalendar. */
    private String extractUid(Map<String, Object> p) {
        // Essayer event.uid, puis uid, puis booking_uid, puis id
        Object event = p.get("event");
        if (event instanceof Map<?,?> e) {
            String uid = str((Map<?,?>) e, "uid");
            if (uid != null) return uid;
        }
        for (String key : new String[]{"uid", "booking_uid", "id", "booking_id"}) {
            String v = str(p, key);
            if (v != null) return v;
        }
        return null;
    }

    /** Nom de l'invité : invitee.name ou invitee.full_name. */
    private String extractInviteeName(Map<String, Object> p) {
        Object invitee = p.get("invitee");
        if (invitee instanceof Map<?,?> i) {
            String n = str((Map<?,?>) i, "name");
            if (n != null) return n;
            return str((Map<?,?>) i, "full_name");
        }
        return str(p, "name");
    }

    /** Email de l'invité : invitee.email. */
    private String extractInviteeEmail(Map<String, Object> p) {
        Object invitee = p.get("invitee");
        if (invitee instanceof Map<?,?> i) return str((Map<?,?>) i, "email");
        return str(p, "email");
    }

    /**
     * Parse start_at / end_at qui peuvent être dans payload directement
     * ou dans payload.event.{field}.
     * Format attendu : ISO 8601 avec timezone, ex: "2024-01-15T10:00:00+01:00".
     * Converti en heure locale Europe/Zurich.
     */
    private LocalDateTime extractDateTime(Map<String, Object> p, String field) {
        // Chercher au niveau racine, puis dans event{}
        String raw = str(p, field);
        if (raw == null) {
            Object event = p.get("event");
            if (event instanceof Map<?,?> e) raw = str((Map<?,?>) e, field);
        }
        if (raw == null) return null;
        try {
            OffsetDateTime odt = OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return odt.atZoneSameInstant(CH_ZONE).toLocalDateTime();
        } catch (Exception e) {
            try {
                // Fallback: sans timezone (déjà en heure locale)
                return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (Exception ex) {
                log.warn("Koalendar: impossible de parser '{}' = '{}'", field, raw);
                return null;
            }
        }
    }

    private String str(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return (v instanceof String s && !s.isBlank()) ? s : null;
    }
}
