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

        if (pages <= 0)  throw new InvalidPrintRequestException("pages doit être > 0");
        if (copies <= 0) throw new InvalidPrintRequestException("copies doit être > 0");

        if (submittedUsername != null && !submittedUsername.isBlank()
                && !submittedUsername.equalsIgnoreCase(m.getEmail())) {
            log.warn("Print job: username IPP '{}' ≠ email du token holder '{}' (token={})",
                    submittedUsername, m.getEmail(), token);
        }

        int factor = color ? colorFactor : bwFactor;
        int cost   = pages * copies * factor;
        int remaining = m.getPrintQuota() - m.getPrintUsed();
        if (cost > remaining) {
            throw new InsufficientPrintCreditsException(
                    "crédits insuffisants — " + remaining + " disponibles, " + cost + " requis");
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
        log.info("Print submit: {} · {}p × {} · {} · cost={} · job={}",
                m.getDisplayName(), pages, copies, color ? "couleur" : "N&B", cost, job.getId());
        return job;
    }

    @Transactional
    public void complete(UUID jobId) {
        PrinterJob job = jobRepo.findById(jobId)
                .orElseThrow(() -> new InvalidPrintRequestException("job inconnu: " + jobId));
        if (job.getStatus() == PrintJobStatus.COMPLETED) {
            log.debug("complete() idempotent no-op pour job {}", jobId);
            return;
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
        log.info("Print complete: job={} · {} · +{} crédits (total used={}/{})",
                jobId, m.getDisplayName(), cost, m.getPrintUsed(), m.getPrintQuota());
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
