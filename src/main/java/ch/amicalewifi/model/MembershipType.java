package ch.amicalewifi.model;

import java.math.BigDecimal;
import java.util.List;

public enum MembershipType {

    //          label                          shortName          description                          badge   priceSuffix purchasable packUnits             confCredits             priceChf
    PACK_DEMIJ  ("Pack demi-journée — Fr. 19.-","Pack demi-journée","4h",                              "½ j",  null,       true,       new BigDecimal("0.5"),  BigDecimal.ZERO,        new BigDecimal("19.00")),
    PACK_1J     ("Pack 1 jour — Fr. 29.-",     "Pack 1 jour",     "1 journée ou 2 demi-journées",      "1 j",  null,       true,       new BigDecimal("1.0"),  new BigDecimal("0.5"),  new BigDecimal("29.00")),
    PACK_5J     ("Pack 5 jours — Fr. 109.-",   "Pack 5 jours",    "5 journées ou 10 demi-journées",    "5 j",  null,       true,       new BigDecimal("5.0"),  new BigDecimal("2.0"),  new BigDecimal("109.00")),
    PACK_10J    ("Pack 10 jours — Fr. 199.-",  "Pack 10 jours",   "10 journées ou 20 demi-journées",   "10 j", null,       true,       new BigDecimal("10.0"), new BigDecimal("4.0"),  new BigDecimal("199.00")),
    PACK_15J    ("Pack 15 jours — Fr. 279.-",  "Pack 15 jours",   "15 journées ou 30 demi-journées",   "15 j", null,       true,       new BigDecimal("15.0"), new BigDecimal("6.0"),  new BigDecimal("279.00")),
    PACK_30J    ("Pack 30 jours — Fr. 509.-",  "Pack 30 jours",   "30 journées ou 60 demi-journées",   "30 j", null,       true,       new BigDecimal("30.0"), new BigDecimal("12.0"), new BigDecimal("509.00")),
    PACK_60J    ("Pack 60 jours — Fr. 959.-",  "Pack 60 jours",   "60 journées ou 120 demi-journées",  "60 j", null,       true,       new BigDecimal("60.0"), new BigDecimal("24.0"), new BigDecimal("959.00")),
    PERMANENT   ("Permanent — Fr. 329.-/mois", "Permanent",       "Accès illimité 24h/7j",             "∞",    "/mois",    false,      null,                   new BigDecimal("10.0"), new BigDecimal("329.00")),
    JOURNEE_ESSAI("Journée d'essai",           "Journée d'essai", null,                                null,   null,       false,      BigDecimal.ZERO,        BigDecimal.ZERO,        BigDecimal.ZERO),
    UNITAIRE    ("Unitaire (à la journée)",    "Unitaire",        null,                                null,   null,       false,      null,                   BigDecimal.ZERO,        BigDecimal.ZERO),
    DOMICILIATION("Domiciliation Fr. 79.-/mois", "Domiciliation", null,                                null,   "/mois",    false,      null,                   BigDecimal.ZERO,        new BigDecimal("79.00"));

    private final String     label;
    private final String     shortName;
    private final String     description;
    private final String     badge;
    private final String     priceSuffix;
    private final boolean    purchasable;
    private final BigDecimal packUnits;
    private final BigDecimal confCredits;
    private final BigDecimal priceChf;

    MembershipType(String label, String shortName, String description, String badge,
                   String priceSuffix, boolean purchasable,
                   BigDecimal packUnits, BigDecimal confCredits, BigDecimal priceChf) {
        this.label       = label;
        this.shortName   = shortName;
        this.description = description;
        this.badge       = badge;
        this.priceSuffix = priceSuffix;
        this.purchasable = purchasable;
        this.packUnits   = packUnits;
        this.confCredits = confCredits;
        this.priceChf    = priceChf;
    }

    public String     getLabel()       { return label; }
    public String     getShortName()   { return shortName; }
    public String     getDescription() { return description; }
    public String     getBadge()       { return badge; }
    public String     getPriceSuffix() { return priceSuffix; }
    public boolean    isPurchasable()  { return purchasable; }
    public BigDecimal getPackUnits()   { return packUnits; }
    public BigDecimal getConfCredits() { return confCredits; }
    public BigDecimal getPriceChf()    { return priceChf; }
    public boolean    hasPack()        { return packUnits != null && packUnits.compareTo(BigDecimal.ZERO) > 0; }

    /** Liste ordonnée des formules proposées à l'achat sur /mobile/renew. */
    public static List<MembershipType> purchasable() {
        return java.util.Arrays.stream(values()).filter(MembershipType::isPurchasable).toList();
    }
}
