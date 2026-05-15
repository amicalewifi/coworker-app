package ch.amicalewifi.service;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

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
    private final PrinterJobRepository             printerJobRepo;
    private final PresenceRepository               presenceRepo;

    public record PrintSummary(Member member, long jobs, int bwPages, int colorPages, BigDecimal cost) {
        public int totalPages() { return bwPages + colorPages; }
    }

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
            BigDecimal monthlyTotal,
            List<PrintSummary> todayPrintSummaries,
            Map<LocalDate, Long> last30DaysPresence,
            long maxPresence30Days
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

        int occupied      = (int) bookings.stream()
                .map(b -> b.getRoom().getId())
                .distinct()
                .count();
        int packs         = (int) allActive.stream()
                .filter(m -> m.getMembership().name().startsWith("PACK_"))
                .filter(m -> !"empty".equals(m.getPackAlert()))
                .count();

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

        List<PrinterJob> todayJobs = printerJobRepo.findByCreatedAtBetweenOrderByMemberLastNameAscCreatedAtDesc(
                today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        Map<Member, List<PrinterJob>> jobsByMember = todayJobs.stream()
                .filter(j -> j.getStatus() == PrintJobStatus.COMPLETED)
                .collect(Collectors.groupingBy(PrinterJob::getMember, LinkedHashMap::new, Collectors.toList()));
        List<PrintSummary> todayPrintSummaries = jobsByMember.entrySet().stream()
                .map(e -> {
                    List<PrinterJob> mJobs = e.getValue();
                    int bw    = mJobs.stream().mapToInt(j -> j.isColor() ? 0 : j.getTotalPages()).sum();
                    int color = mJobs.stream().mapToInt(j -> j.isColor() ? j.getTotalPages() : 0).sum();
                    BigDecimal cost = mJobs.stream().map(PrinterJob::getTotalCost).reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new PrintSummary(e.getKey(), mJobs.size(), bw, color, cost);
                })
                .collect(Collectors.toList());

        LocalDate thirtyDaysAgo = today.minusDays(29);
        Map<LocalDate, Long> last30DaysPresence = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) last30DaysPresence.put(thirtyDaysAgo.plusDays(i), 0L);
        for (Object[] row : presenceRepo.countDistinctMembersByDateRange(thirtyDaysAgo, today))
            last30DaysPresence.put((LocalDate) row[0], (Long) row[1]);
        long maxPresence30Days = last30DaysPresence.values().stream().mapToLong(Long::longValue).max().orElse(1L);

        return new Stats(
                memberService.countToday(),
                (int) presences.stream().filter(p -> p.getPresenceType() == PresenceType.FULL_DAY).count(),
                (int) presences.stream().filter(p -> p.getPresenceType() == PresenceType.HALF_AM
                                                  || p.getPresenceType() == PresenceType.HALF_PM).count(),
                occupied, rooms.size(), packs, permanents.size(), alerts, events,
                monthlyIncome, monthTxs, monthPrintTxs, monthlyTotal, todayPrintSummaries,
                last30DaysPresence, maxPresence30Days
        );
    }
}
