package ch.amicalewifi.controller;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import ch.amicalewifi.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final DashboardService     dashboardService;
    private final MemberService        memberService;
    private final RoomService          roomService;
    private final RoomRepository       roomRepo;
    private final MemberRepository     memberRepo;
    private final PrinterJobRepository printerRepo;
    private final PrinterService       printerService;

    @GetMapping({"", "/"})
    public String dashboard(Model model) {
        List<RoomBooking> todayBookings = roomService.getToday();
        LocalTime now = LocalTime.now();
        Set<UUID> bookedRoomIds = todayBookings.stream()
                .map(b -> b.getRoom().getId())
                .collect(Collectors.toSet());
        Set<UUID> currentlyBookedRoomIds = todayBookings.stream()
                .filter(b -> !now.isBefore(b.getStartTime()) && !now.isAfter(b.getEndTime()))
                .map(b -> b.getRoom().getId())
                .collect(Collectors.toSet());
        // bookedHoursByRoom: roomId -> set of booked hours (8..20)
        Map<UUID, Set<Integer>> bookedHoursByRoom = todayBookings.stream()
                .collect(Collectors.groupingBy(
                        b -> b.getRoom().getId(),
                        Collectors.collectingAndThen(Collectors.toList(), bookings -> {
                            Set<Integer> hours = new HashSet<>();
                            for (RoomBooking b : bookings)
                                for (int h = b.getStartTime().getHour(); h < b.getEndTime().getHour(); h++)
                                    hours.add(h);
                            return hours;
                        })
                ));
        model.addAttribute("stats",                  dashboardService.getStats());
        model.addAttribute("todayDate",              LocalDate.now());
        model.addAttribute("presences",              memberService.getToday());
        model.addAttribute("rooms",                  roomService.getAll());
        model.addAttribute("todayBookings",          todayBookings);
        model.addAttribute("bookedRoomIds",          bookedRoomIds);
        model.addAttribute("currentlyBookedRoomIds", currentlyBookedRoomIds);
        model.addAttribute("bookedHoursByRoom",      bookedHoursByRoom);
        return "admin/dashboard";
    }

    @GetMapping("/members")
    public String members(Model model) {
        model.addAttribute("members",       memberService.getAllIncludingInactive());
        model.addAttribute("alerts",        memberService.getPackAlerts());
        model.addAttribute("memberships",   MembershipType.values());
        model.addAttribute("presenceTypes", PresenceType.values());
        return "admin/members";
    }

    @GetMapping("/members/{id}")
    public String memberDetail(@PathVariable UUID id, Model model) {
        model.addAttribute("member",      memberService.getById(id));
        model.addAttribute("presences",   memberService.getForMember(id));
        model.addAttribute("memberships", MembershipType.values());
        return "admin/member-detail";
    }

    @PostMapping("/members/create")
    public String createMember(@RequestParam String firstName,
                               @RequestParam String lastName,
                               @RequestParam String email,
                               @RequestParam(required = false) String phone,
                               @RequestParam(required = false) String company,
                               @RequestParam(required = false) String badgeUid,
                               @RequestParam MembershipType membership,
                               RedirectAttributes ra) {
        memberService.create(Member.builder()
                .firstName(firstName).lastName(lastName).email(email)
                .phone(phone).company(company).badgeUid(badgeUid)
                .membership(membership).build());
        ra.addFlashAttribute("success", "Membre " + firstName + " " + lastName + " créé avec succès");
        return "redirect:/admin/members";
    }

    @PostMapping("/members/{id}/renew")
    public String renewPack(@PathVariable UUID id,
                            @RequestParam MembershipType membership,
                            RedirectAttributes ra) {
        Member m = memberService.renewPack(id, membership);
        ra.addFlashAttribute("success", "Pack renouvelé: " + membership.getLabel() + " pour " + m.getDisplayName());
        return "redirect:/admin/members/" + id;
    }

    @PostMapping("/members/{id}/deactivate")
    public String deactivate(@PathVariable UUID id, RedirectAttributes ra) {
        Member m = memberRepo.findById(id).orElseThrow();
        m.setActive(false);
        memberRepo.save(m);
        ra.addFlashAttribute("success", "Membre désactivé");
        return "redirect:/admin/members";
    }

    @PostMapping("/members/{id}/activate")
    public String activate(@PathVariable UUID id, RedirectAttributes ra) {
        Member m = memberRepo.findById(id).orElseThrow();
        m.setActive(true);
        memberRepo.save(m);
        ra.addFlashAttribute("success", "Membre réactivé");
        return "redirect:/admin/members";
    }

    @PostMapping("/members/{id}/reset-print")
    public String resetPrint(@PathVariable UUID id, RedirectAttributes ra) {
        Member m = memberRepo.findById(id).orElseThrow();
        m.setPrintUsed(0);
        memberRepo.save(m);
        ra.addFlashAttribute("success", "Quota d'impression réinitialisé");
        return "redirect:/admin/members/" + id;
    }

    @PostMapping("/presences/manual")
    public String manualEntry(@RequestParam UUID memberId,
                              @RequestParam PresenceType presenceType,
                              @RequestParam(defaultValue = "false") boolean unitaire,
                              RedirectAttributes ra) {
        memberService.manualEntry(memberId, presenceType, unitaire);
        ra.addFlashAttribute("success", "Entrée enregistrée: " + presenceType.getLabel());
        return "redirect:/admin/";
    }

    @PostMapping("/presences/{id}/checkout")
    public String checkout(@PathVariable UUID id, RedirectAttributes ra) {
        memberService.checkout(id);
        ra.addFlashAttribute("success", "Départ enregistré");
        return "redirect:/admin/";
    }

    @GetMapping("/rooms")
    public String rooms(@RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                        Model model) {
        LocalDate d = date != null ? date : LocalDate.now();
        List<RoomBooking> bookings = roomService.getForDate(d);
        Set<UUID> bookedRoomIds = bookings.stream()
                .map(b -> b.getRoom().getId())
                .collect(Collectors.toSet());
        Map<UUID, Set<Integer>> bookedHoursByRoom = bookings.stream()
                .collect(Collectors.groupingBy(
                        b -> b.getRoom().getId(),
                        Collectors.collectingAndThen(Collectors.toList(), bs -> {
                            Set<Integer> hours = new HashSet<>();
                            for (RoomBooking b : bs)
                                for (int h = b.getStartTime().getHour(); h < b.getEndTime().getHour(); h++)
                                    hours.add(h);
                            return hours;
                        })
                ));
        model.addAttribute("rooms",             roomService.getAll());
        model.addAttribute("bookings",          bookings);
        model.addAttribute("bookedRoomIds",     bookedRoomIds);
        model.addAttribute("bookedHoursByRoom", bookedHoursByRoom);
        model.addAttribute("date",              d);
        model.addAttribute("members",           memberService.getAll());
        return "admin/rooms";
    }

    @PostMapping("/rooms/book")
    public String bookRoom(@RequestParam UUID roomId,
                           @RequestParam(required = false) UUID memberId,
                           @RequestParam(required = false) String organizerName,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                           @RequestParam String startTime,
                           @RequestParam String endTime,
                           @RequestParam(defaultValue = "1") int participants,
                           @RequestParam(required = false) String title,
                           @RequestParam(defaultValue = "true") boolean billedFromCredits,
                           RedirectAttributes ra) {
        Room   room   = roomRepo.findById(roomId).orElseThrow();
        Member member = (memberId != null) ? memberService.getById(memberId) : null;
        try {
            roomService.book(RoomBooking.builder()
                    .room(room).member(member).organizerName(organizerName)
                    .date(date)
                    .startTime(LocalTime.parse(startTime))
                    .endTime(LocalTime.parse(endTime))
                    .participants(participants).title(title)
                    .billedFromCredits(billedFromCredits).build());
            ra.addFlashAttribute("success", "Salle réservée: " + room.getName());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/rooms";
    }

    @PostMapping("/rooms/bookings/{id}/cancel")
    public String cancelBooking(@PathVariable UUID id, RedirectAttributes ra) {
        roomService.cancel(id);
        ra.addFlashAttribute("success", "Réservation annulée");
        return "redirect:/admin/rooms";
    }

    @GetMapping("/printer")
    public String printer(Model model) {
        model.addAttribute("printing",       printerRepo.findByStatusOrderByCreatedAtAsc(PrintJobStatus.PRINTING));
        model.addAttribute("queued",         printerRepo.findByStatusOrderByCreatedAtAsc(PrintJobStatus.QUEUED));
        model.addAttribute("members",        memberService.getAll());
        model.addAttribute("printerOnline",  printerService.isOnline());
        model.addAttribute("printerHost",    printerService.getHost());
        return "admin/printer";
    }
}
