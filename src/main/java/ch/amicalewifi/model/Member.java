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
@Table(name = "members")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Member {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "first_name", nullable = false) private String firstName;
    @Column(name = "last_name",  nullable = false) private String lastName;
    @Column(nullable = false, unique = true)        private String email;
    private String phone;
    private String company;
    @Column(name = "tva_number") private String tvaNumber;

    @Column(name = "badge_uid", unique = true)  private String  badgeUid;
    @Column(name = "badge_active") @Builder.Default private boolean badgeActive = true;
    @Column(name = "badge_expires")             private LocalDate badgeExpires;

    // QR token unique (toujours disponible, indépendant du badge NFC)
    @Column(name = "qr_token", unique = true)
    @Builder.Default private UUID qrToken = UUID.randomUUID();

    // Adresse postale
    private String address;
    private String city;
    @Column(name = "postal_code") private String postalCode;
    @Column(nullable = false) @Builder.Default private String country = "Suisse";

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "membership_type")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Builder.Default private MembershipType membership = MembershipType.PACK_5J;

    @Column(name = "pack_units_total",  precision = 5, scale = 1) private BigDecimal packUnitsTotal;
    @Column(name = "pack_units_used",   precision = 5, scale = 1)
    @Builder.Default private BigDecimal packUnitsUsed = BigDecimal.ZERO;
    @Column(name = "pack_expires")      private LocalDate packExpires;

    @Column(name = "conf_credits_total_h", precision = 5, scale = 2)
    @Builder.Default private BigDecimal confCreditsTotalH = BigDecimal.ZERO;
    @Column(name = "conf_credits_used_h",  precision = 5, scale = 2)
    @Builder.Default private BigDecimal confCreditsUsedH  = BigDecimal.ZERO;

    @Column(name = "has_domiciliation") @Builder.Default private boolean hasDomiciliation = false;
    @Column(name = "logo_url")          private String  logoUrl;
    @Column(name = "has_mailbox")       @Builder.Default private boolean hasMailbox = false;

    @Column(name = "print_quota")  @Builder.Default private int printQuota = 50;
    @Column(name = "print_used")   @Builder.Default private int printUsed  = 0;

    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "bytea")
    private byte[] photo;

    @Column(name = "photo_type")
    private String photoType;

    @ManyToOne(fetch = FetchType.EAGER) @JoinColumn(name = "user_id") private User user;

    private String website;
    @Column(name = "linkedin_url") private String linkedinUrl;

    @Column(name = "is_active") @Builder.Default private boolean active = true;
    private String notes;

    @Column(name = "created_at") @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "updated_at") @Builder.Default private LocalDateTime updatedAt = LocalDateTime.now();

    // ── Propriétés calculées ────────────────────────────────

    public String getDisplayName() {
        return firstName + " " + lastName;
    }

    public String getInitials() {
        String f = (firstName != null && !firstName.isEmpty()) ? String.valueOf(firstName.charAt(0)) : "?";
        String l = (lastName  != null && !lastName.isEmpty())  ? String.valueOf(lastName.charAt(0))  : "?";
        return f + l;
    }

    public BigDecimal getPackUnitsRemaining() {
        return (packUnitsTotal != null) ? packUnitsTotal.subtract(packUnitsUsed) : null;
    }

    public Integer getHalfDaysRemaining() {
        BigDecimal r = getPackUnitsRemaining();
        return (r != null) ? r.multiply(BigDecimal.valueOf(2)).intValue() : null;
    }

    public BigDecimal getConfCreditsRemaining() {
        BigDecimal total = confCreditsTotalH != null ? confCreditsTotalH : BigDecimal.ZERO;
        BigDecimal used  = confCreditsUsedH  != null ? confCreditsUsedH  : BigDecimal.ZERO;
        return total.subtract(used);
    }

    public boolean isPermanent() {
        return membership == MembershipType.PERMANENT;
    }

    /**
     * Retourne l'alerte du pack: "ok" | "low" | "expiring" | "empty"
     */
    public String getPackAlert() {
        if (isPermanent()) {
            if (packExpires == null) return "ok";
            if (!packExpires.isAfter(LocalDate.now())) return "empty";
            if (packExpires.isBefore(LocalDate.now().plusDays(7))) return "expiring";
            return "ok";
        }
        BigDecimal r = getPackUnitsRemaining();
        if (r == null) return "ok";
        if (r.compareTo(BigDecimal.ZERO) <= 0) return "empty";
        if (packExpires != null && packExpires.isBefore(LocalDate.now().plusDays(7))) return "expiring";
        if (r.compareTo(BigDecimal.ONE) <= 0) return "low";
        return "ok";
    }
}
