package ch.amicalewifi.model;

import java.math.BigDecimal;

public enum PresenceType {

    HALF_AM  ("Demi-journée matin", "½ Matin", "07:00–12:30", new BigDecimal("0.5")),
    HALF_PM  ("Demi-journée AP-M",  "½ AP-M",  "12:30–19:00", new BigDecimal("0.5")),
    FULL_DAY ("Journée complète",   "Journée", "07:00–19:00", new BigDecimal("1.0")),
    TRIAL    ("Journée d'essai",    "Essai",   "07:00–19:00", BigDecimal.ZERO),
    UNIT_HALF("Unitaire ½ jour",    "Unit.½j", "07:00–12:30", new BigDecimal("0.5")),
    UNIT_FULL("Unitaire journée",   "Unit.j.", "07:00–19:00", new BigDecimal("1.0"));

    private final String label, shortLabel, hours;
    private final BigDecimal units;

    PresenceType(String label, String shortLabel, String hours, BigDecimal units) {
        this.label      = label;
        this.shortLabel = shortLabel;
        this.hours      = hours;
        this.units      = units;
    }

    public String     getLabel()      { return label; }
    public String     getShortLabel() { return shortLabel; }
    public String     getHours()      { return hours; }
    public BigDecimal getUnits()      { return units; }
    public boolean    isUnitaire()    { return this == UNIT_HALF || this == UNIT_FULL || this == TRIAL; }

    public PresenceType toUnitaire() {
        return (this == FULL_DAY || this == UNIT_FULL) ? UNIT_FULL : UNIT_HALF;
    }
}
