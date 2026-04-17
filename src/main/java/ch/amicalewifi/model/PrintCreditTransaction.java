package ch.amicalewifi.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "print_credit_transactions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PrintCreditTransaction {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "pack_type", nullable = false, length = 20)
    private PrintPackType packType;

    @Column(name = "credits_added", nullable = false)
    private int creditsAdded;

    @Column(name = "amount_chf", nullable = false, precision = 8, scale = 2)
    private BigDecimal amountChf;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
