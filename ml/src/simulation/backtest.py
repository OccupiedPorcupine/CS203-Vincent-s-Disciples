"""
Replay simulated stalls day by day and score how much each prep policy wastes and sells out
(issue #85).

For every open day in the evaluation window, a forecaster sees the stall's recorded sales
before that day (never the true demand), a prep policy turns its forecast into portions, and
the day is scored against the true demand. The loop is closed: what a policy prepares decides
what sells, and those sales are what the forecaster sees the next day, as they would be in the
app. A policy that runs out often therefore teaches its forecaster to expect less.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Callable, Protocol

import numpy as np
import pandas as pd

from ml.src.simulation import assumptions as A
from ml.src.simulation.simulator import arrival_curve, recipes, sold_out_minute


@dataclass(frozen=True)
class History:
    """One dish's recorded open days before the forecast date, oldest first."""
    dates: np.ndarray  # datetime64[D]
    sold: np.ndarray
    # When the dish sold out, as the owner reported it (minutes since midnight, rounded);
    # NaN when it didn't. On those days sold is below what customers wanted.
    sold_out_minute: np.ndarray


@dataclass(frozen=True)
class Target:
    """The day being forecast, with what is known about it in advance."""
    date: np.datetime64
    holiday: str
    # Stands in for the weather forecast. It is the day's actual rainfall, so it is
    # better than a real forecast would be.
    rainfall_mm: float


@dataclass(frozen=True)
class Forecast:
    point: float
    # Portions at probability p of the likely demand, e.g. quantile(0.9) is the top of an
    # 80% range.
    quantile: Callable[[float], float]


class Forecaster(Protocol):
    """Forecasts one dish's next day. A forecaster may also define
    forecast_batch(histories, targets) -> list[Forecast | None] to forecast many series in one
    call (a pretrained model batches far faster); the backtest uses it when present."""

    name: str

    def forecast(self, history: History, target: Target) -> Forecast | None: ...


def forecast_all(forecaster: Forecaster, histories: list[History], targets: list[Target]) -> list[Forecast | None]:
    batch = getattr(forecaster, "forecast_batch", None)
    if batch is not None:
        return batch(histories, targets)
    return [forecaster.forecast(history, target) for history, target in zip(histories, targets)]


class AppBaseline:
    """backend/src/main/java/app/service/BaselineForecaster.java, line for line: the mean of the
    last 4 same weekdays (or of the last 7 days), with a range from its own errors on the last
    12 days, or +/- 25% when it has fewer than 5 of them."""

    name = "App baseline"
    SAME_WEEKDAYS = 4
    RECENT_DAYS = 7
    BACKTEST_DAYS = 12
    MIN_BACKTEST_ERRORS = 5
    ROUGH_SPREAD = 0.25

    def forecast(self, history: History, target: Target) -> Forecast | None:
        # The estimate needs at most 4 same weekdays and the range 12 more days: 60 rows covers
        # both, even across rest days and closures.
        dates, sold = history.dates[-60:], history.sold[-60:]
        weekdays = _weekdays(dates)
        point = self._estimate(weekdays, sold, len(sold), _weekdays(np.array([target.date]))[0])
        if point is None:
            return None

        errors = []
        for index in range(max(1, len(sold) - self.BACKTEST_DAYS), len(sold)):
            estimate = self._estimate(weekdays, sold, index, weekdays[index])
            if estimate is not None:
                errors.append(sold[index] - estimate)

        if len(errors) < self.MIN_BACKTEST_ERRORS:
            # The app's rough range is forecast * (1 +/- 25%) for its 10%-90% range.
            return Forecast(point, lambda p: point * (1 + self.ROUGH_SPREAD * (p - 0.5) / 0.4))
        errors = np.sort(np.array(errors))
        return Forecast(point, lambda p: point + float(np.quantile(errors, p)))

    def _estimate(self, weekdays: np.ndarray, sold: np.ndarray, end: int, weekday: int) -> float | None:
        same = np.flatnonzero(weekdays[:end] == weekday)
        if len(same):
            return float(sold[same[-self.SAME_WEEKDAYS:]].mean())
        if end == 0:
            return None
        return float(sold[max(0, end - self.RECENT_DAYS):end].mean())


