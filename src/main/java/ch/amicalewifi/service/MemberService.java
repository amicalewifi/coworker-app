package ch.amicalewifi.service;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class MemberService {

    private final MemberRepository          memberRepo;
    private final PresenceRepository        presenceRepo;
    private final PackTransactionRepository packTxRepo;
    private final AccessEventRepository     eventRepo;

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
        if (m.getMembership().hasPack()) {
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
        Member m = getById(id);
        m.setMembership(membership);
        m.setPackUnitsTotal(membership.getPackUnits());
        m.setPackUnitsUsed(BigDecimal.ZERO);
        m.setPackExpires(membership.hasPack() ? LocalDate.now().plusMonths(3) : null);
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
        Member m = getById(memberId);
        PresenceType effective = unitaire ? type.toUnitaire() : type;
        boolean alreadyPresent = presenceRepo.findByMemberIdAndDateAndPresenceType(
                m.getId(), LocalDate.now(), effective).isPresent();
        if (alreadyPresent) {
            return presenceRepo.findByMemberIdAndDateAndPresenceType(m.getId(), LocalDate.now(), effective).orElseThrow();
        }
        BigDecimal units = m.isPermanent() ? BigDecimal.ZERO : effective.getUnits();
        Presence presence = presenceRepo.save(Presence.builder()
                .member(m)
                .date(LocalDate.now())
                .presenceType(effective)
                .status(PresenceStatus.ACTIVE)
                .checkedInAt(LocalDateTime.now())
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
