package app.service;

import app.closure.Hours;

import java.time.LocalTime;
import java.util.OptionalLong;

// On a day a dish sold out, the quantity recorded is what the stall had, not what customers wanted
// (issue #50). Training or averaging on it teaches the forecast to under-prepare, and the stall
// sells out again. As a simple start, a sold-out day counts as what the dish would have sold at
// the same rate until closing: quantity × trading minutes ÷ minutes it was on sale. Sales aren't
// spread evenly over the day (most come at lunch), so the uplift is capped at MAX_UPLIFT: a dish
// that ran out at 1 pm in a 10 am to 3 pm stall had most of its customers already.
//
// In the prep simulator (ml/README.md, 24 stalls, 182 days) this rule, with a 1.5 cap, beats the
// simulator's generic customer-curve rule: forecast error 24.3% vs 25.7% (32.0% without either).
// Used only when app.forecast.count-sold-out-demand is on (see ForecastService).
final class SoldOutDemand {

    static final double MAX_UPLIFT = 1.5;

    private SoldOutDemand() {
    }

    // The demand a day stands for. Days that didn't sell out, or whose sold-out time is outside
    // the trading hours (so how early it was can't be told), count as recorded.
    static double estimate(int quantity, LocalTime soldOutAt, Hours hours) {
        if (soldOutAt == null) return quantity;
        OptionalLong onSale = hours.minutesUntil(soldOutAt);
        if (onSale.isEmpty()) return quantity;
        if (onSale.getAsLong() == 0) return quantity * MAX_UPLIFT;
        return quantity * Math.min((double) hours.minutesOpen() / onSale.getAsLong(), MAX_UPLIFT);
    }
}
