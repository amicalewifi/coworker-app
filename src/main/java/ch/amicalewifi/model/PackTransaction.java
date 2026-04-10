package ch.amicalewifi.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "pack_transactions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PackTransaction {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "membership", columnDefinition = "membership_type")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private MembershipType membership;

    /** Unités achetées (peut être null pour PERMANENT/DOMICILIATION) */
    @Column(name = "units", precision = 5, scale = 1)
    private BigDecimal units;

    /** Prix facturé en CHF */
    @Column(name = "amount_chf", nullable = false, precision = 8, scale = 2)
    private BigDecimal amountChf;

    /** "create" ou "renew" */
    @Column(name = "kind", nullable = false, length = 20)
    @Builder.Default
    private String kind = "renew";

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
