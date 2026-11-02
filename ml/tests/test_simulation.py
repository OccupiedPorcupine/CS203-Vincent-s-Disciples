from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from ml.src.simulation import assumptions as A
from ml.src.simulation.backtest import (
    BEST_POSSIBLE,
    OWNER_HABIT,
    AppBaseline,
    Forecast,
    History,
    POLICIES,
    SoldOutAwareBaseline,
    Target,
    critical_ratio,
    ingredient_totals,
    run_backtest,
    summarise,
)
from ml.src.simulation.simulator import load_holidays, simulate

START, END = date(2025, 1, 1), date(2025, 4, 30)


@pytest.fixture(scope="module")
def sales():
    return simulate(stall_count=2, start=START, end=END, seed=3)


def test_simulation_is_repeatable(sales):
    pd.testing.assert_frame_equal(sales, simulate(stall_count=2, start=START, end=END, seed=3))


def test_a_stall_sells_what_it_prepared_or_what_was_wanted(sales):
    open_days = sales[sales["open"]]
    assert (open_days["sold"] == np.minimum(open_days["demand"], open_days["prepared"])).all()
    assert (open_days["leftover"] == open_days["prepared"] - open_days["sold"]).all()
    assert ((open_days["sold_out_at"] != "") == (open_days["demand"] > open_days["prepared"])).all()
    assert open_days["sold_out_at"].str.match(r"^$|^\d\d:\d\d$").all()

    closed = sales[~sales["open"]]
    assert (closed["sold"] == 0).all()
    assert (closed["closure_reason"] != "").all()


def test_closures_use_the_real_calendar(sales):
    holidays = load_holidays()
    assert holidays[date(2025, 1, 29)] == "Chinese New Year"
    assert holidays[date(2025, 8, 9)] == "National Day"

    for _, stall in sales.groupby("stall_id"):
        reasons = stall.drop_duplicates("date").set_index("date")["closure_reason"]
        assert reasons[pd.Timestamp("2025-01-29")] != ""
        assert reasons[pd.Timestamp("2025-01-30")] != ""
        assert "Rest day" in set(reasons)


def history_from(sold, start=date(2025, 3, 3), sold_out_minute=None):
    """Consecutive days from a Monday."""
    dates = np.array([np.datetime64(start + timedelta(days=offset)) for offset in range(len(sold))])
    minutes = np.full(len(sold), np.nan) if sold_out_minute is None else np.array(sold_out_minute, dtype=float)
    return History(dates, np.array(sold, dtype=float), minutes)


def target(day):
    return Target(np.datetime64(day), "", 0.0)


def test_app_baseline_is_the_mean_of_the_last_four_same_weekdays():
    sold = [10 + day for day in range(42)]  # six weeks from Monday 3 March
    forecast = AppBaseline().forecast(history_from(sold), target(date(2025, 4, 14)))  # a Monday
    assert forecast.point == pytest.approx(np.mean([sold[35], sold[28], sold[21], sold[14]]))


def test_app_baseline_falls_back_to_recent_days_and_a_rough_range():
    # Mon-Wed only, so there is no earlier Saturday and too few errors for a measured range.
    forecast = AppBaseline().forecast(history_from([20, 30, 40]), target(date(2025, 3, 8)))
    assert forecast.point == pytest.approx(30)
    assert forecast.quantile(0.9) == pytest.approx(30 * 1.25)
    assert forecast.quantile(0.1) == pytest.approx(30 * 0.75)


def test_app_baseline_range_comes_from_its_last_twelve_errors():
    rng = np.random.default_rng(1)
    sold = list(rng.integers(30, 70, 49).astype(float))
    history = history_from(sold)
    forecast = AppBaseline().forecast(history, target(date(2025, 4, 21)))

    # BaselineForecaster.range, written out directly.
    errors = []
    for index in range(len(sold) - 12, len(sold)):
        same = [sold[j] for j in range(index) if j % 7 == index % 7][-4:]
        errors.append(sold[index] - np.mean(same))
    assert forecast.quantile(0.9) == pytest.approx(forecast.point + np.quantile(sorted(errors), 0.9))


def test_sell_out_times_raise_the_estimate():
    sold = [50.0] * 28
    lunch_sell_outs = [np.nan if day % 7 else 12 * 60 for day in range(28)]  # every Monday at noon
    plain = AppBaseline().forecast(history_from(sold), target(date(2025, 3, 31)))
    adjusted = SoldOutAwareBaseline().forecast(history_from(sold, sold_out_minute=lunch_sell_outs),
                                                target(date(2025, 3, 31)))
    assert plain.point == pytest.approx(50)
    assert 50 < adjusted.point <= 50 / A.MIN_ARRIVED_SHARE


def test_margin_rule_prepares_to_the_critical_ratio():
    assert critical_ratio(4.50, 1.60) == pytest.approx(2.90 / 4.50)
    forecast = Forecast(50, lambda p: 40 + 20 * p)
    assert POLICIES["Margin rule"](forecast, 4.50, 1.60) == 53  # ceil(40 + 20 * 0.644)
    assert POLICIES["Top of likely range (app default)"](forecast, 4.50, 1.60) == 58


class SpyForecaster(AppBaseline):
    name = "Spy"

    def __init__(self):
        self.calls = 0

    def forecast(self, history, target):
        self.calls += 1
        assert len(history.dates) == 0 or history.dates.max() < target.date
        assert not hasattr(history, "demand")
        return super().forecast(history, target)


def test_backtest_never_shows_a_forecaster_the_future(sales):
    spy = SpyForecaster()
    result = run_backtest(sales, [spy], eval_days=14)
    assert spy.calls == len(result[result["forecaster"] == "Spy"])
    assert spy.calls > 0


def test_backtest_scores_against_true_demand(sales):
    result = run_backtest(sales, [AppBaseline()], eval_days=14)
    assert (result["sold"] == np.minimum(result["prepared"], result["demand"])).all()
    assert {OWNER_HABIT, BEST_POSSIBLE} <= set(result["policy"])

    summary = summarise(result).set_index("policy")
    assert (summary["pct_of_best_profit"] <= 100).all()
    owner = result[result["policy"] == OWNER_HABIT]
    expected_profit = (owner["sold"] * owner["price"] - owner["prepared"] * owner["unit_cost"]).sum()
    assert summary.loc[OWNER_HABIT, "profit"] == pytest.approx(expected_profit)

    ingredients = ingredient_totals(result)
    assert (ingredients["prepared"] - ingredients["leftover"]
            == ingredients["needed"] - ingredients["short"]).all()
