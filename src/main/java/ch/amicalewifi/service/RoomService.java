package ch.amicalewifi.service;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class RoomService {

    private final RoomRepository        roomRepo;
    private final RoomBookingRepository bookingRepo;
    private final MemberRepository      memberRepo;

    public List<Room>        getAll()              { return roomRepo.findByActiveTrue(); }
    public Optional<Room>    getByToken(String t)  { return roomRepo.findByQrCodeToken(t); }
    public List<RoomBooking> getToday()            { return bookingRepo.findByDateAndStatusOrderByStartTime(LocalDate.now(), BookingStatus.CONFIRMED); }
    public List<RoomBooking> getForDate(LocalDate d) { return bookingRepo.findByDateAndStatusOrderByStartTime(d, BookingStatus.CONFIRMED); }
    public List<RoomBooking> getForMember(UUID id)   { return bookingRepo.findByMemberIdOrderByDateDescStartTimeDesc(id); }
    public List<RoomBooking> getUpcomingFrom(LocalDate from) { return bookingRepo.findByDateGreaterThanEqualAndStatusOrderByDateAscStartTimeAsc(from, BookingStatus.CONFIRMED); }
    public List<RoomBooking> getForMonth(YearMonth ym)       { return bookingRepo.findByDateBetweenAndStatusOrderByDateAscStartTimeAsc(ym.atDay(1), ym.atEndOfMonth(), BookingStatus.CONFIRMED); }

    public RoomBooking book(RoomBooking b) {
        if (!bookingRepo.findConflicts(b.getRoom().getId(), b.getDate(), b.getStartTime(), b.getEndTime(), BookingStatus.CONFIRMED).isEmpty()) {
            throw new IllegalStateException("La salle est déjà réservée sur ce créneau");
        }
        RoomBooking saved = bookingRepo.save(b);
        if (saved.getMember() != null && saved.isBilledFromCredits()) {
            Member m = saved.getMember();
            BigDecimal hours = BigDecimal.valueOf(saved.getDurationHours());
            m.setConfCreditsUsedH(m.getConfCreditsUsedH().add(hours));
            memberRepo.save(m);
            log.info("Réservation: {} · {} {}–{} · −{}h crédits membre {}", b.getRoom().getName(), b.getDate(), b.getStartTime(), b.getEndTime(), hours, m.getId());
        } else {
            log.info("Réservation: {} · {} {}–{}", b.getRoom().getName(), b.getDate(), b.getStartTime(), b.getEndTime());
        }
        return saved;
    }

    public RoomBooking cancel(UUID id) {
        RoomBooking b = bookingRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Réservation introuvable"));
        b.setStatus(BookingStatus.CANCELLED);
        RoomBooking saved = bookingRepo.save(b);
        if (saved.getMember() != null && saved.isBilledFromCredits()) {
            Member m = saved.getMember();
            BigDecimal hours = BigDecimal.valueOf(saved.getDurationHours());
            m.setConfCreditsUsedH(m.getConfCreditsUsedH().subtract(hours).max(BigDecimal.ZERO));
            memberRepo.save(m);
            log.info("Annulation: {} · {} {}–{} · +{}h crédits remboursés membre {}", b.getRoom().getName(), b.getDate(), b.getStartTime(), b.getEndTime(), hours, m.getId());
        }
        return saved;
    }
}
