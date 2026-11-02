package app.service;

import app.closure.Hours;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SoldOutDemandTest {

    static final Hours LUNCH = new Hours(LocalTime.of(10, 0), LocalTime.of(15, 0));
    static final Hours SUPPER = new Hours(LocalTime.of(18, 0), LocalTime.of(2, 0));

    @Test
    void aDayThatDidNotSellOutCountsAsRecorded() {
        assertEquals(40, SoldOutDemand.estimate(40, null, LUNCH));
    }

    @Test
    void aLateSellOutCountsForTheTimeLeft() {
        // On sale 4 of 5 hours: 40 × 5/4.
        assertEquals(50, SoldOutDemand.estimate(40, LocalTime.of(14, 0), LUNCH));
        assertEquals(40, SoldOutDemand.estimate(40, LocalTime.of(15, 0), LUNCH));
    }

    @Test
    void anEarlySellOutIsCapped() {
        assertEquals(60, SoldOutDemand.estimate(40, LocalTime.of(11, 0), LUNCH));
        assertEquals(60, SoldOutDemand.estimate(40, LocalTime.of(10, 0), LUNCH));
    }

    @Test
    void aSellOutOutsideTheHoursCountsAsRecorded() {
        assertEquals(40, SoldOutDemand.estimate(40, LocalTime.of(1, 0), LUNCH));
        assertEquals(40, SoldOutDemand.estimate(40, LocalTime.of(16, 0), LUNCH));
    }

    @Test
    void aSupperStallsHoursRunPastMidnight() {
        // On sale 18:00 to 01:00, 7 of 8 hours.
        assertEquals(40 * 8 / 7.0, SoldOutDemand.estimate(40, LocalTime.of(1, 0), SUPPER), 1e-9);
        assertEquals(40, SoldOutDemand.estimate(40, LocalTime.of(3, 0), SUPPER));
    }
}