_GENERIC_CURVE = arrival_curve(A.GENERIC_OPENING_HOURS, A.GENERIC_ARRIVAL_PEAKS)


def adjust_for_sell_outs(history: History) -> History:
    """Scales up each sold-out day by how much of the day was left (#50): sold out at 1pm means
    most of the dinner crowd never got any. Uses a generic hawker customer pattern, not the
    stall's own, and the owner's rounded sell-out time."""
    minutes, share = _GENERIC_CURVE
    sold_out = ~np.isnan(history.sold_out_minute)
    arrived = np.ones(len(history.sold))
    positions = np.clip(np.searchsorted(minutes, history.sold_out_minute[sold_out]), 0, len(minutes) - 1)
    arrived[sold_out] = np.maximum(share[positions], A.MIN_ARRIVED_SHARE)
    return History(history.dates, history.sold / arrived, history.sold_out_minute)


class SoldOutAwareBaseline(AppBaseline):
    """The app baseline on sales adjusted for sell-outs (adjust_for_sell_outs)."""

    name = "Baseline + sell-out times"

    def forecast(self, history: History, target: Target) -> Forecast | None:
        return super().forecast(adjust_for_sell_outs(history), target)


class AppSoldOutBaseline(AppBaseline):
    """SoldOutDemand.java, as the app does it with app.forecast.count-sold-out-demand on (#50):
    a sold-out day counts as what it would have sold at the same rate until closing, at most
    MAX_UPLIFT times the recorded sales; a sell-out outside the trading hours counts as recorded.
    The range still comes from the recorded sales, moved to the new estimate. The app uses the
    stall's own trading hours; here every stall gets the generic ones."""

    name = "App baseline + sell-out times"
    MAX_UPLIFT = 1.5

    def forecast(self, history: History, target: Target) -> Forecast | None:
        recorded = super().forecast(history, target)
        opens, closes = (hour * 60 for hour in A.GENERIC_OPENING_HOURS)
        uplift = np.ones(len(history.sold))
        sold_out = ~np.isnan(history.sold_out_minute)
        on_sale = history.sold_out_minute[sold_out] - opens
        inside = (on_sale >= 0) & (on_sale <= closes - opens)
        scaled = np.where(on_sale > 0, (closes - opens) / np.maximum(on_sale, 1), self.MAX_UPLIFT)
        uplift[sold_out] = np.where(inside, np.minimum(scaled, self.MAX_UPLIFT), 1.0)
        demand = super().forecast(History(history.dates, history.sold * uplift, history.sold_out_minute), target)
        if recorded is None or demand is None:
            return demand
        shift = demand.point - recorded.point
        return Forecast(demand.point, lambda p: recorded.quantile(p) + shift)


def _weekdays(dates: np.ndarray) -> np.ndarray:
    # 1970-01-01 was a Thursday; Monday = 0.
    return (dates.astype("datetime64[D]").astype(np.int64) + 3) % 7


def reported_minute(minute: float) -> float:
    """A sell-out time as an owner would report it."""
    step = A.SOLD_OUT_REPORT_ROUNDING_MINUTES
    return float(step * round(minute / step))


def _clock_to_minute(clock: str) -> float:
    if not clock:
        return np.nan
    hours, minutes = clock.split(":")
    return reported_minute(int(hours) * 60 + int(minutes))


def _round_up(value: float) -> int:
    return max(0, math.ceil(value - 1e-9))


def critical_ratio(price: float, unit_cost: float) -> float:
    """Running out loses the profit (price - cost); a leftover loses the cost. Preparing up to
    this point of the likely demand balances the two (the newsvendor rule)."""
    if price <= 0:
        return 0.5
    return min(0.99, max(0.01, (price - unit_cost) / price))


# Prep policy: (forecast, price, unit cost) -> portions to prepare.
Policy = Callable[[Forecast, float, float], int]

