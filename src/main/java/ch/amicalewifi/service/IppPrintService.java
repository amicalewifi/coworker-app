package ch.amicalewifi.service;

import ch.amicalewifi.model.Member;
import ch.amicalewifi.model.PrintJobStatus;
import ch.amicalewifi.model.PrinterJob;
import ch.amicalewifi.repository.MemberRepository;
import ch.amicalewifi.repository.PrinterJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Orchestre le flux d'impression IPP : auth par token, vérif quota, ledger
 * sur PrinterJob, débit du quota à la complétion. Le comptage de pages est
 * fait côté broker CUPS (pdfinfo) — ce service reçoit juste les métadonnées.
 *
 * Source de vérité du débit : printUsed est incrémenté UNIQUEMENT dans
 * complete() — pas à l'admission. Échec = pas de débit (refund implicite).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IppPrintService {

    private final MemberRepository     memberRepo;
    private final PrinterJobRepository jobRepo;

    @Value("${amicale.business.print-color-factor:2}")
    private int colorFactor;

    @Value("${amicale.business.print-bw-factor:1}")
    private int bwFactor;

    @Transactional(readOnly = true)
    public Member authenticate(UUID token) {
        if (token == null) throw new InvalidPrintTokenException("token manquant");
        Member m = memberRepo.findByPrintToken(token)
                .orElseThrow(() -> new InvalidPrintTokenException("token inconnu"));
        if (!m.isActive()) throw new InvalidPrintTokenException("membre désactivé");
        return m;
    }

    @Transactional
    public PrinterJob submit(UUID token, int pages, String filename, int copies,
                             boolean color, boolean duplex, String submittedUsername) {
        Member m = authenticate(token);

        // pages == 0 = mode "deferred billing" (claudine-proxy ne connaît pas
        // encore le page count, il sera fourni au /complete via le polling
        // Kyocera). On crée le job sans vérifier le quota — la vérification
        // sera faite à /complete avec le coût réel.
        if (pages < 0)   throw new InvalidPrintRequestException("pages doit être >= 0");
        if (copies <= 0) throw new InvalidPrintRequestException("copies doit être > 0");

        if (submittedUsername != null && !submittedUsername.isBlank()
                && !submittedUsername.equalsIgnoreCase(m.getEmail())) {
            log.warn("Print job: username IPP '{}' ≠ email du token holder '{}' (token={})",
                    submittedUsername, m.getEmail(), token);
        }

        int factor = color ? colorFactor : bwFactor;
        if (pages > 0) {
            // Mode legacy : page count connu à l'admission, on vérifie le quota
            // dès maintenant (early reject si insuffisant — UX broker CUPS).
            int cost = pages * copies * factor;
            int remaining = m.getPrintQuota() - m.getPrintUsed();
            if (cost > remaining) {
                throw new InsufficientPrintCreditsException(
                        "crédits insuffisants — " + remaining + " disponibles, " + cost + " requis");
            }
        }

        BigDecimal costPerPage = color ? new BigDecimal("0.200") : new BigDecimal("0.100");
        PrinterJob job = jobRepo.save(PrinterJob.builder()
                .member(m)
                .filename(filename != null ? filename : "document")
                .pages(pages)
                .copies(copies)
                .color(color)
                .duplex(duplex)
                .status(PrintJobStatus.PRINTING)
                .costPerPage(costPerPage)
                .build());
        log.info("Print submit: {} · {}p × {} · {} · job={}",
                m.getDisplayName(), pages, copies, color ? "couleur" : "N&B", job.getId());
        return job;
    }

    @Transactional
    public void complete(UUID jobId) {
        complete(jobId, null);
    }

    /**
     * Finalise un job : applique l'override (si fourni) sur pages/copies/
     * color/duplex puis débite le membre. L'override permet le deferred
     * billing utilisé par claudine-proxy (Go) qui ne connaît le vrai page
     * count qu'en pollant la Kyocera après le job.
     *
     * Backward compat : appel sans override (override == null) garde le
     * comportement legacy — utilise les champs initialement passés au /submit.
     *
     * Idempotent : status == COMPLETED → no-op.
     */
    @Transactional
    public void complete(UUID jobId, CompleteOverride override) {
        PrinterJob job = jobRepo.findById(jobId)
                .orElseThrow(() -> new InvalidPrintRequestException("job inconnu: " + jobId));
        if (job.getStatus() == PrintJobStatus.COMPLETED) {
            log.debug("complete() idempotent no-op pour job {}", jobId);
            return;
        }
        if (override != null) {
            if (override.pages() != null && override.pages() > 0) job.setPages(override.pages());
            if (override.copies() != null && override.copies() > 0) job.setCopies(override.copies());
            if (override.color() != null)  job.setColor(override.color());
            if (override.duplex() != null) job.setDuplex(override.duplex());
        }

        int factor = job.isColor() ? colorFactor : bwFactor;
        int cost   = job.getPages() * job.getCopies() * factor;

        Member m = job.getMember();
        m.setPrintUsed(m.getPrintUsed() + cost);
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);

        job.setStatus(PrintJobStatus.COMPLETED);
        job.setCompletedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        jobRepo.save(job);
        log.info("Print complete: job={} · {} · {}p × {} · +{} crédits (total used={}/{})",
                jobId, m.getDisplayName(), job.getPages(), job.getCopies(),
                cost, m.getPrintUsed(), m.getPrintQuota());
    }

    /** Override pour le deferred billing : tous les champs nullables. */
    public record CompleteOverride(Integer pages, Integer copies,
                                    Boolean color, Boolean duplex) {}

    @Transactional
    public void deleteJob(UUID jobId) {
        PrinterJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) return;
        if (job.getStatus() == PrintJobStatus.COMPLETED && job.getMember() != null) {
            int factor = job.isColor() ? colorFactor : bwFactor;
            int cost   = job.getPages() * job.getCopies() * factor;
            Member m   = job.getMember();
            m.setPrintUsed(Math.max(0, m.getPrintUsed() - cost));
            m.setUpdatedAt(LocalDateTime.now());
            memberRepo.save(m);
            log.info("Print refund on delete: job={} · {} · -{} crédits (used now={}/{})",
                    jobId, m.getDisplayName(), cost, m.getPrintUsed(), m.getPrintQuota());
        }
        jobRepo.delete(job);
    }

    @Transactional
    public void error(UUID jobId, String message) {
        PrinterJob job = jobRepo.findById(jobId)
                .orElseThrow(() -> new InvalidPrintRequestException("job inconnu: " + jobId));
        if (job.getStatus() == PrintJobStatus.ERROR) {
            log.debug("error() idempotent no-op pour job {}", jobId);
            return;
        }
        job.setStatus(PrintJobStatus.ERROR);
        job.setErrorMessage(message != null ? message : "");
        job.setUpdatedAt(LocalDateTime.now());
        jobRepo.save(job);
        log.warn("Print error: job={} · {} · {}", jobId, job.getMember().getDisplayName(), message);
    }

    public static class InvalidPrintTokenException        extends RuntimeException {
        public InvalidPrintTokenException(String msg) { super(msg); }
    }
    public static class InsufficientPrintCreditsException extends RuntimeException {
        public InsufficientPrintCreditsException(String msg) { super(msg); }
    }
    public static class InvalidPrintRequestException      extends RuntimeException {
        public InvalidPrintRequestException(String msg) { super(msg); }
    }
}
