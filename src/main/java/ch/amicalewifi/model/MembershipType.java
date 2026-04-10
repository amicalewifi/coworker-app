package ch.amicalewifi.model;

import java.math.BigDecimal;

public enum MembershipType {

    PACK_MATIN  ("Pack matin — Fr. 15.-",      new BigDecimal("0.5"),  BigDecimal.ZERO),
    PACK_APMIDI ("Pack après-midi — Fr. 15.-", new BigDecimal("0.5"),  BigDecimal.ZERO),
    PACK_1J     ("Pack 1 jour — Fr. 29.-",     new BigDecimal("1.0"),  new BigDecimal("0.5")),
    PACK_5J     ("Pack 5 jours — Fr. 109.-",   new BigDecimal("5.0"),  new BigDecimal("2.0")),
    PACK_10J    ("Pack 10 jours — Fr. 199.-",  new BigDecimal("10.0"), new BigDecimal("4.0")),
    PACK_15J    ("Pack 15 jours — Fr. 279.-",  new BigDecimal("15.0"), new BigDecimal("6.0")),
    PERMANENT   ("Permanent — Fr. 329.-/mois", null,                   new BigDecimal("10.0")),
    JOURNEE_ESSAI("Journée d'essai",           BigDecimal.ZERO,        BigDecimal.ZERO),
    UNITAIRE    ("Unitaire (à la journée)",    null,                   BigDecimal.ZERO),
    DOMICILIATION("Domiciliation Fr. 79.-/mois", null,                BigDecimal.ZERO);

    private final String label;
    private final BigDecimal packUnits;
    private final BigDecimal confCredits;

    MembershipType(String label, BigDecimal packUnits, BigDecimal confCredits) {
        this.label       = label;
        this.packUnits   = packUnits;
        this.confCredits = confCredits;
    }

    public String     getLabel()       { return label; }
    public BigDecimal getPackUnits()   { return packUnits; }
    public BigDecimal getConfCredits() { return confCredits; }
    public boolean    hasPack()        { return packUnits != null && packUnits.compareTo(BigDecimal.ZERO) > 0; }
}
