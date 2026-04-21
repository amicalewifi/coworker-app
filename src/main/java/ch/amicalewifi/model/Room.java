package ch.amicalewifi.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "rooms")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Room {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", columnDefinition = "room_type")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private RoomType roomType;

    private int capacity;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] equipment;

    @Column(name = "hourly_rate_chf", precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal hourlyRateChf = new BigDecimal("19.00");

    @Column(name = "qr_code_token", unique = true)
    private String qrCodeToken;

    private String description;

    @Column(name = "is_active")
    @Builder.Default
    private boolean active = true;

}
