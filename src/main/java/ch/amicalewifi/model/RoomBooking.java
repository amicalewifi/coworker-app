package ch.amicalewifi.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "room_bookings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RoomBooking {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(name = "organizer_name")
    private String organizerName;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Builder.Default
    private int participants = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "booking_status")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Builder.Default
    private BookingStatus status = BookingStatus.CONFIRMED;

    private String title;
    private String notes;

    @Column(name = "billed_from_credits")
    @Builder.Default
    private boolean billedFromCredits = true;

    @Column(name = "billed_amount_chf", precision = 8, scale = 2)
    private BigDecimal billedAmountChf;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    public double getDurationHours() {
        return (startTime != null && endTime != null)
                ? Duration.between(startTime, endTime).toMinutes() / 60.0
                : 0;
    }
}
