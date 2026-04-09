package ch.amicalewifi.service;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DashboardService {

    private final MemberService         memberService;
    private final RoomService           roomService;
    private final MemberRepository      memberRepo;
    private final AccessEventRepository eventRepo;

    public record Stats(
            long presentToday,
            int fullDayCount,
            int halfDayCount,
            int roomsOccupied,
            int roomsTotal,
            int activePacks,
            int permanentMembers,
            List<Member> packsExpiringSoon,
            List<AccessEvent> todayEvents
    ) {}

    public Stats getStats() {
        List<Presence>    presences  = memberService.getToday();
        List<RoomBooking> bookings   = roomService.getToday();
        List<Room>        rooms      = roomService.getAll();
        List<AccessEvent> events     = eventRepo.findByDate(LocalDate.now());
        List<Member>      permanents = memberRepo.findByMembershipAndActiveTrue(MembershipType.PERMANENT);
        List<Member>      alerts     = memberService.getPackAlerts();
        List<Member>      allActive  = memberRepo.findByActiveTrueOrderByLastNameAsc();

        LocalTime now     = LocalTime.now();
        int occupied      = (int) bookings.stream()
                .filter(b -> !now.isBefore(b.getStartTime()) && !now.isAfter(b.getEndTime())).count();
        int packs         = (int) allActive.stream()
                .filter(m -> m.getMembership().name().startsWith("PACK_")).count();

        return new Stats(
                memberService.countToday(),
                (int) presences.stream().filter(p -> p.getPresenceType() == PresenceType.FULL_DAY).count(),
                (int) presences.stream().filter(p -> p.getPresenceType() == PresenceType.HALF_AM
                                                  || p.getPresenceType() == PresenceType.HALF_PM).count(),
                occupied, rooms.size(), packs, permanents.size(), alerts, events
        );
    }
}
