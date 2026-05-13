package ch.amicalewifi.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "wifi_daily_usage",
       uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "usage_date"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WifiDailyUsage {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(nullable = false)
    @Builder.Default
    private Integer seconds = 0;

    @Column(name = "units_charged", nullable = false, precision = 2, scale = 1)
    @Builder.Default
    private BigDecimal unitsCharged = BigDecimal.ZERO;

    @Column(name = "last_poll_at", nullable = false)
    @Builder.Default
    private LocalDateTime lastPollAt = LocalDateTime.now();
}
