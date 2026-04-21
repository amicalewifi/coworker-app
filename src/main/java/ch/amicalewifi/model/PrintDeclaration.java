package ch.amicalewifi.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "print_declarations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PrintDeclaration {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "pages_bw", nullable = false)
    @Builder.Default private int pagesBw = 0;

    @Column(name = "pages_color", nullable = false)
    @Builder.Default private int pagesColor = 0;

    @Column(name = "credits_used", nullable = false)
    private int creditsUsed;

    private String notes;

    @Column(name = "declared_at")
    @Builder.Default private LocalDateTime declaredAt = LocalDateTime.now();
}
