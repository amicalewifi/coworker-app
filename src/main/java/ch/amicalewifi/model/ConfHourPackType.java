package ch.amicalewifi.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;

public enum ConfHourPackType {

    //   hours                        label         priceChf                 purchasable
    H1  (new BigDecimal("1.0"),  "1 heure",   new BigDecimal("19.00"),  true),
    H2  (new BigDecimal("2.0"),  "2 heures",  new BigDecimal("36.00"),  true),
    H3  (new BigDecimal("3.0"),  "3 heures",  new BigDecimal("54.00"),  true),
    H4  (new BigDecimal("4.0"),  "4 heures",  new BigDecimal("71.00"),  true),
    H5  (new BigDecimal("5.0"),  "5 heures",  new BigDecimal("88.00"),  true),
    H6  (new BigDecimal("6.0"),  "6 heures",  new BigDecimal("105.00"), true),
    H7  (new BigDecimal("7.0"),  "7 heures",  new BigDecimal("122.00"), true),
    H8  (new BigDecimal("8.0"),  "8 heures",  new BigDecimal("138.00"), true),
    // H10 retiré du catalogue mais conservé dans l'enum pour ne pas casser le
    // chargement des conf_credit_transactions historiques (pack_type='H10').
    H10 (new BigDecimal("10.0"), "10 heures", new BigDecimal("190.00"), false),
    H12 (new BigDecimal("12.0"), "12 heures", new BigDecimal("199.00"), true),
    H24 (new BigDecimal("24.0"), "24 heures", new BigDecimal("349.00"), true);

    private final BigDecimal hours;
    private final String     label;
    private final BigDecimal priceChf;
    private final boolean    purchasable;

    ConfHourPackType(BigDecimal hours, String label, BigDecimal priceChf, boolean purchasable) {
        this.hours       = hours;
        this.label       = label;
        this.priceChf    = priceChf;
        this.purchasable = purchasable;
    }

    public BigDecimal getHours()       { return hours; }
    public String     getLabel()       { return label; }
    public BigDecimal getPriceChf()    { return priceChf; }
    public boolean    isPurchasable()  { return purchasable; }

    /** Tarif effectif Fr./heure, arrondi au centime. */
    public BigDecimal getPricePerHour() {
        return priceChf.divide(hours, 2, RoundingMode.HALF_UP);
    }

    /** Liste ordonnée des packs proposés à l'achat sur /mobile/rooms. */
    public static List<ConfHourPackType> purchasable() {
        return Arrays.stream(values()).filter(ConfHourPackType::isPurchasable).toList();
    }
}
