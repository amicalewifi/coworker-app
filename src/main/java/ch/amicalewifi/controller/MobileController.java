package ch.amicalewifi.controller;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import ch.amicalewifi.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final UnifiService     unifiService;
    private final ZahlsService     zahlsService;
    private final MemberRepository memberRepo;
    private final RoomRepository   roomRepo;

    @Value("${amicale.venue.qr-token}") private String venueQrToken;

    @GetMapping({"", "/"})
    public String home(Authentication auth,
                       @RequestParam(required = false) String renewed,
                       Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElse(null);
        if ("ok".equals(renewed)) {
            model.addAttribute("success", "Paiement reçu — votre pack a été renouvelé !");
        }
        if (member == null) {
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
        if (unitaire) presenceType = presenceType.toUnitaire();
        ScanResult result = scanService.processScanByToken(member.getQrToken(), presenceType);
        model.addAttribute("result", result);
        model.addAttribute("member", member);
        return "mobile/scan-result";
    }

    @GetMapping("/history")
    public String history(Authentication auth, Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        List<Presence> presences = memberService.getForMember(member.getId());

        long fullDays  = presences.stream().filter(p -> p.getPresenceType() == PresenceType.FULL_DAY).count();
        long halfDays  = presences.stream().filter(p -> p.getPresenceType() == PresenceType.HALF_AM
                                                     || p.getPresenceType() == PresenceType.HALF_PM).count();
        BigDecimal totalChf = presences.stream()
                .filter(p -> p.getUnitPriceChf() != null && p.getUnitsConsumed() != null)
                .map(p -> p.getUnitPriceChf().multiply(p.getUnitsConsumed()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        model.addAttribute("member",    member);
        model.addAttribute("presences", presences);
        model.addAttribute("fullDays",  fullDays);
        model.addAttribute("halfDays",  halfDays);
        model.addAttribute("totalChf",  totalChf);
        return "mobile/history";
    }

    @GetMapping("/profile")
    public String profile(Authentication auth, Model model) {
        model.addAttribute("member", memberRepo.findByEmail(auth.getName()).orElseThrow());
        return "mobile/profile";
    }

    @PostMapping("/profile")
    public String updateProfile(Authentication auth,
                                @RequestParam(required = false) String phone,
                                @RequestParam(required = false) String company,
                                @RequestParam(required = false) String tvaNumber,
                                @RequestParam(required = false) String address,
                                @RequestParam(required = false) String city,
                                @RequestParam(required = false) String postalCode,
                                @RequestParam(required = false) String country,
                                RedirectAttributes ra) {
        Member m = memberRepo.findByEmail(auth.getName()).orElseThrow();
        m.setPhone(phone);
        m.setCompany(company);
        m.setTvaNumber(tvaNumber);
        m.setAddress(address);
        m.setCity(city);
        m.setPostalCode(postalCode);
        m.setCountry(country != null && !country.isBlank() ? country : "Suisse");
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
        ra.addFlashAttribute("success", "Profil mis à jour.");
        return "redirect:/mobile/profile";
    }

    /** Page ouverte après scan du QR code du coworking. */
    @GetMapping("/presence")
    public String presenceScan(@RequestParam(required = false) String venue,
                               Authentication auth, Model model) {
        if (venue == null || !venue.equals(venueQrToken)) {
            return "redirect:/mobile/?error=qr_invalide";
        }
        Member member = memberRepo.findByEmail(auth.getName()).orElse(null);
        if (member == null) return "redirect:/mobile/";
        model.addAttribute("member", member);
        model.addAttribute("venue", venue);
        model.addAttribute("presenceTypes", List.of(PresenceType.HALF_AM, PresenceType.FULL_DAY, PresenceType.HALF_PM));
        return "mobile/presence";
    }

    /** Enregistrement de présence via QR scan du coworking. */
    @PostMapping("/presence")
    public String presenceConfirm(@RequestParam(required = false) String venue,
                                  @RequestParam(defaultValue = "FULL_DAY") PresenceType presenceType,
                                  @RequestParam(defaultValue = "false") boolean unitaire,
                                  Authentication auth, Model model) {
        if (venue == null || !venue.equals(venueQrToken)) {
            return "redirect:/mobile/?error=qr_invalide";
        }
        Member member = memberRepo.findByEmail(auth.getName()).orElse(null);
        if (member == null) return "redirect:/mobile/";
        if (unitaire) presenceType = presenceType.toUnitaire();
        ScanResult result = scanService.processScanByToken(member.getQrToken(), presenceType);
        if (result instanceof ScanResult.Granted) {
            unifiService.createVoucher(member.getDisplayName())
                    .ifPresent(code -> model.addAttribute("wifiVoucher", code));
        }
        model.addAttribute("result", result);
        model.addAttribute("member", member);
        return "mobile/scan-result";
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

    /** Page de renouvellement de pack via zahls.ch. */
    @GetMapping("/renew")
    public String renewPage(Authentication auth, Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        model.addAttribute("member", member);
        // Proposer uniquement les packs avec un prix (pas essai, unitaire, domiciliation)
        List<MembershipType> packs = List.of(
                MembershipType.PACK_MATIN, MembershipType.PACK_APMIDI,
                MembershipType.PACK_1J, MembershipType.PACK_5J,
                MembershipType.PACK_10J, MembershipType.PACK_15J,
                MembershipType.PERMANENT);
        model.addAttribute("memberships", packs);
        return "mobile/renew";
    }

    /** Initie le paiement zahls.ch pour le renouvellement choisi. */
    @PostMapping("/renew")
    public String initiateRenewal(Authentication auth,
                                  @RequestParam MembershipType membership,
                                  RedirectAttributes ra) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        return zahlsService.createPaymentLink(member.getId(), membership)
                .map(url -> "redirect:" + url)
                .orElseGet(() -> {
                    ra.addFlashAttribute("error",
                            "Impossible de créer le lien de paiement zahls.ch. Contactez l'admin.");
                    return "redirect:/mobile/";
                });
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
