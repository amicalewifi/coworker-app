package ch.amicalewifi.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "printer_jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PrinterJob {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(nullable = false)
    private String filename;

    @Builder.Default private int pages  = 1;
    @Builder.Default private int copies = 1;
    @Builder.Default private boolean color  = false;
    @Builder.Default private boolean duplex = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "print_job_status")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Builder.Default
    private PrintJobStatus status = PrintJobStatus.QUEUED;

    @Column(name = "printer_job_id")
    private String printerJobId;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cost_per_page", precision = 5, scale = 3)
    @Builder.Default
    private BigDecimal costPerPage = new BigDecimal("0.100");

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    public int getTotalPages()       { return pages * copies; }
    public BigDecimal getTotalCost() { return costPerPage.multiply(BigDecimal.valueOf(getTotalPages())); }
}
