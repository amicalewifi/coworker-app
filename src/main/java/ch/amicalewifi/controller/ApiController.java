package ch.amicalewifi.controller;

import ch.amicalewifi.model.AccessEventType;
import ch.amicalewifi.repository.AccessEventRepository;
import ch.amicalewifi.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ApiController {

    private final MemberService         memberService;
    private final RoomService           roomService;
    private final DashboardService      dashboardService;
    private final AccessEventRepository eventRepo;

    @GetMapping("/members")
    public List<?> members() {
        return memberService.getAll().stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id",                m.getId());
            map.put("displayName",       m.getDisplayName());
            map.put("membership",        m.getMembership());
            map.put("packUnitsRemaining",m.getPackUnitsRemaining());
            map.put("halfDaysRemaining", m.getHalfDaysRemaining());
            map.put("packAlert",         m.getPackAlert());
            return map;
        }).toList();
    }

    @GetMapping("/presences/today")
    public List<?> today() {
        return memberService.getToday();
    }

    @GetMapping("/rooms")
    public List<?> rooms() {
        return roomService.getAll();
    }

    @GetMapping("/dashboard/stats")
    public DashboardService.Stats stats() {
        return dashboardService.getStats();
    }

    @GetMapping("/admin/entries-today")
    public List<Map<String, Object>> entriesToday() {
        LocalDate today = LocalDate.now();
        return eventRepo.findByTypeAndDate(
                AccessEventType.ENTRY_GRANTED,
                today.atStartOfDay(),
                today.plusDays(1).atStartOfDay()
        ).stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time",   e.getOccurredAt().format(DateTimeFormatter.ofPattern("HH:mm")));
            m.put("name",   e.getMember() != null ? e.getMember().getDisplayName() : "Badge " + e.getBadgeUid());
            m.put("badge",  e.getBadgeUid());
            return m;
        }).toList();
    }
}
