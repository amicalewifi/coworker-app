package ch.amicalewifi.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "wifi_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WifiAuditEvent {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(length = 17)
    private String mac;

    @Column(nullable = false, length = 32)
    private String event;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}
