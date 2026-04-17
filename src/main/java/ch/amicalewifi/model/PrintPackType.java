package ch.amicalewifi.model;

import java.math.BigDecimal;

public enum PrintPackType {

    PACK_50  (  50, "Pack 50 crédits",   new BigDecimal("5.00")),
    PACK_100 ( 100, "Pack 100 crédits",  new BigDecimal("10.00")),
    PACK_200 ( 200, "Pack 200 crédits",  new BigDecimal("20.00")),
    PACK_500 ( 500, "Pack 500 crédits",  new BigDecimal("50.00")),
    PACK_1000(1000, "Pack 1000 crédits", new BigDecimal("100.00"));

    private final int        credits;
    private final String     label;
    private final BigDecimal priceChf;

    PrintPackType(int credits, String label, BigDecimal priceChf) {
        this.credits  = credits;
        this.label    = label;
        this.priceChf = priceChf;
    }

    public int        getCredits()  { return credits; }
    public String     getLabel()    { return label; }
    public BigDecimal getPriceChf() { return priceChf; }
}
