package ch.amicalewifi.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrintCostCalculatorTest {

    private static final int COLOR_FACTOR = 2;
    private static final int BW_FACTOR    = 1;

    @Test
    void sheets_simplex_oneSheetPerPage() {
        assertEquals(7, PrintCostCalculator.sheets(7, false));
    }

    @Test
    void sheets_duplex_evenPagesHalve() {
        assertEquals(3, PrintCostCalculator.sheets(6, true));
    }

    @Test
    void sheets_duplex_oddPagesRoundUp() {
        assertEquals(4, PrintCostCalculator.sheets(7, true));
        assertEquals(1, PrintCostCalculator.sheets(1, true));
    }

    @Test
    void sheets_zeroOrNegative_returnsZero() {
        assertEquals(0, PrintCostCalculator.sheets(0, true));
        assertEquals(0, PrintCostCalculator.sheets(-1, false));
    }

    @Test
    void cost_bwSimplex_oneCreditPerPage() {
        assertEquals(7, PrintCostCalculator.cost(7, 1, false, false, COLOR_FACTOR, BW_FACTOR));
    }

    @Test
    void cost_colorDuplex_oddPages() {
        // 7 pages duplex → 4 sheets × 1 copy × 2 (color) = 8 credits
        assertEquals(8, PrintCostCalculator.cost(7, 1, true, true, COLOR_FACTOR, BW_FACTOR));
    }

    @Test
    void cost_multipleCopies() {
        // 4 pages duplex × 3 copies × N&B = 2 sheets × 3 × 1 = 6
        assertEquals(6, PrintCostCalculator.cost(4, 3, false, true, COLOR_FACTOR, BW_FACTOR));
    }

    @Test
    void cost_singlePageDuplex_oneSheet() {
        // 1 page duplex still uses 1 sheet (the back stays blank)
        assertEquals(1, PrintCostCalculator.cost(1, 1, false, true, COLOR_FACTOR, BW_FACTOR));
        assertEquals(2, PrintCostCalculator.cost(1, 1, true,  true, COLOR_FACTOR, BW_FACTOR));
    }

    @Test
    void credits_directInput_skipsSheetMath() {
        // IPP path passes pre-computed sheet counts (claudine-proxy collapses
        // impressions→sheets via Kyocera before calling /complete).
        assertEquals(10, PrintCostCalculator.credits(5, 1, true, COLOR_FACTOR, BW_FACTOR));
    }

    @Test
    void cost_zeroPages_returnsZero() {
        assertEquals(0, PrintCostCalculator.cost(0, 5, true, true, COLOR_FACTOR, BW_FACTOR));
    }

    @Test
    void cost_zeroCopies_returnsZero() {
        assertEquals(0, PrintCostCalculator.cost(5, 0, true, true, COLOR_FACTOR, BW_FACTOR));
    }
}
