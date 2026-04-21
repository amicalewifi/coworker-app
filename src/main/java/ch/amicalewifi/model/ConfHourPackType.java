package ch.amicalewifi.model;

import java.math.BigDecimal;

public enum ConfHourPackType {

    H1 (new BigDecimal("1.0"),  "1 heure",    new BigDecimal("19.00")),
    H2 (new BigDecimal("2.0"),  "2 heures",   new BigDecimal("38.00")),
    H3 (new BigDecimal("3.0"),  "3 heures",   new BigDecimal("57.00")),
    H4 (new BigDecimal("4.0"),  "4 heures",   new BigDecimal("76.00")),
    H5 (new BigDecimal("5.0"),  "5 heures",   new BigDecimal("95.00")),
    H6 (new BigDecimal("6.0"),  "6 heures",   new BigDecimal("114.00")),
    H8 (new BigDecimal("8.0"),  "8 heures",   new BigDecimal("152.00")),
    H10(new BigDecimal("10.0"), "10 heures",  new BigDecimal("190.00"));

    private final BigDecimal hours;
    private final String     label;
    private final BigDecimal priceChf;

    ConfHourPackType(BigDecimal hours, String label, BigDecimal priceChf) {
        this.hours    = hours;
        this.label    = label;
        this.priceChf = priceChf;
    }

    public BigDecimal getHours()    { return hours; }
    public String     getLabel()    { return label; }
    public BigDecimal getPriceChf() { return priceChf; }
}
