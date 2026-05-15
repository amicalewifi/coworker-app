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
    private final ZahlsService             zahlsService;
    private final PrinterService           printerService;
    private final PrinterJobRepository     printerJobRepo;
    private final MemberRepository         memberRepo;
    private final UserRepository           userRepo;
    private final RoomRepository           roomRepo;
    private final RoomBookingRepository    bookingRepo;
    private final MemberWifiMacRepository  wifiMacRepo;
    private final WifiDailyUsageRepository wifiDailyUsageRepo;
    private final PackTransactionRepository packTxRepo;
    private final WifiAccessService        wifiAccess;
    private final UnifiService             unifi;
    private final LanDetectionService      lanDetection;
    private final PasswordEncoder          passwordEncoder;

    public record PackGroup(PackTransaction tx, List<Presence> presences, BigDecimal unitsUsed) {}

    @Value("${amicale.print.public-host}") private String printPublicHost;
    @Value("${amicale.print.queue-name}")  private String printQueueName;
    @Value("${amicale.business.opening-hour}") private int openingHour;
    @Value("${amicale.business.closing-hour}") private int closingHour;
    @Value("${amicale.venue.address}")         private String venueAddress;
    @Value("${amicale.community.whatsapp-url}")    private String whatsappUrl;
    @Value("${amicale.community.coworkers-email}") private String coworkersEmail;
    @Value("${amicale.community.staff-email}")     private String staffEmail;
    @Value("${amicale.events.calendar-view-url}") private String calendarViewUrl;
    @Value("${amicale.events.calendar-ics-url}")  private String calendarIcsUrl;
    @Value("${amicale.wifi.trampoline-url}")      private String wifiTrampolineUrl;

    @GetMapping({"", "/"})
    public String home(Authentication auth,
                       @RequestParam(required = false) String renewed,
                       jakarta.servlet.http.HttpServletRequest request,
                       Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElse(null);
        if ("ok".equals(renewed)) {
            model.addAttribute("success", "Paiement reçu — ton pack a été renouvelé !");
        }
        if (member == null) {
            boolean isAdmin = auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            return isAdmin ? "redirect:/admin/" : "redirect:/login";
        }
        int deviceCount = wifiMacRepo.findAllByMemberIdOrderByCreatedAtAsc(member.getId()).size();
        boolean hasBadge = member.getBadgeUid() != null && !member.getBadgeUid().isBlank();

        // Pack actif : permanent non expiré OU pack avec unités > 0.
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Europe/Zurich"));
        BigDecimal unitsRemaining = member.getPackUnitsRemaining();
        boolean packNotExpired = member.getPackExpires() == null
                || !member.getPackExpires().isBefore(today);
        boolean hasActivePack = (member.isPermanent() && packNotExpired)
                || (unitsRemaining != null
                    && unitsRemaining.compareTo(BigDecimal.ZERO) > 0
                    && packNotExpired);

        // Temps de connexion WiFi du jour (pour la carte pack).
        int wifiTodaySeconds = wifiDailyUsageRepo
                .findByMemberIdAndUsageDate(member.getId(), today)
                .map(WifiDailyUsage::getSeconds)
                .orElse(0);
        BigDecimal wifiTodayCharged = wifiDailyUsageRepo
                .findByMemberIdAndUsageDate(member.getId(), today)
                .map(WifiDailyUsage::getUnitsCharged)
                .orElse(BigDecimal.ZERO);

        // Alerte "appareil non enregistré": affichée uniquement si le membre
        // est sur le LAN coworking ET qu'aucune de ses MAC enregistrées n'est
        // actuellement autorisée chez UniFi. Indication forte que la session
        // actuelle se fait depuis un appareil non ajouté à sa liste.
        boolean showRegisterDevicePrompt = false;
        if (lanDetection.isLanRequest(request)) {
            java.util.List<MemberWifiMac> myMacs =
                    wifiMacRepo.findAllByMemberIdOrderByCreatedAtAsc(member.getId());
            if (myMacs.isEmpty()) {
                showRegisterDevicePrompt = true;
            } else {
                java.util.Set<String> myMacSet = myMacs.stream()
                        .map(MemberWifiMac::getMac)
                        .collect(java.util.stream.Collectors.toSet());
                boolean anyAuthorized = unifi.getConnectedClients().stream()
                        .filter(c -> Boolean.TRUE.equals(c.get("authorized")))
                        .map(c -> (String) c.get("mac"))
                        .filter(java.util.Objects::nonNull)
                        .anyMatch(myMacSet::contains);
                showRegisterDevicePrompt = !anyAuthorized;
            }
        }

        model.addAttribute("member",                member);
        model.addAttribute("showRegisterDevicePrompt", showRegisterDevicePrompt);
        model.addAttribute("firstName",             member.getFirstName());
        model.addAttribute("openingHour",       openingHour);
        model.addAttribute("closingHour",       closingHour);
        model.addAttribute("address",           venueAddress);
        model.addAttribute("hasBadge",          hasBadge);
        model.addAttribute("deviceCount",       deviceCount);
        model.addAttribute("hasActivePack",     hasActivePack);
        model.addAttribute("unitsRemaining",    unitsRemaining);
        model.addAttribute("wifiTodaySeconds",  wifiTodaySeconds);
        model.addAttribute("wifiTodayCharged",  wifiTodayCharged);
        model.addAttribute("whatsappUrl",       whatsappUrl);
        model.addAttribute("coworkersEmail",    coworkersEmail);
        model.addAttribute("staffEmail",        staffEmail);
        model.addAttribute("calendarViewUrl",   calendarViewUrl);
        model.addAttribute("calendarIcsUrl",    calendarIcsUrl);
        return "mobile/dashboard";
    }

    @GetMapping("/devices")
    public String devicesPage(Authentication auth,
                              jakarta.servlet.http.HttpServletRequest request,
                              Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        model.addAttribute("member", member);
        model.addAttribute("devices",
                wifiMacRepo.findAllByMemberIdOrderByCreatedAtAsc(member.getId()));
        // Le bouton "Ajouter cet appareil" ne fonctionne que depuis le WiFi
        // du coworking (la trampoline pointe sur l'IP du tunnel WG, non
        // routable depuis l'extérieur). On le cache pour les requêtes distantes.
        model.addAttribute("isLanRequest", lanDetection.isLanRequest(request));
        return "mobile/devices";
    }

    @PostMapping("/devices/{id}/label")
    public Object renameDevice(@PathVariable UUID id,
                               @RequestParam(required = false) String label,
                               @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
                               Authentication auth, RedirectAttributes ra) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        wifiMacRepo.findById(id).ifPresent(mac -> {
            if (!mac.getMember().getId().equals(member.getId())) return;
            mac.setLabel(label != null && !label.isBlank() ? label.trim() : null);
            wifiMacRepo.save(mac);
        });
        if ("XMLHttpRequest".equalsIgnoreCase(requestedWith)) {
            return ResponseEntity.noContent().build();
        }
        ra.addFlashAttribute("success", "Nom de l'appareil mis à jour.");
        return "redirect:/mobile/devices";
    }

    /**
     * "Ajouter cet appareil" — redirige le navigateur vers une URL hors
     * walled-garden (le tunnel WireGuard du VPS, configuré dans
     * application.yml). UniFi intercepte la requête HTTP et 302 vers
     * /guest/s/default/?id=<MAC>&... qui capture la MAC. CaptivePortalParamFilter
     * la pose en session, l'utilisateur arrive sur /login déjà authentifié,
     * et RootController y déclenche bindMacToMember + tryAuthorize.
     *
     * Ne fonctionne que depuis le WiFi du coworking : l'IP du tunnel n'est
     * pas joignable depuis l'extérieur. Côté template, on affiche un message
     * d'info pour rappeler ce prérequis.
     */
    @GetMapping("/devices/add-current")
    public String addCurrentDevice() {
        return "redirect:" + wifiTrampolineUrl;
    }

    /**
     * Retire un appareil : désautorise la MAC auprès d'UniFi puis supprime
     * le binding en base. Le membre peut toujours réajouter l'appareil en
     * cliquant sur "Ajouter cet appareil" depuis ce même appareil.
     */
    @PostMapping("/devices/{id}/revoke")
    public String revokeDevice(@PathVariable UUID id,
                               Authentication auth, RedirectAttributes ra) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        wifiMacRepo.findById(id).ifPresent(mac -> {
            if (!mac.getMember().getId().equals(member.getId())) return;
            unifi.unauthorizeGuest(mac.getMac());
            wifiAccess.audit(member, mac.getMac(), "REVOKED", "self-service");
            wifiMacRepo.delete(mac);
        });
        ra.addFlashAttribute("success", "Appareil retiré.");
        return "redirect:/mobile/devices";
    }

    @GetMapping("/pack")
    public String pack(Authentication auth, Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        List<Presence> presences = memberService.getForMember(member.getId());

        BigDecimal totalChf = presences.stream()
                .filter(p -> p.getUnitPriceChf() != null && p.getUnitsConsumed() != null)
                .map(p -> p.getUnitPriceChf().multiply(p.getUnitsConsumed()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Active pack = permanent non expiré OU pack avec unités restantes.
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Europe/Zurich"));
        BigDecimal unitsRemaining = member.getPackUnitsRemaining();
        boolean packNotExpired = member.getPackExpires() == null
                || !member.getPackExpires().isBefore(today);
        boolean hasActivePack = (member.isPermanent() && packNotExpired)
                || (unitsRemaining != null
                    && unitsRemaining.compareTo(BigDecimal.ZERO) > 0
                    && packNotExpired);

        // Découpage des présences par pack: chaque PackTransaction délimite une période.
        // tx desc = du plus récent au plus ancien; current = premier, previous = suivants.
        List<PackTransaction> txsDesc = packTxRepo.findByMemberIdOrderByCreatedAtDesc(member.getId());

        java.util.Map<java.util.UUID, List<Presence>> presByTx = new java.util.LinkedHashMap<>();
        for (PackTransaction tx : txsDesc) presByTx.put(tx.getId(), new java.util.ArrayList<>());

        for (Presence p : presences) {
            LocalDateTime ref = p.getCheckedInAt() != null ? p.getCheckedInAt() : p.getDate().atStartOfDay();
            PackTransaction matched = null;
            for (PackTransaction tx : txsDesc) {
                if (!tx.getCreatedAt().isAfter(ref)) { matched = tx; break; }
            }
            // Présences antérieures à toute transaction connue: rattacher à la plus ancienne
            // (au pire on les laisse dans le pack actuel s'il n'y a aucune transaction).
            if (matched == null && !txsDesc.isEmpty()) {
                matched = txsDesc.get(txsDesc.size() - 1);
            }
            if (matched != null) presByTx.get(matched.getId()).add(p);
        }

        List<Presence> currentPackPresences = txsDesc.isEmpty()
                ? presences
                : presByTx.get(txsDesc.get(0).getId());

        List<PackGroup> previousPacks = new java.util.ArrayList<>();
        for (int i = 1; i < txsDesc.size(); i++) {
            PackTransaction tx = txsDesc.get(i);
            List<Presence> ps = presByTx.get(tx.getId());
            BigDecimal used = ps.stream()
                    .map(p -> p.getUnitsConsumed() != null ? p.getUnitsConsumed() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            previousPacks.add(new PackGroup(tx, ps, used));
        }

        model.addAttribute("member",               member);
        model.addAttribute("hasActivePack",        hasActivePack);
        model.addAttribute("currentPackPresences", currentPackPresences);
        model.addAttribute("previousPacks",        previousPacks);
        model.addAttribute("totalChf",             totalChf);
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
    public Object updateProfile(Authentication auth,
                                @RequestParam(required = false) String phone,
                                @RequestParam(required = false) String company,
                                @RequestParam(required = false) String tvaNumber,
                                @RequestParam(required = false) String address,
                                @RequestParam(required = false) String city,
                                @RequestParam(required = false) String postalCode,
                                @RequestParam(required = false) String country,
                                @RequestParam(required = false) String website,
                                @RequestParam(required = false) String linkedinUrl,
                                @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
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
        // Autosave (XHR) : pas de redirection, pas de page renvoyée — juste 204.
        if ("XMLHttpRequest".equalsIgnoreCase(requestedWith)) {
            return ResponseEntity.noContent().build();
        }
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
        if (notVerified(member, ra)) return "redirect:/mobile/renew";
        return zahlsService.createPaymentLink(member, membership)
                .map(url -> "redirect:" + url)
                .orElseGet(() -> {
                    ra.addFlashAttribute("error",
                            "Impossible de créer le lien de paiement zahls.ch. Contactez l'admin.");
                    return "redirect:/mobile/";
                });
    }

    /** Bloque les paiements tant que l'email n'est pas vérifié.
     *  Renvoie true (+ flash) si on doit interrompre l'action. */
    private boolean notVerified(Member member, RedirectAttributes ra) {
        boolean verified = member.getUser() != null && member.getUser().isEmailVerified();
        if (!verified) {
            ra.addFlashAttribute("error",
                    "Vérifie d'abord ton adresse email avant de procéder à un paiement.");
        }
        return !verified;
    }

    @GetMapping("/print")
    public String printPage(Authentication auth,
                            @RequestParam(required = false) String bought,
                            Model model) {
        Member member = memberRepo.findByEmail(auth.getName()).orElseThrow();
        if ("ok".equals(bought)) {
            model.addAttribute("success", "Paiement reçu — tes crédits d'impression ont été ajoutés !");
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
        if (notVerified(member, ra)) return "redirect:/mobile/print";
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
            model.addAttribute("success", "Paiement reçu — tes crédits de salle ont été ajoutés !");
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
                        ? "Crédits insuffisants — " + remaining + "h disponibles, " + hours + "h requises. Achète des crédits ci-dessous."
                        : "Tu n'as pas de crédits de salle. Achète un pack ci-dessous ou directement à l'Amicale (19 CHF/h).");
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
        if (notVerified(member, ra)) return "redirect:/mobile/rooms";
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
