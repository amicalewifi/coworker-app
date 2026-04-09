package ch.amicalewifi.controller;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import ch.amicalewifi.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/mobile")
@RequiredArgsConstructor
public class MobileController {

    private final MemberService    memberService;
    private final RoomService      roomService;
    private final ScanService      scanService;
    private final MemberRepository memberRepo;
    private final RoomRepository   roomRepo;

    @GetMapping({"", "/"})
    public String home(Authentication auth, Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElse(null);
        if (member == null) {
            // Admin sans profil membre → retour au tableau de bord admin
            boolean isAdmin = auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            return isAdmin ? "redirect:/admin/" : "redirect:/login";
        }
        List<RoomBooking> todayBookings = roomService.getToday();
        Set<UUID> bookedRoomIds = todayBookings.stream()
                .map(b -> b.getRoom().getId())
                .collect(Collectors.toSet());
        model.addAttribute("member",        member);
        model.addAttribute("presences",     memberService.getForMember(member.getId()).stream().limit(5).toList());
        model.addAttribute("rooms",         roomService.getAll());
        model.addAttribute("bookings",      todayBookings);
        model.addAttribute("bookedRoomIds", bookedRoomIds);
        model.addAttribute("presenceTypes", List.of(PresenceType.HALF_AM, PresenceType.FULL_DAY, PresenceType.HALF_PM));
        return "mobile/dashboard";
    }

    @PostMapping("/scan")
    public String scan(Authentication auth,
                       @RequestParam(defaultValue = "FULL_DAY") PresenceType presenceType,
                       @RequestParam(defaultValue = "false") boolean unitaire,
                       Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElse(null);
        if (member == null) return "redirect:/mobile/";
        if (member.getBadgeUid() == null) {
            model.addAttribute("error", "Aucun badge NFC associé à votre compte. Contactez l'administrateur.");
            model.addAttribute("member", member);
            return "mobile/scan-result";
        }
        if (unitaire) presenceType = presenceType.toUnitaire();
        ScanResult result = scanService.processScan(member.getBadgeUid(), presenceType);
        model.addAttribute("result", result);
        model.addAttribute("member", member);
        return "mobile/scan-result";
    }

    @GetMapping("/history")
    public String history(Authentication auth, Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        model.addAttribute("member",    member);
        model.addAttribute("presences", memberService.getForMember(member.getId()));
        return "mobile/history";
    }

    @GetMapping("/room-scan")
    public String roomScan(@RequestParam String token, Model model) {
        Room room = roomRepo.findByQrCodeToken(token).orElse(null);
        if (room == null) return "redirect:/mobile/?error=room_not_found";
        model.addAttribute("room",     room);
        model.addAttribute("bookings", roomService.getToday().stream()
                .filter(b -> b.getRoom().getId().equals(room.getId())).toList());
        return "mobile/room-scan";
    }

    @PostMapping("/book-room")
    public String bookRoom(Authentication auth,
                           @RequestParam UUID roomId,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                           @RequestParam String startTime,
                           @RequestParam String endTime,
                           @RequestParam(defaultValue = "1") int participants,
                           @RequestParam(required = false) String title,
                           RedirectAttributes ra) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        Room   room   = roomRepo.findById(roomId).orElseThrow();
        try {
            roomService.book(RoomBooking.builder()
                    .room(room).member(member).date(date)
                    .startTime(LocalTime.parse(startTime))
                    .endTime(LocalTime.parse(endTime))
                    .participants(participants).title(title)
                    .billedFromCredits(true).build());
            ra.addFlashAttribute("success", "Salle réservée: " + room.getName());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/mobile/";
    }
}