POLICIES: dict[str, Policy] = {
    "Forecast only": lambda f, price, cost: _round_up(f.point - 0.5),
    "Forecast + 20%": lambda f, price, cost: _round_up(f.point * 1.2),
    # PrepListService without a buffer: the top of the forecast's likely range.
    "Top of likely range (app default)": lambda f, price, cost: _round_up(max(f.quantile(0.9), f.point)),
    "Margin rule": lambda f, price, cost: _round_up(f.quantile(critical_ratio(price, cost))),
}

OWNER_HABIT = "Owner's own habit (no app)"
REFERENCE = "Reference"
BEST_POSSIBLE = "Best possible: knows each day's true average"


def best_possible_portions(expected_demand: float, price: float, unit_cost: float,
                           rng: np.random.Generator, samples: int = 2000) -> int:
    """The margin rule applied to the true spread of demand around the day's true average: what
    a perfect forecaster would prepare. The rest of the gap to the best profit is luck."""
    mean = expected_demand * np.exp(rng.normal(0, A.DAY_SHOCK, samples))
    draws = rng.negative_binomial(A.DISH_DISPERSION, A.DISH_DISPERSION / (A.DISH_DISPERSION + mean))
    return _round_up(float(np.quantile(draws, critical_ratio(price, unit_cost))))


@dataclass
class _Series:
    """One stall's dish: what's fixed during the backtest."""
    base: dict
    price: float
    cost: float
    dates: np.ndarray
    demand: np.ndarray
    curve: tuple[np.ndarray, np.ndarray]
    first: int
    targets: dict[np.datetime64, Target]
    recorded_sold: np.ndarray
    recorded_sold_out: np.ndarray


@dataclass
class _Run:
    """One series under one policy: its own recorded sales, which the policy changes."""
    series: _Series
    policy_name: str
    policy: Policy
    sold: list
    sold_out: list


def run_backtest(sales: pd.DataFrame, forecasters: list[Forecaster],
                 policies: dict[str, Policy] = POLICIES, eval_days: int = 182,
                 min_history_days: int = 28) -> pd.DataFrame:
    """One row per forecaster, policy and evaluated stall-dish-day. Steps through the evaluation
    days in order, asking each forecaster for every series due that day at once."""
    eval_start = sales["date"].max() - pd.Timedelta(days=eval_days - 1)
    rng = np.random.default_rng(0)
    records = []
    series = []

    for (stall_id, dish), rows in sales[sales["open"]].groupby(["stall_id", "dish"], sort=False):
        rows = rows.sort_values("date")
        dates = rows["date"].to_numpy().astype("datetime64[D]")
        is_eval = (rows["date"] >= eval_start).to_numpy()
        is_eval &= np.arange(len(rows)) >= min_history_days
        if not is_eval.any():
            continue
        first = int(np.argmax(is_eval))
        price, cost = float(rows["price"].iloc[0]), float(rows["unit_cost"].iloc[0])
        demand = rows["demand"].to_numpy()
        stall_type = A.STALL_TYPES[rows["stall_type"].iloc[0]]
        base = {"stall_id": stall_id, "stall_type": rows["stall_type"].iloc[0], "dish": dish,
                "price": price, "unit_cost": cost}

        expected = rows["expected_demand"].to_numpy()
        for index in range(first, len(rows)):
            records.append({**base, "forecaster": "", "policy": OWNER_HABIT, "date": dates[index],
                            "point": np.nan, "demand": demand[index],
                            "prepared": int(rows["prepared"].iloc[index])})
            records.append({**base, "forecaster": REFERENCE, "policy": BEST_POSSIBLE, "date": dates[index],
                            "point": expected[index], "demand": demand[index],
                            "prepared": best_possible_portions(expected[index], price, cost, rng)})

        series.append(_Series(
            base=base, price=price, cost=cost, dates=dates, demand=demand,
            curve=arrival_curve(stall_type.opening_hours, stall_type.arrival_peaks), first=first,
            targets={dates[index]: Target(dates[index], rows["holiday"].iloc[index], float(rows["rainfall_mm"].iloc[index]))
                     for index in range(first, len(rows))},
            recorded_sold=rows["sold"].to_numpy().astype(float),
            recorded_sold_out=np.array([_clock_to_minute(clock) for clock in rows["sold_out_at"]]),
        ))

    eval_dates = sorted({day for one in series for day in one.targets})
    for forecaster in forecasters:
        runs = [_Run(one, policy_name, policy, list(one.recorded_sold[:one.first]), list(one.recorded_sold_out[:one.first]))
                for one in series for policy_name, policy in policies.items()]
        for day in eval_dates:
            due = [run for run in runs if day in run.series.targets]
            histories = [History(run.series.dates[:len(run.sold)], np.array(run.sold), np.array(run.sold_out, dtype=float))
                         for run in due]
            forecasts = forecast_all(forecaster, histories, [run.series.targets[day] for run in due])
            for run, forecast in zip(due, forecasts):
                one = run.series
                index = len(run.sold)
                prepared = run.policy(forecast, one.price, one.cost) if forecast else 0
                records.append({**one.base, "forecaster": forecaster.name, "policy": run.policy_name,
                                "date": day, "point": forecast.point if forecast else np.nan,
                                "demand": one.demand[index], "prepared": prepared})
                run.sold.append(min(prepared, one.demand[index]))
                run.sold_out.append(reported_minute(sold_out_minute(prepared, one.demand[index], one.curve))
                                    if one.demand[index] > prepared else np.nan)

    result = pd.DataFrame(records)
    result["sold"] = np.minimum(result["prepared"], result["demand"])
    result["leftover"] = result["prepared"] - result["sold"]
    result["unmet"] = result["demand"] - result["sold"]
    return result


