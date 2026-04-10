package ch.amicalewifi.model;

import java.math.BigDecimal;

public enum MembershipType {

    PACK_MATIN  ("Pack matin — Fr. 15.-",      new BigDecimal("0.5"),  BigDecimal.ZERO,        new BigDecimal("15.00")),
    PACK_APMIDI ("Pack après-midi — Fr. 15.-", new BigDecimal("0.5"),  BigDecimal.ZERO,        new BigDecimal("15.00")),
    PACK_1J     ("Pack 1 jour — Fr. 29.-",     new BigDecimal("1.0"),  new BigDecimal("0.5"),  new BigDecimal("29.00")),
    PACK_5J     ("Pack 5 jours — Fr. 109.-",   new BigDecimal("5.0"),  new BigDecimal("2.0"),  new BigDecimal("109.00")),
    PACK_10J    ("Pack 10 jours — Fr. 199.-",  new BigDecimal("10.0"), new BigDecimal("4.0"),  new BigDecimal("199.00")),
    PACK_15J    ("Pack 15 jours — Fr. 279.-",  new BigDecimal("15.0"), new BigDecimal("6.0"),  new BigDecimal("279.00")),
    PERMANENT   ("Permanent — Fr. 329.-/mois", null,                   new BigDecimal("10.0"), new BigDecimal("329.00")),
    JOURNEE_ESSAI("Journée d'essai",           BigDecimal.ZERO,        BigDecimal.ZERO,        BigDecimal.ZERO),
    UNITAIRE    ("Unitaire (à la journée)",    null,                   BigDecimal.ZERO,        BigDecimal.ZERO),
    DOMICILIATION("Domiciliation Fr. 79.-/mois", null,                BigDecimal.ZERO,        new BigDecimal("79.00"));

    private final String label;
    private final BigDecimal packUnits;
    private final BigDecimal confCredits;
    private final BigDecimal priceChf;

    MembershipType(String label, BigDecimal packUnits, BigDecimal confCredits, BigDecimal priceChf) {
        this.label       = label;
        this.packUnits   = packUnits;
        this.confCredits = confCredits;
        this.priceChf    = priceChf;
    }

    public String     getLabel()       { return label; }
    public BigDecimal getPackUnits()   { return packUnits; }
    public BigDecimal getConfCredits() { return confCredits; }
    public BigDecimal getPriceChf()    { return priceChf; }
    public boolean    hasPack()        { return packUnits != null && packUnits.compareTo(BigDecimal.ZERO) > 0; }
}
