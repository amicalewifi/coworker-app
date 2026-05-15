package ch.amicalewifi.controller;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import ch.amicalewifi.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final DashboardService          dashboardService;
    private final MemberService             memberService;
    private final RoomService               roomService;
    private final UnifiService              unifiService;
    private final RoomRepository            roomRepo;
    private final MemberRepository          memberRepo;
    private final UserRepository            userRepo;
    private final PrinterJobRepository      printerRepo;
    private final PackTransactionRepository packTxRepo;
    private final PrinterService                   printerService;
    private final IppPrintService                  ippPrintService;
    private final PrintCreditTransactionRepository printCreditTxRepo;
    private final PasswordEncoder                  passwordEncoder;

    private static final String CHARS = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RNG = new SecureRandom();

    @GetMapping({"", "/"})
    public String dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Model model) {
        LocalDate selectedDate = (date != null) ? date : LocalDate.now();
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
        DashboardService.Stats stats = dashboardService.getStats();

        model.addAttribute("stats",                  stats);
        model.addAttribute("todayDate",              LocalDate.now());
        model.addAttribute("selectedDate",           selectedDate);
        model.addAttribute("prevDay",                selectedDate.minusDays(1).toString());
        model.addAttribute("nextDay",                selectedDate.plusDays(1).toString());
        model.addAttribute("isToday",                selectedDate.equals(LocalDate.now()));
        model.addAttribute("presences",              memberService.getForDate(selectedDate));
        model.addAttribute("allMembers",             memberService.getAll());
        model.addAttribute("rooms",                  roomService.getAll());
        model.addAttribute("todayBookings",          todayBookings);
        model.addAttribute("bookedRoomIds",          bookedRoomIds);
        model.addAttribute("currentlyBookedRoomIds", currentlyBookedRoomIds);
        model.addAttribute("bookedHoursByRoom",      bookedHoursByRoom);
        return "admin/dashboard";
    }

    @GetMapping("/presences")
    public String presences(@RequestParam(required = false) String month, Model model) {
        YearMonth selectedMonth = (month != null && !month.isBlank()) ? YearMonth.parse(month) : YearMonth.now();
        Map<LocalDate, List<Presence>> byDay = memberService.getForMonth(selectedMonth).stream()
                .collect(Collectors.groupingBy(Presence::getDate, TreeMap::new, Collectors.toList()));
        LocalDate firstOfMonth = selectedMonth.atDay(1);
        model.addAttribute("selectedMonth",  selectedMonth);
        model.addAttribute("firstOfMonth",   firstOfMonth);
        model.addAttribute("presencesByDay", byDay);
        model.addAttribute("prevMonth",      selectedMonth.minusMonths(1).toString());
        model.addAttribute("nextMonth",      selectedMonth.plusMonths(1).toString());
        model.addAttribute("isCurrentMonth", selectedMonth.equals(YearMonth.now()));
        model.addAttribute("daysInMonth",    selectedMonth.lengthOfMonth());
        model.addAttribute("firstDayOffset", firstOfMonth.getDayOfWeek().getValue() - 1);
        model.addAttribute("todayDate",      LocalDate.now());
        model.addAttribute("totalPresences", byDay.values().stream().mapToInt(List::size).sum());
        model.addAttribute("activeDays",     byDay.size());
        return "admin/presences";
    }

    @GetMapping("/members")
    public String members(@RequestParam(required = false) String filter, Model model) {
        List<Member> members;
        if ("present".equals(filter)) {
            members = memberService.getToday().stream()
                    .map(p -> p.getMember()).distinct().collect(Collectors.toList());
        } else if ("packs".equals(filter)) {
            members = memberService.getAll().stream()
                    .filter(m -> m.getMembership().name().startsWith("PACK_"))
                    .collect(Collectors.toList());
        } else if ("permanent".equals(filter)) {
            members = memberRepo.findByMembershipAndActiveTrue(MembershipType.PERMANENT);
        } else {
            members = memberService.getAllIncludingInactive();
        }
        model.addAttribute("members",       members);
        model.addAttribute("filter",        filter);
        model.addAttribute("alerts",        memberService.getPackAlerts());
        model.addAttribute("memberships",   MembershipType.values());
        model.addAttribute("presenceTypes", PresenceType.values());
        return "admin/members";
    }

    @GetMapping("/members/{id}")
    public String memberDetail(@PathVariable UUID id, Model model) {
        model.addAttribute("member",       memberService.getById(id));
        model.addAttribute("presences",    memberService.getForMember(id));
        model.addAttribute("roomBookings", roomService.getForMember(id));
        model.addAttribute("printJobs",    printerRepo.findByMemberIdOrderByCreatedAtDesc(id));
        model.addAttribute("memberships",  MembershipType.values());
        return "admin/member-detail";
    }

    @GetMapping("/members/{id}/photo")
    public ResponseEntity<byte[]> memberPhoto(@PathVariable UUID id) {
        return memberRepo.findById(id)
                .filter(m -> m.getPhoto() != null)
                .map(m -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(m.getPhotoType()))
                        .body(m.getPhoto()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/members/{id}/photo")
    public String uploadMemberPhoto(@PathVariable UUID id,
                                    @RequestParam("file") MultipartFile file,
                                    RedirectAttributes ra) throws IOException {
        var member = memberRepo.findById(id).orElseThrow();
        if (!file.isEmpty() && file.getContentType() != null && file.getContentType().startsWith("image/")) {
            member.setPhoto(file.getBytes());
            member.setPhotoType(file.getContentType());
            memberRepo.save(member);
            ra.addFlashAttribute("success", "Photo mise à jour.");
        }
        return "redirect:/admin/members/" + id;
    }

    @PostMapping("/members/create")
    public String createMember(@RequestParam String firstName,
                               @RequestParam String lastName,
                               @RequestParam String email,
                               @RequestParam(required = false) String phone,
                               @RequestParam(required = false) String company,
                               @RequestParam(required = false) String tvaNumber,
                               @RequestParam(required = false) String badgeUid,
                               @RequestParam(required = false) String address,
                               @RequestParam(required = false) String city,
                               @RequestParam(required = false) String postalCode,
                               @RequestParam(required = false) String country,
                               @RequestParam MembershipType membership,
                               RedirectAttributes ra) {
        memberService.create(Member.builder()
                .firstName(firstName).lastName(lastName).email(email)
                .phone(phone).company(company).tvaNumber(tvaNumber).badgeUid(badgeUid)
                .address(address != null && !address.isBlank() ? address : null)
                .city(city != null && !city.isBlank() ? city : null)
                .postalCode(postalCode != null && !postalCode.isBlank() ? postalCode : null)
                .country(country != null && !country.isBlank() ? country : "Suisse")
                .membership(membership).build());
        ra.addFlashAttribute("success", "Membre " + firstName + " " + lastName + " créé avec succès");
        return "redirect:/admin/members";
    }

    @PostMapping("/members/{id}/set-print-quota")
    public String setPrintQuota(@PathVariable UUID id,
                                @RequestParam int printQuota,
                                RedirectAttributes ra) {
        Member m = memberRepo.findById(id).orElseThrow();
        m.setPrintQuota(printQuota);
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
        ra.addFlashAttribute("success", "Quota d'impression mis à jour: " + printQuota + " pages");
        return "redirect:/admin/members/" + id;
    }


    @PostMapping("/members/{id}/renew")
    public String renewPack(@PathVariable UUID id,
                            @RequestParam MembershipType membership,
                            @RequestParam(required = false) String validUntil,
                            RedirectAttributes ra) {
        LocalDate expiryDate = (validUntil != null && !validUntil.isBlank()) ? LocalDate.parse(validUntil) : null;
        Member m = memberService.renewPack(id, membership, expiryDate);
        ra.addFlashAttribute("success", "Pack renouvelé: " + membership.getLabel() + " pour " + m.getDisplayName());
        return "redirect:/admin/members/" + id;
    }

    @PostMapping("/members/{id}/adjust-pack")
    public String adjustPack(@PathVariable UUID id,
                             @RequestParam MembershipType membership,
                             @RequestParam(required = false) BigDecimal unitsRemaining,
                             @RequestParam(required = false) String packExpires,
                             @RequestParam(required = false) BigDecimal confHours,
                             RedirectAttributes ra) {
        LocalDate expiryDate = (packExpires != null && !packExpires.isBlank()) ? LocalDate.parse(packExpires) : null;
        Member m = memberService.adjustPack(id, membership, unitsRemaining, expiryDate);
        if (confHours != null) {
            m.setConfCreditsTotalH(confHours);
            m.setUpdatedAt(LocalDateTime.now());
            memberRepo.save(m);
        }
        ra.addFlashAttribute("success", "Solde ajusté pour " + m.getDisplayName());
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

    @PostMapping("/members/{id}/update")
    public String updateMember(@PathVariable UUID id,
                               @RequestParam(required = false) String firstName,
                               @RequestParam(required = false) String lastName,
                               @RequestParam(required = false) String phone,
                               @RequestParam(required = false) String company,
                               @RequestParam(required = false) String tvaNumber,
                               @RequestParam(required = false) String badgeUid,
                               @RequestParam(required = false) String address,
                               @RequestParam(required = false) String city,
                               @RequestParam(required = false) String postalCode,
                               @RequestParam(required = false) String country,
                               @RequestParam(required = false) String notes,
                               @RequestParam(required = false) String website,
                               @RequestParam(required = false) String linkedinUrl,
                               RedirectAttributes ra) {
        Member m = memberRepo.findById(id).orElseThrow();
        if (firstName != null && !firstName.isBlank()) m.setFirstName(firstName);
        if (lastName  != null && !lastName.isBlank())  m.setLastName(lastName);
        m.setPhone(phone);
        m.setCompany(company);
        m.setTvaNumber(tvaNumber);
        m.setBadgeUid(badgeUid != null && !badgeUid.isBlank() ? badgeUid : null);
        m.setAddress(address != null && !address.isBlank() ? address : null);
        m.setCity(city != null && !city.isBlank() ? city : null);
        m.setPostalCode(postalCode != null && !postalCode.isBlank() ? postalCode : null);
        m.setCountry(country != null && !country.isBlank() ? country : "Suisse");
        m.setWebsite(website != null && !website.isBlank() ? website : null);
        m.setLinkedinUrl(linkedinUrl != null && !linkedinUrl.isBlank() ? linkedinUrl : null);
        m.setNotes(notes);
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
        ra.addFlashAttribute("success", "Informations du membre mises à jour");
        return "redirect:/admin/members/" + id;
    }

    @PostMapping("/members/{id}/delete")
    public String deleteMember(@PathVariable UUID id, RedirectAttributes ra) {
        Member m = memberRepo.findById(id).orElseThrow();
        String name = m.getDisplayName();
        User user = m.getUser();
        memberRepo.delete(m);
        if (user != null) userRepo.delete(user);
        ra.addFlashAttribute("success", "Membre " + name + " supprimé définitivement");
        return "redirect:/admin/members";
    }

    @PostMapping("/members/{id}/reset-password")
    public String resetPassword(@PathVariable UUID id, RedirectAttributes ra) {
        Member m = memberRepo.findById(id).orElseThrow();
        if (m.getUser() == null) {
            ra.addFlashAttribute("error", "Ce membre n'a pas de compte utilisateur associé");
            return "redirect:/admin/members/" + id;
        }
        StringBuilder tmp = new StringBuilder(12);
        for (int i = 0; i < 12; i++) tmp.append(CHARS.charAt(RNG.nextInt(CHARS.length())));
        String tempPassword = tmp.toString();
        m.getUser().setPasswordHash(passwordEncoder.encode(tempPassword));
        userRepo.save(m.getUser());
        ra.addFlashAttribute("tempPassword", tempPassword);
        ra.addFlashAttribute("success",
                "Mot de passe réinitialisé pour " + m.getDisplayName() + ". Nouveau mot de passe temporaire ci-dessous.");
        return "redirect:/admin/members/" + id;
    }

    @PostMapping("/members/{id}/create-account")
    public String createAccount(@PathVariable UUID id, RedirectAttributes ra) {
        Member m = memberRepo.findById(id).orElseThrow();
        if (m.getUser() != null) {
            ra.addFlashAttribute("error", "Ce membre a déjà un compte utilisateur");
            return "redirect:/admin/members/" + id;
        }
        if (userRepo.existsByEmail(m.getEmail())) {
            ra.addFlashAttribute("error", "Un compte existe déjà avec l'email " + m.getEmail());
            return "redirect:/admin/members/" + id;
        }
        StringBuilder tmp = new StringBuilder(12);
        for (int i = 0; i < 12; i++) tmp.append(CHARS.charAt(RNG.nextInt(CHARS.length())));
        String tempPassword = tmp.toString();
        User user = userRepo.save(User.builder()
                .email(m.getEmail())
                .passwordHash(passwordEncoder.encode(tempPassword))
                .role(UserRole.COWORKER)
                .build());
        m.setUser(user);
        memberRepo.save(m);
        ra.addFlashAttribute("tempPassword", tempPassword);
        ra.addFlashAttribute("success",
                "Compte créé pour " + m.getDisplayName() + ". Mot de passe temporaire ci-dessous.");
        return "redirect:/admin/members/" + id;
    }

    @PostMapping("/members/{id}/unlink-account")
    public String unlinkAccount(@PathVariable UUID id, RedirectAttributes ra) {
        Member m = memberRepo.findById(id).orElseThrow();
        if (m.getUser() == null) {
            ra.addFlashAttribute("error", "Ce membre n'a pas de compte utilisateur");
            return "redirect:/admin/members/" + id;
        }
        m.setUser(null);
        memberRepo.save(m);
        ra.addFlashAttribute("success", "Compte désassocié. Le membre ne peut plus se connecter.");
        return "redirect:/admin/members/" + id;
    }

    @PostMapping("/printer/credits/add")
    public String addPrintCredits(@RequestParam UUID memberId,
                                  @RequestParam PrintPackType packType,
                                  RedirectAttributes ra) {
        Member m = memberService.getById(memberId);
        m.setPrintQuota(m.getPrintQuota() + packType.getCredits());
        memberRepo.save(m);
        printCreditTxRepo.save(PrintCreditTransaction.builder()
                .member(m)
                .packType(packType)
                .creditsAdded(packType.getCredits())
                .amountChf(packType.getPriceChf())
                .build());
        ra.addFlashAttribute("success",
                "+" + packType.getCredits() + " crédits ajoutés pour " + m.getDisplayName()
                + " — Fr. " + packType.getPriceChf());
        return "redirect:/admin/printer";
    }

    @PostMapping("/printer/jobs/clean-completed")
    public String cleanCompletedJobs(RedirectAttributes ra) {
        List<ch.amicalewifi.model.PrinterJob> completed =
                printerRepo.findByStatusOrderByCreatedAtAsc(PrintJobStatus.COMPLETED);
        printerRepo.deleteAll(completed);
        ra.addFlashAttribute("success", completed.size() + " job(s) imprimés supprimés");
        return "redirect:/admin/printer";
    }

    @PostMapping("/printer/jobs/clear-printing")
    public String clearPrintingJobs(RedirectAttributes ra) {
        List<ch.amicalewifi.model.PrinterJob> jobs =
                printerRepo.findByStatusOrderByCreatedAtAsc(PrintJobStatus.PRINTING);
        jobs.forEach(j -> j.setStatus(PrintJobStatus.CANCELLED));
        printerRepo.saveAll(jobs);
        ra.addFlashAttribute("success", jobs.size() + " job(s) en cours annulé(s)");
        return "redirect:/admin/printer";
    }

    @PostMapping("/printer/jobs/clear-queued")
    public String clearQueuedJobs(RedirectAttributes ra) {
        List<ch.amicalewifi.model.PrinterJob> jobs =
                printerRepo.findByStatusOrderByCreatedAtAsc(PrintJobStatus.QUEUED);
        jobs.forEach(j -> j.setStatus(PrintJobStatus.CANCELLED));
        printerRepo.saveAll(jobs);
        ra.addFlashAttribute("success", jobs.size() + " job(s) en attente annulé(s)");
        return "redirect:/admin/printer";
    }

    @PostMapping("/printer/jobs/{id}/cancel")
    public String cancelPrinterJob(@PathVariable UUID id, RedirectAttributes ra) {
        printerRepo.findById(id).ifPresent(j -> {
            j.setStatus(PrintJobStatus.CANCELLED);
            printerRepo.save(j);
        });
        ra.addFlashAttribute("success", "Job d'impression annulé");
        return "redirect:/admin/printer";
    }

    @PostMapping("/printer/jobs/{id}/delete")
    public String deletePrinterJob(@PathVariable UUID id, RedirectAttributes ra) {
        ippPrintService.deleteJob(id);
        ra.addFlashAttribute("success", "Job d'impression supprimé et crédits remboursés si applicable");
        return "redirect:/admin/printer";
    }

    @PostMapping("/presences/manual")
    public String manualEntry(@RequestParam UUID memberId,
                              @RequestParam PresenceType presenceType,
                              @RequestParam(defaultValue = "false") boolean unitaire,
                              RedirectAttributes ra) {
        try {
            memberService.manualEntry(memberId, presenceType, unitaire);
            ra.addFlashAttribute("success", "Entrée enregistrée: " + presenceType.getLabel());
        } catch (DataIntegrityViolationException e) {
            ra.addFlashAttribute("error", "Une présence " + presenceType.getLabel() + " existe déjà aujourd'hui pour ce membre.");
        }
        return "redirect:/admin/";
    }

    @PostMapping("/presences/{id}/checkout")
    public String checkout(@PathVariable UUID id, RedirectAttributes ra) {
        try {
            memberService.checkout(id);
            ra.addFlashAttribute("success", "Départ enregistré");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/";
    }

    @PostMapping("/members/{id}/presences/add")
    public String addPresence(@PathVariable UUID id,
                              @RequestParam PresenceType presenceType,
                              @RequestParam(required = false, defaultValue = "false") boolean unitaire,
                              @RequestParam String date,
                              RedirectAttributes ra) {
        try {
            memberService.manualEntryForDate(id, presenceType, unitaire, LocalDate.parse(date));
            ra.addFlashAttribute("success", "Présence ajoutée : " + presenceType.getLabel());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/members/" + id;
    }

    @PostMapping("/presences/{id}/remove")
    public String removePresence(@PathVariable UUID id,
                                 @RequestParam UUID memberId,
                                 RedirectAttributes ra) {
        try {
            memberService.removePresence(id);
            ra.addFlashAttribute("success", "Présence supprimée et crédits remboursés");
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/members/" + memberId;
    }

    @PostMapping("/presences/permanent-checkin/{memberId}")
    public String permanentCheckin(@PathVariable UUID memberId, RedirectAttributes ra) {
        Member m = memberService.getById(memberId);
        memberService.permanentCheckin(memberId);
        ra.addFlashAttribute("success", m.getDisplayName() + " enregistré comme présent aujourd'hui");
        return "redirect:/admin/";
    }

    @GetMapping("/rooms")
    public String rooms(@RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                        @RequestParam(required = false) String month,
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

        YearMonth ym = (month != null) ? YearMonth.parse(month) : YearMonth.from(d);
        List<RoomBooking> monthBookings = roomService.getForMonth(ym);
        Map<Integer, List<RoomBooking>> monthBookingsByDay = monthBookings.stream()
                .collect(Collectors.groupingBy(b -> b.getDate().getDayOfMonth()));
        int firstDow = ym.atDay(1).getDayOfWeek().getValue(); // 1=Mon … 7=Sun

        model.addAttribute("rooms",             roomService.getAll());
        model.addAttribute("bookings",          bookings);
        model.addAttribute("bookedRoomIds",     bookedRoomIds);
        model.addAttribute("bookedHoursByRoom", bookedHoursByRoom);
        model.addAttribute("date",              d);
        model.addAttribute("allBookings",       roomService.getUpcomingFrom(LocalDate.now()));
        model.addAttribute("members",           memberService.getAll());
        model.addAttribute("monthYear",         ym);
        model.addAttribute("daysInMonth",       ym.lengthOfMonth());
        model.addAttribute("firstDow",          firstDow);
        model.addAttribute("monthBookingsByDay",monthBookingsByDay);
        model.addAttribute("prevMonth",         ym.minusMonths(1).toString());
        model.addAttribute("nextMonth",         ym.plusMonths(1).toString());
        model.addAttribute("prevDay",           d.minusDays(1).toString());
        model.addAttribute("nextDay",           d.plusDays(1).toString());
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
    public String printer(@RequestParam(required = false) String month,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                          Model model) {
        YearMonth ym = (month != null) ? YearMonth.parse(month) : YearMonth.now();
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to   = ym.atEndOfMonth().atTime(23, 59, 59);

        LocalDate selectedDay = (day != null) ? day : LocalDate.now();
        LocalDateTime dayFrom = selectedDay.atStartOfDay();
        LocalDateTime dayTo   = selectedDay.atTime(23, 59, 59);

        model.addAttribute("printing",      printerRepo.findByStatusOrderByCreatedAtAsc(PrintJobStatus.PRINTING));
        model.addAttribute("queued",        printerRepo.findByStatusOrderByCreatedAtAsc(PrintJobStatus.QUEUED));
        model.addAttribute("dayJobs",       printerRepo.findByStatusAndCreatedAtBetweenOrderByMemberLastNameAscCreatedAtDesc(PrintJobStatus.COMPLETED, dayFrom, dayTo));
        model.addAttribute("creditTxs",     printCreditTxRepo.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to));
        model.addAttribute("printPacks",    ch.amicalewifi.model.PrintPackType.values());
        model.addAttribute("allMembers",    memberService.getAll());
        model.addAttribute("billingMonth",  ym);
        model.addAttribute("prevMonth",     ym.minusMonths(1).toString());
        model.addAttribute("nextMonth",     ym.plusMonths(1).toString());
        model.addAttribute("selectedDay",   selectedDay);
        model.addAttribute("prevDay",       selectedDay.minusDays(1).toString());
        model.addAttribute("nextDay",       selectedDay.plusDays(1).toString());
        model.addAttribute("isDayToday",    selectedDay.equals(LocalDate.now()));
        model.addAttribute("printerOnline", printerService.isOnline());
        model.addAttribute("printerHost",   printerService.getHost());
        return "admin/printer";
    }

    @GetMapping("/billing")
    public String billing(@RequestParam(required = false) String month, Model model) {
        YearMonth ym = (month != null) ? YearMonth.parse(month) : YearMonth.now();
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to   = ym.atEndOfMonth().atTime(23, 59, 59);

        var packTxs      = packTxRepo.findByCreatedAtBetweenOrderByMemberLastNameAscCreatedAtDesc(from, to);
        var printCreditTxs = printCreditTxRepo.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to);
        java.math.BigDecimal packTotal  = packTxs.stream().map(t -> t.getAmountChf()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.math.BigDecimal printTotal = printCreditTxs.stream().map(t -> t.getAmountChf()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        model.addAttribute("packTxs",        packTxs);
        model.addAttribute("printCreditTxs", printCreditTxs);
        model.addAttribute("packTotal",      packTotal);
        model.addAttribute("printTotal",     printTotal);
        model.addAttribute("grandTotal",     packTotal.add(printTotal));
        model.addAttribute("billingMonth",   ym);
        model.addAttribute("prevMonth",      ym.minusMonths(1).toString());
        model.addAttribute("nextMonth",      ym.plusMonths(1).toString());
        return "admin/billing";
    }

    @GetMapping("/wifi")
    public String wifi(Model model) {
        var clients = unifiService.getConnectedClients();
        model.addAttribute("clients", clients);
        model.addAttribute("stats",   unifiService.getSiteStats());
        return "admin/wifi";
    }


    // ── User management ──────────────────────────────────────────────────────

    @GetMapping("/users")
    public String users(Model model) {
        List<User> users = userRepo.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
        Map<UUID, Member> memberByUserId = memberRepo.findAll().stream()
                .filter(m -> m.getUser() != null)
                .collect(Collectors.toMap(m -> m.getUser().getId(), m -> m));
        model.addAttribute("users", users);
        model.addAttribute("memberByUserId", memberByUserId);
        model.addAttribute("roles", new UserRole[]{UserRole.ADMIN, UserRole.COWORKER});
        return "admin/users";
    }

    @PostMapping("/users/{id}/set-role")
    public String setUserRole(@PathVariable UUID id,
                              @RequestParam UserRole role,
                              Authentication auth,
                              RedirectAttributes ra) {
        User u = userRepo.findById(id).orElseThrow();
        if (u.getEmail().equals(auth.getName()) && role != UserRole.ADMIN) {
            ra.addFlashAttribute("error", "Vous ne pouvez pas retirer votre propre rôle admin");
            return "redirect:/admin/users";
        }
        u.setRole(role);
        userRepo.save(u);
        ra.addFlashAttribute("success", "Rôle mis à jour pour " + u.getEmail());
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/reset-password")
    public String resetUserPassword(@PathVariable UUID id, RedirectAttributes ra) {
        User u = userRepo.findById(id).orElseThrow();
        StringBuilder tmp = new StringBuilder(12);
        for (int i = 0; i < 12; i++) tmp.append(CHARS.charAt(RNG.nextInt(CHARS.length())));
        String tempPassword = tmp.toString();
        u.setPasswordHash(passwordEncoder.encode(tempPassword));
        userRepo.save(u);
        ra.addFlashAttribute("tempPassword", tempPassword);
        ra.addFlashAttribute("success", "Mot de passe réinitialisé pour " + u.getEmail());
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/delete")
    public String deleteUser(@PathVariable UUID id, Authentication auth, RedirectAttributes ra) {
        User u = userRepo.findById(id).orElseThrow();
        if (u.getEmail().equals(auth.getName())) {
            ra.addFlashAttribute("error", "Vous ne pouvez pas supprimer votre propre compte");
            return "redirect:/admin/users";
        }
        memberRepo.findAll().stream()
                .filter(m -> u.equals(m.getUser()))
                .findFirst()
                .ifPresent(m -> { m.setUser(null); memberRepo.save(m); });
        userRepo.delete(u);
        ra.addFlashAttribute("success", "Compte " + u.getEmail() + " supprimé");
        return "redirect:/admin/users";
    }
}
