package ch.amicalewifi.service;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class MemberService {

    private final MemberRepository                 memberRepo;
    private final PresenceRepository               presenceRepo;
    private final PackTransactionRepository        packTxRepo;
    private final AccessEventRepository            eventRepo;
    private final PrintCreditTransactionRepository printCreditTxRepo;
    private final PrintDeclarationRepository       printDeclarationRepo;
    private final ConfCreditTransactionRepository  confCreditTxRepo;

    public List<Member>   getAll()                 { return memberRepo.findByActiveTrueOrderByLastNameAsc(); }
    public List<Member>   getAllIncludingInactive() { return memberRepo.findAllOrderByActiveDescLastNameAsc(); }
    public Member         getById(UUID id)         { return memberRepo.findById(id).orElseThrow(() -> new java.util.NoSuchElementException("Membre introuvable: " + id)); }
    public Member         getByEmail(String email) { return memberRepo.findByEmail(email).orElse(null); }
    public List<Member>   getPackAlerts()          { return memberRepo.findPackAlerts(LocalDate.now().plusDays(7)); }
    public List<Presence> getToday()               { return presenceRepo.findTodayActive(LocalDate.now()); }
    public List<Presence> getForMember(UUID id)    { return presenceRepo.findByMemberIdOrderByDateDescCheckedInAtDesc(id); }
    public long           countToday()             { return presenceRepo.countTodayActive(LocalDate.now()); }

    public Member create(Member m) {
        m.setPackUnitsTotal(m.getMembership().getPackUnits());
        m.setPackUnitsUsed(BigDecimal.ZERO);
        m.setConfCreditsTotalH(m.getMembership().getConfCredits());
        m.setConfCreditsUsedH(BigDecimal.ZERO);
        if (m.getMembership() == MembershipType.PERMANENT) {
            m.setPackExpires(LocalDate.now().plusMonths(1));
        } else if (m.getMembership().hasPack()) {
            m.setPackExpires(LocalDate.now().plusMonths(3));
        }
        log.info("Création membre: {} · {}", m.getDisplayName(), m.getMembership());
        Member saved = memberRepo.save(m);
        packTxRepo.save(PackTransaction.builder()
                .member(saved)
                .membership(saved.getMembership())
                .units(saved.getPackUnitsTotal())
                .amountChf(saved.getMembership().getPriceChf())
                .kind("create")
                .build());
        return saved;
    }

    public Member renewPack(UUID id, MembershipType membership) {
        return renewPack(id, membership, null);
    }

    public Member renewPack(UUID id, MembershipType membership, LocalDate validUntil) {
        Member m = getById(id);
        m.setMembership(membership);
        m.setPackUnitsTotal(membership.getPackUnits());
        m.setPackUnitsUsed(BigDecimal.ZERO);
        boolean singleSession = membership == MembershipType.PACK_MATIN
                            || membership == MembershipType.PACK_APMIDI;
        m.setPackExpires(validUntil != null ? validUntil :
                membership == MembershipType.PERMANENT ? LocalDate.now().plusMonths(1) :
                singleSession ? null :
                membership.hasPack() ? LocalDate.now().plusMonths(3) : null);
        m.setConfCreditsTotalH(membership.getConfCredits());
        m.setConfCreditsUsedH(BigDecimal.ZERO);
        m.setUpdatedAt(LocalDateTime.now());
        log.info("Renouvellement: {} · {}", m.getDisplayName(), membership);
        Member saved = memberRepo.save(m);
        packTxRepo.save(PackTransaction.builder()
                .member(saved)
                .membership(membership)
                .units(membership.getPackUnits())
                .amountChf(membership.getPriceChf())
                .kind("renew")
                .build());
        return saved;
    }

    public Member adjustPack(UUID id, MembershipType membership, BigDecimal unitsRemaining, LocalDate packExpires) {
        Member m = getById(id);
        m.setMembership(membership);
        BigDecimal total = membership.getPackUnits();
        if (total != null) {
            BigDecimal remaining = unitsRemaining != null ? unitsRemaining : BigDecimal.ZERO;
            if (remaining.compareTo(total) > 0) total = remaining;
            m.setPackUnitsTotal(total);
            m.setPackUnitsUsed(total.subtract(remaining));
        } else {
            m.setPackUnitsTotal(null);
            m.setPackUnitsUsed(null);
        }
        m.setPackExpires(packExpires);
        m.setUpdatedAt(LocalDateTime.now());
        log.info("Ajustement pack admin: {} → {} · {}j restantes · expire {}", m.getDisplayName(), membership, unitsRemaining, packExpires);
        return memberRepo.save(m);
    }

    public Presence permanentCheckin(UUID memberId) {
        Member m = getById(memberId);
        LocalDate today = LocalDate.now();
        if (presenceRepo.existsByMemberIdAndDateAndPresenceType(m.getId(), today, PresenceType.FULL_DAY)) {
            return presenceRepo.findByMemberIdAndDateAndPresenceType(m.getId(), today, PresenceType.FULL_DAY).orElseThrow();
        }
        Presence p = presenceRepo.save(Presence.builder()
                .member(m).date(today).presenceType(PresenceType.FULL_DAY)
                .status(PresenceStatus.ACTIVE).checkedInAt(today.atStartOfDay())
                .unitsConsumed(BigDecimal.ZERO).unitaire(false).build());
        log.info("Check-in permanent manuel: {} le {}", m.getDisplayName(), today);
        return p;
    }

    public Presence manualEntry(UUID memberId, PresenceType type, boolean unitaire) {
        return manualEntryForDate(memberId, type, unitaire, LocalDate.now());
    }

    public Presence manualEntryForDate(UUID memberId, PresenceType type, boolean unitaire, LocalDate date) {
        Member m = getById(memberId);
        PresenceType effective = unitaire ? type.toUnitaire() : type;
        if (presenceRepo.findByMemberIdAndDateAndPresenceType(m.getId(), date, effective).isPresent()) {
            throw new IllegalStateException("Une présence " + effective.getLabel() + " existe déjà le " + date + " pour ce membre.");
        }
        BigDecimal units = m.isPermanent() ? BigDecimal.ZERO : effective.getUnits();
        Presence presence = presenceRepo.save(Presence.builder()
                .member(m)
                .date(date)
                .presenceType(effective)
                .status(PresenceStatus.ACTIVE)
                .checkedInAt(date.atTime(8, 0))
                .unitsConsumed(units)
                .unitaire(unitaire || effective.isUnitaire())
                .build());
        if (!m.isPermanent()) {
            m.setPackUnitsUsed(m.getPackUnitsUsed().add(units));
            memberRepo.save(m);
        }
        eventRepo.save(AccessEvent.builder()
                .member(m)
                .badgeUid("manual")
                .eventType(AccessEventType.ENTRY_GRANTED)
                .presenceType(effective)
                .unitsConsumed(units)
                .terminalId("admin")
                .build());
        return presence;
    }

    public void addPrintCredits(UUID memberId, PrintPackType pack) {
        Member m = getById(memberId);
        m.setPrintQuota(m.getPrintQuota() + pack.getCredits());
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
        printCreditTxRepo.save(PrintCreditTransaction.builder()
                .member(m).packType(pack)
                .creditsAdded(pack.getCredits())
                .amountChf(pack.getPriceChf())
                .build());
        log.info("Crédits impression: +{} pour {}", pack.getCredits(), m.getDisplayName());
    }

    public void refundPrintCredits(UUID memberId, int credits) {
        Member m = getById(memberId);
        m.setPrintUsed(Math.max(0, m.getPrintUsed() - credits));
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
        log.info("Remboursement impression: {} crédits pour {}", credits, m.getDisplayName());
    }

    public void deductPrintCredits(UUID memberId, int credits) {
        Member m = getById(memberId);
        int remaining = m.getPrintQuota() - m.getPrintUsed();
        if (credits > remaining) {
            throw new IllegalStateException(
                "Crédits insuffisants — " + remaining + " disponibles, " + credits + " requis.");
        }
        m.setPrintUsed(m.getPrintUsed() + credits);
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
    }

    public void declarePrint(UUID memberId, int pagesBw, int pagesColor) {
        Member m = getById(memberId);
        int credits = pagesBw + pagesColor * 2;
        int remaining = m.getPrintQuota() - m.getPrintUsed();
        if (credits > remaining) {
            throw new IllegalStateException(
                "Crédits insuffisants — " + remaining + " disponibles, " + credits + " requis.");
        }
        m.setPrintUsed(m.getPrintUsed() + credits);
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
        printDeclarationRepo.save(PrintDeclaration.builder()
                .member(m).pagesBw(pagesBw).pagesColor(pagesColor)
                .creditsUsed(credits).build());
        log.info("Déclaration impression: {}N&B + {}C = {} crédits pour {}",
                pagesBw, pagesColor, credits, m.getDisplayName());
    }

    public void addConfCredits(UUID memberId, ConfHourPackType pack) {
        Member m = getById(memberId);
        m.setConfCreditsTotalH(m.getConfCreditsTotalH().add(pack.getHours()));
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
        confCreditTxRepo.save(ConfCreditTransaction.builder()
                .member(m).packType(pack)
                .hoursAdded(pack.getHours())
                .amountChf(pack.getPriceChf())
                .build());
        log.info("Crédits conf ajoutés: +{}h pour {}", pack.getHours(), m.getDisplayName());
    }

    public Optional<Member> findByPrintToken(UUID token) {
        return memberRepo.findByPrintToken(token);
    }

    public Member rotatePrintToken(UUID memberId) {
        Member m = getById(memberId);
        m.setPrintToken(UUID.randomUUID());
        m.setUpdatedAt(LocalDateTime.now());
        Member saved = memberRepo.save(m);
        log.info("Rotation print token: {}", m.getDisplayName());
        return saved;
    }

    public List<PrintDeclaration>       getPrintDeclarations(UUID memberId) {
        return printDeclarationRepo.findByMemberIdOrderByDeclaredAtDesc(memberId);
    }

    public List<PrintCreditTransaction> getPrintPurchases(UUID memberId) {
        return printCreditTxRepo.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    public Member removePresence(UUID presenceId) {
        Presence p = presenceRepo.findById(presenceId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Présence introuvable: " + presenceId));
        Member m = p.getMember();
        if (!m.isPermanent() && p.getUnitsConsumed() != null
                && p.getUnitsConsumed().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal refunded = m.getPackUnitsUsed().subtract(p.getUnitsConsumed());
            m.setPackUnitsUsed(refunded.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : refunded);
            m.setUpdatedAt(LocalDateTime.now());
            memberRepo.save(m);
        }
        presenceRepo.delete(p);
        log.info("Présence supprimée: {} le {} ({}j remboursés)", m.getDisplayName(), p.getDate(), p.getUnitsConsumed());
        return m;
    }

    public Presence checkout(UUID presenceId) {
        Presence p = presenceRepo.findById(presenceId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Présence introuvable: " + presenceId));
        if (p.getMember().isPermanent()) {
            throw new IllegalStateException("Les membres permanents restent présents toute la journée.");
        }
        p.setStatus(PresenceStatus.COMPLETED);
        p.setCheckedOutAt(LocalDateTime.now());
        return presenceRepo.save(p);
    }
}
