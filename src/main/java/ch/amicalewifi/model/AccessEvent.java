package ch.amicalewifi.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "access_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccessEvent {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(name = "badge_uid")
    private String badgeUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", columnDefinition = "access_event_type")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private AccessEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "presence_type", columnDefinition = "presence_type")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private PresenceType presenceType;

    @Column(name = "units_consumed", precision = 3, scale = 1)
    private BigDecimal unitsConsumed;

    @Column(name = "denied_reason")
    private String deniedReason;

    @Column(name = "terminal_id")
    @Builder.Default
    private String terminalId = "borne";

    @Column(name = "occurred_at")
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}
