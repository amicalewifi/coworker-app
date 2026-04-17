package ch.amicalewifi.service;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DashboardService {

    private final MemberService                    memberService;
    private final RoomService                      roomService;
    private final MemberRepository                 memberRepo;
    private final AccessEventRepository            eventRepo;
    private final PackTransactionRepository        packTxRepo;
    private final PrintCreditTransactionRepository printCreditTxRepo;

    public record Stats(
            long presentToday,
            int fullDayCount,
            int halfDayCount,
            int roomsOccupied,
            int roomsTotal,
            int activePacks,
            int permanentMembers,
            List<Member> packsExpiringSoon,
            List<AccessEvent> todayEvents,
            Map<Integer, BigDecimal> monthlyIncome,
            List<PackTransaction> monthlyTransactions,
            List<PrintCreditTransaction> monthlyPrintTransactions,
            BigDecimal monthlyTotal
    ) {}

    public Stats getStats() {
        List<Presence>    presences  = memberService.getToday();
        List<RoomBooking> bookings   = roomService.getToday();
        List<Room>        rooms      = roomService.getAll();
        LocalDate today = LocalDate.now();
        List<AccessEvent> events = eventRepo.findByDate(today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        List<Member>      permanents = memberRepo.findByMembershipAndActiveTrue(MembershipType.PERMANENT);
        List<Member>      alerts     = memberService.getPackAlerts();
        List<Member>      allActive  = memberRepo.findByActiveTrueOrderByLastNameAsc();

        LocalTime now     = LocalTime.now();
        int occupied      = (int) bookings.stream()
                .filter(b -> !now.isBefore(b.getStartTime()) && !now.isAfter(b.getEndTime())).count();
        int packs         = (int) allActive.stream()
                .filter(m -> m.getMembership().name().startsWith("PACK_")).count();

        // Daily income for current month
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        List<PackTransaction> monthTxs = packTxRepo
                .findByCreatedAtBetweenOrderByMemberLastNameAscCreatedAtDesc(
                        firstOfMonth.atStartOfDay(), today.plusDays(1).atStartOfDay());
        List<PrintCreditTransaction> monthPrintTxs = printCreditTxRepo
                .findByCreatedAtBetweenOrderByCreatedAtDesc(
                        firstOfMonth.atStartOfDay(), today.plusDays(1).atStartOfDay());
        Map<Integer, BigDecimal> monthlyIncome = new TreeMap<>();
        for (int d = 1; d <= today.getDayOfMonth(); d++) monthlyIncome.put(d, BigDecimal.ZERO);
        BigDecimal monthlyTotal = BigDecimal.ZERO;
        for (PackTransaction tx : monthTxs) {
            int day = tx.getCreatedAt().getDayOfMonth();
            monthlyIncome.merge(day, tx.getAmountChf(), BigDecimal::add);
            monthlyTotal = monthlyTotal.add(tx.getAmountChf());
        }
        for (PrintCreditTransaction tx : monthPrintTxs) {
            int day = tx.getCreatedAt().getDayOfMonth();
            monthlyIncome.merge(day, tx.getAmountChf(), BigDecimal::add);
            monthlyTotal = monthlyTotal.add(tx.getAmountChf());
        }

        return new Stats(
                memberService.countToday(),
                (int) presences.stream().filter(p -> p.getPresenceType() == PresenceType.FULL_DAY).count(),
                (int) presences.stream().filter(p -> p.getPresenceType() == PresenceType.HALF_AM
                                                  || p.getPresenceType() == PresenceType.HALF_PM).count(),
                occupied, rooms.size(), packs, permanents.size(), alerts, events,
                monthlyIncome, monthTxs, monthPrintTxs, monthlyTotal
        );
    }
}
