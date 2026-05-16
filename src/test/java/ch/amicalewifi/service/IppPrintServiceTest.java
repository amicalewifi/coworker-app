package ch.amicalewifi.service;

import ch.amicalewifi.model.Member;
import ch.amicalewifi.model.PrintJobStatus;
import ch.amicalewifi.model.PrinterJob;
import ch.amicalewifi.repository.MemberRepository;
import ch.amicalewifi.repository.PrinterJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IppPrintServiceTest {

    @Mock private MemberRepository      memberRepo;
    @Mock private PrinterJobRepository  jobRepo;

    @InjectMocks private IppPrintService service;

    private static final int COLOR_FACTOR = 2;
    private static final int BW_FACTOR    = 1;

    private UUID   token;
    private UUID   jobId;
    private Member member;

    @BeforeEach
    void setUp() {
        // @Value fields aren't injected by @InjectMocks ; set them manually.
        ReflectionTestUtils.setField(service, "colorFactor", COLOR_FACTOR);
        ReflectionTestUtils.setField(service, "bwFactor",    BW_FACTOR);

        token = UUID.randomUUID();
        jobId = UUID.randomUUID();
        member = Member.builder()
                .id(UUID.randomUUID())
                .firstName("Test")
                .lastName("User")
                .email("test@example.com")
                .printToken(token)
                .printQuota(50)
                .printUsed(0)
                .active(true)
                .build();
    }

    @Test
    void submit_rejectsDeferredBillingWhenAtQuota() {
        member.setPrintUsed(member.getPrintQuota()); // 0 remaining
        when(memberRepo.findByPrintToken(token)).thenReturn(Optional.of(member));

        assertThrows(IppPrintService.InsufficientPrintCreditsException.class,
                () -> service.submit(token, 0, "doc", 1, false, false, null));
        verify(jobRepo, never()).save(any());
    }

    @Test
    void submit_acceptsDeferredBillingWhenAnyCreditsRemain() {
        member.setPrintUsed(member.getPrintQuota() - 1); // 1 remaining
        when(memberRepo.findByPrintToken(token)).thenReturn(Optional.of(member));
        when(jobRepo.save(any())).thenAnswer(inv -> {
            PrinterJob j = inv.getArgument(0);
            j.setId(jobId);
            return j;
        });

        PrinterJob job = service.submit(token, 0, "doc", 1, false, false, null);

        assertEquals(jobId, job.getId());
        verify(jobRepo).save(any());
    }

    @Test
    void submit_legacyModeRejectsWhenCostExceedsRemaining() {
        member.setPrintUsed(45); // 5 remaining, but 10 pages B&W = 10 credits
        when(memberRepo.findByPrintToken(token)).thenReturn(Optional.of(member));

        assertThrows(IppPrintService.InsufficientPrintCreditsException.class,
                () -> service.submit(token, 10, "doc", 1, false, false, null));
    }

    @Test
    void complete_normalCaseDebitsExactCost() {
        member.setPrintUsed(10);
        PrinterJob job = PrinterJob.builder()
                .id(jobId).member(member)
                .filename("doc").pages(5).copies(1)
                .color(false).duplex(false)
                .status(PrintJobStatus.PRINTING)
                .build();
        when(jobRepo.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));

        service.complete(jobId);

        assertEquals(15, member.getPrintUsed());            // +5 B&W
        assertNull(job.getErrorMessage());
        assertEquals(PrintJobStatus.COMPLETED, job.getStatus());
    }

    @Test
    void complete_clampsOverdraft() {
        // Color, 4 pages, 1 copy → 8 credits. Member has 5 remaining.
        member.setPrintQuota(50);
        member.setPrintUsed(45); // 5 remaining
        PrinterJob job = PrinterJob.builder()
                .id(jobId).member(member)
                .filename("doc").pages(4).copies(1)
                .color(true).duplex(false)
                .status(PrintJobStatus.PRINTING)
                .build();
        when(jobRepo.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));

        service.complete(jobId);

        // printUsed clamps at printQuota — never exceeds it.
        assertEquals(member.getPrintQuota(), member.getPrintUsed());
        assertNotNull(job.getErrorMessage());
        assertTrue(job.getErrorMessage().contains("OVERDRAFT"));
        assertTrue(job.getErrorMessage().contains("cost=8"));
        assertTrue(job.getErrorMessage().contains("remaining=5"));
        assertTrue(job.getErrorMessage().contains("debited=5"));
        assertEquals(PrintJobStatus.COMPLETED, job.getStatus());
    }

    @Test
    void complete_clampsToZeroWhenAlreadyAtQuota() {
        member.setPrintUsed(member.getPrintQuota()); // 0 remaining
        PrinterJob job = PrinterJob.builder()
                .id(jobId).member(member)
                .filename("doc").pages(1).copies(1)
                .color(false).duplex(false)
                .status(PrintJobStatus.PRINTING)
                .build();
        when(jobRepo.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));

        service.complete(jobId);

        // Nothing debited beyond zero — printUsed stays at quota, not above.
        assertEquals(member.getPrintQuota(), member.getPrintUsed());
        assertNotNull(job.getErrorMessage());
        assertTrue(job.getErrorMessage().contains("debited=0"));
    }

    @Test
    void complete_idempotentOnAlreadyCompleted() {
        PrinterJob job = PrinterJob.builder()
                .id(jobId).member(member)
                .pages(5).copies(1).color(false)
                .status(PrintJobStatus.COMPLETED)
                .build();
        when(jobRepo.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));

        int before = member.getPrintUsed();
        service.complete(jobId);
        assertEquals(before, member.getPrintUsed()); // no-op, no double-debit
        verify(memberRepo, never()).save(any());
    }
}
