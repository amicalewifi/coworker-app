package ch.amicalewifi.model;

import java.math.BigDecimal;

public enum PresenceType {

    HALF_AM      ("Demi-journée matin",      "½ Matin",    "07:00–12:30", new BigDecimal("0.5")),
    HALF_PM      ("Demi-journée AP-M",       "½ AP-M",     "12:30–19:00", new BigDecimal("0.5")),
    FULL_DAY     ("Journée complète",        "Journée",    "07:00–19:00", new BigDecimal("1.0")),
    TRIAL        ("Journée d'essai",         "Essai",      "07:00–19:00", BigDecimal.ZERO),
    UNIT_HALF    ("Unitaire ½ jour",         "Unit.½j",    "07:00–12:30", new BigDecimal("0.5")),
    UNIT_HALF_AM ("Unitaire ½ matin",        "Unit.½AM",   "07:00–12:30", new BigDecimal("0.5")),
    UNIT_HALF_PM ("Unitaire ½ AP-M",         "Unit.½PM",   "12:30–19:00", new BigDecimal("0.5")),
    UNIT_FULL    ("Unitaire journée",        "Unit.j.",    "07:00–19:00", new BigDecimal("1.0"));

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
    public boolean    isUnitaire()    { return this == UNIT_HALF || this == UNIT_HALF_AM || this == UNIT_HALF_PM || this == UNIT_FULL || this == TRIAL; }

    /** Durée nominale de la plage en minutes (déduite de la propriété {@link #hours}). */
    public int getDurationMinutes() {
        String[] parts = hours.split("[–-]");
        return toMinutes(parts[1].trim()) - toMinutes(parts[0].trim());
    }

    private static int toMinutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    public PresenceType toUnitaire() {
        return switch (this) {
            case FULL_DAY, UNIT_FULL -> UNIT_FULL;
            case HALF_AM,  UNIT_HALF_AM -> UNIT_HALF_AM;
            case HALF_PM,  UNIT_HALF_PM -> UNIT_HALF_PM;
            default -> UNIT_HALF;
        };
    }
}
