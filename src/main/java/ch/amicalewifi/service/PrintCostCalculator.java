package ch.amicalewifi.service;

/**
 * Single source of truth for sheet/credit math. Avoids drift between the
 * mobile upload flow (computes sheets from raw PDF page count) and the IPP
 * flow (claudine-proxy already collapses pages→sheets via Kyocera attributes,
 * Spring stores the billing unit directly in PrinterJob.pages).
 *
 * Conventions:
 *   - 1 sheet = 1 physical piece of paper
 *   - duplex : 2 logical pages share 1 sheet (odd page count → trailing blank)
 *   - color  : multiplies credit cost (factor injected — configurable via
 *              amicale.business.print-color-factor / print-bw-factor)
 */
public final class PrintCostCalculator {

    private PrintCostCalculator() {}

    /** Physical sheets needed to print {@code pages} given duplex setting. */
    public static int sheets(int pages, boolean duplex) {
        if (pages <= 0) return 0;
        return duplex ? (pages + 1) / 2 : pages;
    }

    /** Credit cost from already-resolved sheet count. */
    public static int credits(int sheets, int copies, boolean color,
                              int colorFactor, int bwFactor) {
        if (sheets <= 0 || copies <= 0) return 0;
        return sheets * copies * (color ? colorFactor : bwFactor);
    }

    /** Convenience: full cost from raw page count, applying duplex collapse. */
    public static int cost(int pages, int copies, boolean color, boolean duplex,
                           int colorFactor, int bwFactor) {
        return credits(sheets(pages, duplex), copies, color, colorFactor, bwFactor);
    }
}
