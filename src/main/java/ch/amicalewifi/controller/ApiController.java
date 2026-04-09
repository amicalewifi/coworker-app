package ch.amicalewifi.controller;

import ch.amicalewifi.model.PresenceType;
import ch.amicalewifi.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ApiController {

    private final ScanService      scanService;
    private final MemberService    memberService;
    private final RoomService      roomService;
    private final DashboardService dashboardService;

    @PostMapping("/scan")
    public ResponseEntity<?> scan(@RequestBody Map<String, String> body) {
        String uid        = body.getOrDefault("badgeUid", "").trim().toUpperCase();
        PresenceType type = PresenceType.valueOf(body.getOrDefault("presenceType", "FULL_DAY"));
        return ResponseEntity.ok(scanService.processScan(uid, type));
    }

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
}
