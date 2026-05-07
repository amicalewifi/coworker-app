package ch.amicalewifi.controller;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import ch.amicalewifi.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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

    private final MemberService            memberService;
    private final RoomService              roomService;
    private final ScanService              scanService;
    private final UnifiService             unifiService;
    private final ZahlsService             zahlsService;
    private final PrinterService           printerService;
    private final PrinterJobRepository     printerJobRepo;
    private final MemberRepository         memberRepo;
    private final UserRepository           userRepo;
    private final RoomRepository           roomRepo;
    private final RoomBookingRepository    bookingRepo;
    private final PasswordEncoder          passwordEncoder;
    private final ch.amicalewifi.repository.PresenceRepository presenceRepo;

    @Value("${amicale.venue.qr-token}") private String venueQrToken;
    @Value("${amicale.print.public-host}") private String printPublicHost;
    @Value("${amicale.print.queue-name}")  private String printQueueName;

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
        List<ch.amicalewifi.model.Presence> todayPresences =
                presenceRepo.findActiveByMemberAndDate(member.getId(), LocalDate.now());
        model.addAttribute("member",         member);
        model.addAttribute("todayPresences", todayPresences);
        model.addAttribute("presences",      memberService.getForMember(member.getId()).stream().limit(5).toList());
        model.addAttribute("rooms",          roomService.getAll());
        model.addAttribute("bookings",       todayBookings);
        model.addAttribute("bookedRoomIds",  bookedRoomIds);
        model.addAttribute("presenceTypes",  List.of(PresenceType.HALF_AM, PresenceType.FULL_DAY, PresenceType.HALF_PM));
        return "mobile/dashboard";
    }

    @GetMapping("/register-device")
    public String registerDevicePage(Authentication auth, Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        if (member.getWifiMac() != null) return "redirect:/mobile/";

        Set<String> knownMacs = memberRepo.findAll().stream()
                .map(Member::getWifiMac)
                .filter(m -> m != null && !m.isBlank())
                .collect(Collectors.toSet());
        model.addAttribute("member",         member);
        model.addAttribute("unknownClients", unifiService.getUnknownClients(knownMacs));
        return "mobile/register-device";
    }

    @PostMapping("/register-device/select")
    public String selectMac(@RequestParam String mac, Authentication auth,
                            HttpServletRequest request, RedirectAttributes ra) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        if (member.getWifiMac() == null) {
            member.setWifiMac(mac.toLowerCase().trim());
            member.setUpdatedAt(LocalDateTime.now());
            memberRepo.save(member);
            request.getSession().removeAttribute("skipWifiMac");
            ra.addFlashAttribute("success", "Appareil enregistré — la présence sera détectée automatiquement !");
        }
        return "redirect:/mobile/";
    }

    @PostMapping("/register-device/skip")
    public String skipMacRegistration(HttpServletRequest request) {
        request.getSession().setAttribute("skipWifiMac", Boolean.TRUE);
        return "redirect:/mobile/";
    }

    @PostMapping("/presence/{id}/upgrade")
    public String upgradePresence(@PathVariable UUID id, Authentication auth, RedirectAttributes ra) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        ch.amicalewifi.model.Presence p = presenceRepo.findById(id).orElseThrow();

        if (!p.getMember().getId().equals(member.getId())) {
            ra.addFlashAttribute("error", "Accès refusé");
            return "redirect:/mobile/";
        }
        if (!p.getDate().equals(LocalDate.now())) {
            ra.addFlashAttribute("error", "Modification possible uniquement le jour même");
            return "redirect:/mobile/";
        }
        PresenceType cur = p.getPresenceType();
        if (cur != PresenceType.HALF_AM && cur != PresenceType.HALF_PM) {
            ra.addFlashAttribute("error", "Présence déjà en journée complète");
            return "redirect:/mobile/";
        }
        BigDecimal extra = new BigDecimal("0.5");
        if (!member.isPermanent()) {
            BigDecimal remaining = member.getPackUnitsRemaining() != null ? member.getPackUnitsRemaining() : BigDecimal.ZERO;
            if (remaining.compareTo(extra) < 0) {
                ra.addFlashAttribute("error", "Solde insuffisant pour passer en journée complète");
                return "redirect:/mobile/";
            }
            member.setPackUnitsUsed(member.getPackUnitsUsed().add(extra));
            memberRepo.save(member);
        }
        p.setPresenceType(PresenceType.FULL_DAY);
        p.setUnitsConsumed(new BigDecimal("1.0"));
        presenceRepo.save(p);
        ra.addFlashAttribute("success", "Présence mise à jour en journée complète");
        return "redirect:/mobile/";
    }

    @PostMapping("/presence/{id}/cancel")
    public String cancelPresence(@PathVariable UUID id, Authentication auth, RedirectAttributes ra) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        ch.amicalewifi.model.Presence p = presenceRepo.findById(id).orElseThrow();

        if (!p.getMember().getId().equals(member.getId())) {
            ra.addFlashAttribute("error", "Accès refusé");
            return "redirect:/mobile/";
        }
        if (!p.getDate().equals(LocalDate.now())) {
            ra.addFlashAttribute("error", "Modification possible uniquement le jour même");
            return "redirect:/mobile/";
        }
        if (!member.isPermanent() && p.getUnitsConsumed() != null) {
            member.setPackUnitsUsed(member.getPackUnitsUsed().subtract(p.getUnitsConsumed()));
            memberRepo.save(member);
        }
        p.setStatus(ch.amicalewifi.model.PresenceStatus.CANCELLED);
        presenceRepo.save(p);
        ra.addFlashAttribute("success", "Présence annulée — crédits remboursés");
        return "redirect:/mobile/";
    }


    @PostMapping("/scan")
    public String scan(Authentication auth,
                       @RequestParam(defaultValue = "FULL_DAY") PresenceType presenceType,
                       @RequestParam(defaultValue = "false") boolean unitaire,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElse(null);
        if (member == null) return "redirect:/mobile/";
        if (unitaire) presenceType = presenceType.toUnitaire();
        LocalDate presenceDate = (date != null) ? date : LocalDate.now();
        ScanResult result = scanService.processScanByToken(member.getQrToken(), presenceType, presenceDate);
        model.addAttribute("result", result);
        model.addAttribute("member", member);
        return "mobile/scan-result";
    }

    @GetMapping("/pack")
    public String pack(Authentication auth, Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        List<Presence> presences = memberService.getForMember(member.getId());

        long fullDays = presences.stream().filter(p -> p.getPresenceType() == PresenceType.FULL_DAY).count();
        long halfDays = presences.stream().filter(p -> p.getPresenceType() == PresenceType.HALF_AM
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
        return "mobile/pack";
    }

    @GetMapping("/history")
    public String history() {
        return "redirect:/mobile/pack";
    }

    @GetMapping("/security")
    public String security(Authentication auth, Model model) {
        model.addAttribute("member", memberRepo.findByEmail(auth.getName()).orElseThrow());
        return "mobile/security";
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
                                @RequestParam(required = false) String website,
                                @RequestParam(required = false) String linkedinUrl,
                                RedirectAttributes ra) {
        Member m = memberRepo.findByEmail(auth.getName()).orElseThrow();
        m.setPhone(phone);
        m.setCompany(company);
        m.setTvaNumber(tvaNumber);
        m.setAddress(address);
        m.setCity(city);
        m.setPostalCode(postalCode);
        m.setCountry(country != null && !country.isBlank() ? country : "Suisse");
        m.setWebsite(website != null && !website.isBlank() ? website : null);
        m.setLinkedinUrl(linkedinUrl != null && !linkedinUrl.isBlank() ? linkedinUrl : null);
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
        ra.addFlashAttribute("success", "Profil mis à jour.");
        return "redirect:/mobile/profile";
    }

    @GetMapping(value = "/profile/photo", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> getPhoto(Authentication auth) {
        return memberRepo.findByEmail(auth.getName())
                .filter(m -> m.getPhoto() != null)
                .map(m -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(
                                m.getPhotoType() != null ? m.getPhotoType() : "image/jpeg"))
                        .body(m.getPhoto()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/profile/photo")
    public String uploadPhoto(Authentication auth,
                              @RequestParam MultipartFile file,
                              RedirectAttributes ra) {
        if (file.isEmpty()) {
            ra.addFlashAttribute("error", "Aucun fichier sélectionné.");
            return "redirect:/mobile/profile";
        }
        if (!file.getContentType().startsWith("image/")) {
            ra.addFlashAttribute("error", "Seules les images sont acceptées.");
            return "redirect:/mobile/profile";
        }
        try {
            Member m = memberRepo.findByEmail(auth.getName()).orElseThrow();
            m.setPhoto(file.getBytes());
            m.setPhotoType(file.getContentType());
            memberRepo.save(m);
            ra.addFlashAttribute("success", "Photo mise à jour.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Erreur lors de l'upload.");
        }
        return "redirect:/mobile/profile";
    }

    @PostMapping("/profile/password")
    public String changePassword(Authentication auth,
                                 @RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 RedirectAttributes ra) {
        Member m = memberRepo.findByEmail(auth.getName()).orElseThrow();
        User user = m.getUser();
        if (user == null) {
            ra.addFlashAttribute("error", "Aucun compte associé à ce profil.");
            return "redirect:/mobile/profile";
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            ra.addFlashAttribute("pwError", "Mot de passe actuel incorrect.");
            return "redirect:/mobile/profile";
        }
        if (!newPassword.equals(confirmPassword)) {
            ra.addFlashAttribute("pwError", "Les nouveaux mots de passe ne correspondent pas.");
            return "redirect:/mobile/profile";
        }
        if (newPassword.length() < 8) {
            ra.addFlashAttribute("pwError", "Le mot de passe doit contenir au moins 8 caractères.");
            return "redirect:/mobile/profile";
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepo.save(user);
        ra.addFlashAttribute("success", "Mot de passe modifié avec succès.");
        return "redirect:/mobile/security";
    }

    @PostMapping("/profile/reset-mac")
    public String resetMac(Authentication auth, RedirectAttributes ra) {
        Member m = memberRepo.findByEmail(auth.getName()).orElseThrow();
        m.setWifiMac(null);
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
        ra.addFlashAttribute("success", "Adresse MAC supprimée — la détection automatique est désactivée.");
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
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                  Authentication auth, Model model) {
        if (venue == null || !venue.equals(venueQrToken)) {
            return "redirect:/mobile/?error=qr_invalide";
        }
        Member member = memberRepo.findByEmail(auth.getName()).orElse(null);
        if (member == null) return "redirect:/mobile/";
        if (unitaire) presenceType = presenceType.toUnitaire();
        LocalDate presenceDate = (date != null) ? date : LocalDate.now();
        ScanResult result = scanService.processScanByToken(member.getQrToken(), presenceType, presenceDate);
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
        return zahlsService.createPaymentLink(member, membership)
                .map(url -> "redirect:" + url)
                .orElseGet(() -> {
                    ra.addFlashAttribute("error",
                            "Impossible de créer le lien de paiement zahls.ch. Contactez l'admin.");
                    return "redirect:/mobile/";
                });
    }

    @GetMapping("/print")
    public String printPage(Authentication auth,
                            @RequestParam(required = false) String bought,
                            Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        if ("ok".equals(bought)) {
            model.addAttribute("success", "Paiement reçu — vos crédits d'impression ont été ajoutés !");
        }
        model.addAttribute("member",       member);
        model.addAttribute("declarations", memberService.getPrintDeclarations(member.getId()).stream().limit(10).toList());
        model.addAttribute("purchases",    memberService.getPrintPurchases(member.getId()).stream().limit(5).toList());
        model.addAttribute("printPacks",   List.of(PrintPackType.values()));
        return "mobile/print";
    }

    @PostMapping("/print/declare")
    public String declare(Authentication auth,
                          @RequestParam(defaultValue = "0") int pagesBw,
                          @RequestParam(defaultValue = "0") int pagesColor,
                          RedirectAttributes ra) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        try {
            memberService.declarePrint(member.getId(), pagesBw, pagesColor);
            ra.addFlashAttribute("success", "Impression déclarée — crédits déduits.");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/mobile/print";
    }

    @PostMapping(value = "/print/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String uploadPrint(Authentication auth,
                              @RequestParam MultipartFile file,
                              @RequestParam(defaultValue = "1") int pages,
                              @RequestParam(defaultValue = "1") int copies,
                              @RequestParam(defaultValue = "false") boolean color,
                              @RequestParam(defaultValue = "false") boolean duplex,
                              RedirectAttributes ra) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        int credits = pages * copies * (color ? 2 : 1);
        try {
            memberService.deductPrintCredits(member.getId(), credits);

            String filename = file.getOriginalFilename();
            String lang = (filename != null && filename.toLowerCase().endsWith(".ps")) ? "POSTSCRIPT" : "PDF";
            BigDecimal cppChf = color ? new BigDecimal("0.200") : new BigDecimal("0.100");
            boolean online = printerService.isOnline();

            PrinterJob job = printerJobRepo.save(PrinterJob.builder()
                    .member(member)
                    .filename(filename != null ? filename : "document.pdf")
                    .pages(pages).copies(copies).color(color).duplex(duplex)
                    .status(online ? PrintJobStatus.PRINTING : PrintJobStatus.QUEUED)
                    .costPerPage(cppChf)
                    .build());

            if (online) {
                try {
                    printerService.print(file.getBytes(), job.getFilename(), lang, copies, duplex);
                    job.setStatus(PrintJobStatus.COMPLETED);
                    job.setCompletedAt(LocalDateTime.now());
                    printerJobRepo.save(job);
                    ra.addFlashAttribute("success",
                            "Document envoyé à l'imprimante — " + credits + " crédit(s) déduits.");
                } catch (Exception e) {
                    job.setStatus(PrintJobStatus.ERROR);
                    job.setErrorMessage(e.getMessage());
                    printerJobRepo.save(job);
                    memberService.refundPrintCredits(member.getId(), credits);
                    ra.addFlashAttribute("error",
                            "Erreur d'impression : " + e.getMessage() + " — crédits remboursés.");
                }
            } else {
                ra.addFlashAttribute("success",
                        "Imprimante hors ligne — document mis en file d'attente. " + credits + " crédit(s) déduits.");
            }
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/mobile/print";
    }

    @GetMapping("/printer-setup")
    public String printerSetup(Authentication auth, Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        model.addAttribute("member",     member);
        model.addAttribute("publicHost", printPublicHost);
        model.addAttribute("queueName",  printQueueName);
        return "mobile/printer-setup";
    }

    @PostMapping("/print/rotate-token")
    public String rotatePrintToken(Authentication auth, RedirectAttributes ra) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        memberService.rotatePrintToken(member.getId());
        ra.addFlashAttribute("success",
                "Token régénéré. Réinstalle Claudine sur tes machines avec ce nouveau token.");
        return "redirect:/mobile/printer-setup";
    }

    @PostMapping("/print/buy")
    public String buyPrintPack(Authentication auth,
                               @RequestParam PrintPackType pack,
                               RedirectAttributes ra) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        return zahlsService.createPrintPackPaymentLink(member, pack)
                .map(url -> "redirect:" + url)
                .orElseGet(() -> {
                    ra.addFlashAttribute("error",
                            "Impossible de créer le lien de paiement. Contactez l'admin.");
                    return "redirect:/mobile/print";
                });
    }

    @GetMapping("/rooms")
    public String roomsPage(Authentication auth,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                            @RequestParam(required = false) String bought,
                            Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        LocalDate selectedDate = date != null ? date : LocalDate.now();
        if ("ok".equals(bought)) {
            model.addAttribute("success", "Paiement reçu — vos crédits de salle ont été ajoutés !");
        }
        List<Room> rooms = roomService.getAll();
        java.util.Map<UUID, List<RoomBooking>> bookingsByRoom = new java.util.HashMap<>();
        rooms.forEach(r -> bookingsByRoom.put(r.getId(),
                bookingRepo.findByRoomIdAndDateAndStatusOrderByStartTime(r.getId(), selectedDate, BookingStatus.CONFIRMED)));
        // precompute busy hours (7-20) per room for the timeline display
        java.util.Map<UUID, Set<Integer>> busyHoursByRoom = new java.util.HashMap<>();
        bookingsByRoom.forEach((rid, bookings) -> {
            Set<Integer> hours = new java.util.HashSet<>();
            for (RoomBooking b : bookings) {
                int startH = b.getStartTime().getHour();
                int endH = b.getEndTime().getHour() + (b.getEndTime().getMinute() > 0 ? 1 : 0);
                for (int h = startH; h < endH; h++) hours.add(h);
            }
            busyHoursByRoom.put(rid, hours);
        });
        List<RoomBooking> myBookings = roomService.getForMember(member.getId()).stream()
                .filter(b -> !b.getDate().isBefore(LocalDate.now()) && b.getStatus() == BookingStatus.CONFIRMED)
                .limit(5).toList();
        model.addAttribute("member",          member);
        model.addAttribute("rooms",           rooms);
        model.addAttribute("selectedDate",    selectedDate);
        model.addAttribute("bookingsByRoom",  bookingsByRoom);
        model.addAttribute("busyHoursByRoom", busyHoursByRoom);
        model.addAttribute("myBookings",      myBookings);
        model.addAttribute("confPacks",       List.of(ConfHourPackType.values()));
        return "mobile/rooms";
    }

    @PostMapping("/rooms/book")
    public String bookRoomWithCredits(Authentication auth,
                                      @RequestParam UUID roomId,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                      @RequestParam String startTime,
                                      @RequestParam String endTime,
                                      @RequestParam(defaultValue = "1") int participants,
                                      @RequestParam(required = false) String title,
                                      RedirectAttributes ra) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        Room   room   = roomRepo.findById(roomId).orElseThrow();
        LocalTime start = LocalTime.parse(startTime);
        LocalTime end   = LocalTime.parse(endTime);
        BigDecimal hours = BigDecimal.valueOf(Duration.between(start, end).toMinutes())
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

        if (!member.isPermanent()) {
            BigDecimal remaining = member.getConfCreditsRemaining();
            if (remaining.compareTo(hours) < 0) {
                ra.addFlashAttribute("error",
                    remaining.compareTo(BigDecimal.ZERO) > 0
                        ? "Crédits insuffisants — " + remaining + "h disponibles, " + hours + "h requises. Achetez des crédits ci-dessous."
                        : "Vous n'avez pas de crédits de salle. Achetez un pack ci-dessous ou directement à l'Amicale (19 CHF/h).");
                ra.addFlashAttribute("neededHours", hours.subtract(remaining).max(BigDecimal.ZERO));
                return "redirect:/mobile/rooms?date=" + date;
            }
        }
        try {
            roomService.book(RoomBooking.builder()
                    .room(room).member(member).date(date)
                    .startTime(start).endTime(end)
                    .participants(participants).title(title)
                    .billedFromCredits(true).build());
            if (!member.isPermanent()) {
                member.setConfCreditsUsedH(member.getConfCreditsUsedH().add(hours));
                member.setUpdatedAt(LocalDateTime.now());
                memberRepo.save(member);
            }
            ra.addFlashAttribute("success", "Salle réservée : " + room.getName() + " — " + hours + "h déduit(es).");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/mobile/rooms?date=" + date;
    }

    @PostMapping("/rooms/cancel/{id}")
    public String cancelBooking(@PathVariable UUID id, RedirectAttributes ra) {
        try {
            roomService.cancel(id);
            ra.addFlashAttribute("success", "Réservation annulée.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/mobile/rooms";
    }

    @PostMapping("/rooms/buy-credits")
    public String buyConfCredits(Authentication auth,
                                 @RequestParam ConfHourPackType pack,
                                 RedirectAttributes ra) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        return zahlsService.createConfCreditPaymentLink(member, pack)
                .map(url -> "redirect:" + url)
                .orElseGet(() -> {
                    ra.addFlashAttribute("error", "Impossible de créer le lien de paiement. Contactez l'admin.");
                    return "redirect:/mobile/rooms";
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