def summarise(result: pd.DataFrame) -> pd.DataFrame:
    """Waste, sell-outs and profit per forecaster and policy, in portions and dollars."""
    frame = result.assign(
        abs_error=(result["point"] - result["demand"]).abs(),
        waste_cost=result["leftover"] * result["unit_cost"],
        lost_margin=result["unmet"] * (result["price"] - result["unit_cost"]),
        profit=result["sold"] * result["price"] - result["prepared"] * result["unit_cost"],
        best_profit=result["demand"] * (result["price"] - result["unit_cost"]),
        ran_out=result["demand"] > result["prepared"],
    )
    groups = frame.groupby(["forecaster", "policy"], sort=False)
    demand = groups["demand"].sum()
    summary = pd.DataFrame({
        "dish_days": groups.size(),
        "forecast_wape_pct": 100 * groups["abs_error"].sum(min_count=1) / demand,
        "leftover_pct_of_demand": 100 * groups["leftover"].sum() / demand,
        "unmet_pct_of_demand": 100 * groups["unmet"].sum() / demand,
        "sold_out_days_pct": 100 * groups["ran_out"].mean(),
        "waste_cost": groups["waste_cost"].sum(),
        "lost_margin": groups["lost_margin"].sum(),
        "profit": groups["profit"].sum(),
        "pct_of_best_profit": 100 * groups["profit"].sum() / groups["best_profit"].sum(),
    })
    return summary.reset_index()


def ingredient_totals(result: pd.DataFrame) -> pd.DataFrame:
    """Per forecaster, policy and ingredient: amounts prepared, needed, left over and short."""
    rows = []
    for dish, recipe in recipes().items():
        dish_rows = result[result["dish"] == dish]
        if dish_rows.empty:
            continue
        totals = dish_rows.groupby(["forecaster", "policy"], sort=False)[
            ["prepared", "demand", "leftover", "unmet"]].sum()
        for ingredient, (amount, unit) in recipe.items():
            rows.append((totals * amount).assign(ingredient=ingredient, unit=unit).reset_index())
    combined = pd.concat(rows)
    return (combined.groupby(["forecaster", "policy", "ingredient", "unit"], sort=False)[
        ["prepared", "demand", "leftover", "unmet"]].sum()
        .rename(columns={"demand": "needed", "unmet": "short"}).reset_index())
