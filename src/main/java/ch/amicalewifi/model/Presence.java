package ch.amicalewifi.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "presences")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Presence {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false)
    @Builder.Default
    private LocalDate date = LocalDate.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "presence_type", columnDefinition = "presence_type")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private PresenceType presenceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "presence_status")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Builder.Default
    private PresenceStatus status = PresenceStatus.ACTIVE;

    @Column(name = "checked_in_at")
    @Builder.Default
    private LocalDateTime checkedInAt = LocalDateTime.now();

    @Column(name = "checked_out_at")
    private LocalDateTime checkedOutAt;

    @Column(name = "units_consumed", precision = 3, scale = 1)
    @Builder.Default
    private BigDecimal unitsConsumed = BigDecimal.ZERO;

    @Column(name = "is_unitaire")
    @Builder.Default
    private boolean unitaire = false;

    @Column(name = "unit_price_chf", precision = 8, scale = 2)
    private BigDecimal unitPriceChf;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
