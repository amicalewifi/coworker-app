package ch.amicalewifi.service;

import ch.amicalewifi.model.PrintJobStatus;
import ch.amicalewifi.model.PrinterJob;
import ch.amicalewifi.repository.PrinterJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Filet de sécurité pour les jobs IPP orphelins : PrinterJob restés en
 * status=PRINTING parce que le callback /complete depuis claudine-proxy n'est
 * jamais arrivé (Spring momentanément lent → timeout du retry, observé en
 * prod 2026-05-05 sur le job 01b3cb9c — job imprimé physiquement mais non
 * débité côté coworker-app).
 *
 * À chaque tick, on prend tout PrinterJob PRINTING plus vieux que
 * {@code staleAfter} avec un kyocera-uri stocké via /dispatched, on
 * interroge la Kyocera via le endpoint interne de claudine-proxy
 * ({@code /internal/poll-job}), et selon le state on finalise via
 * {@link IppPrintService#complete} (override = unité de billing rapportée par
 * la Kyocera) ou {@link IppPrintService#error}.
 *
 * La race avec le {@code pollAndComplete} encore en cours côté claudine-proxy
 * est neutralisée par {@code findByIdForUpdate} : le premier des deux
 * appelants à entrer la section critique débite, le second voit
 * status=COMPLETED et sort en no-op.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrinterJobReconciler {

    private final PrinterJobRepository jobRepo;
    private final IppPrintService      ippPrintService;

    @Value("${amicale.print.claudine-internal-url:http://172.18.0.1:8000}")
    private String claudineInternalUrl;

    @Value("${amicale.print.broker-key}")
    private String brokerKey;

    /** Âge minimum d'un job PRINTING avant qu'on le considère orphelin. */
    @Value("${amicale.print.reconcile-stale-after-minutes:15}")
    private long staleAfterMinutes;

    /** Âge maximum : au-delà, on logge un warning et on laisse pour intervention manuelle. */
    @Value("${amicale.print.reconcile-give-up-after-minutes:120}")
    private long giveUpAfterMinutes;

    private final RestTemplate rest = new RestTemplate();

    // RFC 8011 §5.3.7
    private static final int STATE_CANCELED  = 7;
    private static final int STATE_ABORTED   = 8;
    private static final int STATE_COMPLETED = 9;

    @Scheduled(fixedDelayString = "${amicale.print.reconcile-interval-ms:60000}")
    public void reconcile() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleAfterMinutes);
        List<PrinterJob> orphans = jobRepo
                .findByStatusAndCreatedAtBeforeAndPrinterJobIdNotNull(PrintJobStatus.PRINTING, cutoff);
        if (orphans.isEmpty()) return;
        log.info("PrinterJobReconciler: {} orphan job(s) à examiner (cutoff={})",
                orphans.size(), cutoff);
        for (PrinterJob job : orphans) {
            try {
                reconcileOne(job);
            } catch (Exception e) {
                log.warn("PrinterJobReconciler: échec sur job {}: {}", job.getId(), e.getMessage());
            }
        }
    }

    private void reconcileOne(PrinterJob job) {
        long ageMin = Duration.between(job.getCreatedAt(), LocalDateTime.now()).toMinutes();
        PollJobResponse poll;
        try {
            poll = queryKyocera(job.getPrinterJobId());
        } catch (RestClientException e) {
            // claudine-proxy ou Kyocera injoignable. Si le job est très vieux, on
            // logge un warning structuré pour qu'un admin intervienne. Sinon on
            // laisse pour le prochain tick.
            if (ageMin >= giveUpAfterMinutes) {
                log.warn("PrinterJobReconciler: job {} PRINTING depuis {} min, Kyocera injoignable ({}). À investiguer manuellement.",
                        job.getId(), ageMin, e.getMessage());
            }
            return;
        }
        if (poll == null) return;

        log.info("PrinterJobReconciler: job={} state={} billUnit={} ({}) color={} duplex={} age={}min",
                job.getId(), poll.state(), poll.billUnit(), poll.unitLabel(),
                poll.color(), poll.duplex(), ageMin);

        switch (poll.state()) {
            case STATE_COMPLETED -> ippPrintService.complete(job.getId(),
                    new IppPrintService.CompleteOverride(
                            poll.billUnit(), 1, poll.color(), poll.duplex()));
            case STATE_CANCELED, STATE_ABORTED -> ippPrintService.error(job.getId(),
                    "Kyocera reported job-state=" + poll.state() + " (reconciled by sweeper)");
            default -> {
                // pending / processing / processing-stopped — pas encore fini,
                // on retentera au prochain tick. Si vraiment trop vieux, alerte.
                if (ageMin >= giveUpAfterMinutes) {
                    log.warn("PrinterJobReconciler: job {} PRINTING depuis {} min, Kyocera state={}. À investiguer.",
                            job.getId(), ageMin, poll.state());
                }
            }
        }
    }

    private PollJobResponse queryKyocera(String kyoceraJobUri) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Print-Broker-Key", brokerKey);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(
                Map.of("kyoceraJobUri", kyoceraJobUri), headers);
        ResponseEntity<PollJobResponse> resp = rest.postForEntity(
                claudineInternalUrl + "/internal/poll-job", entity, PollJobResponse.class);
        return resp.getBody();
    }

    public record PollJobResponse(int state, int billUnit, String unitLabel,
                                  boolean color, boolean duplex) {}
}
