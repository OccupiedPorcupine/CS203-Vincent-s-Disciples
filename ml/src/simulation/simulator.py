"""
Simulate daily sales at hawker stalls whose true demand is known (issue #85).

Each stall has a menu, a location type (residential or office), a weekly rest day, cleaning
closures, Chinese New Year and leave. Demand follows the weekday, public holidays and real
Singapore rainfall, with day-to-day noise. The owner prepares from habit, so on a busy day the
stall sells out: the recorded sales are what it *had*, and the true demand is kept separately.

Every assumption is in ml/src/simulation/assumptions.py.
"""

from __future__ import annotations

import csv
import math
from collections import deque
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

from ml.src.simulation import assumptions as A

RAW_DATA_DIR = Path(__file__).resolve().parents[2] / "data" / "raw"
HOLIDAYS_PATH = RAW_DATA_DIR / "sg_public_holidays.csv"
RAINFALL_PATH = RAW_DATA_DIR / "sg_daily_rainfall.csv"

COLUMNS = [
    "stall_id", "stall_type", "menu", "date", "dish", "price", "unit_cost",
    "open", "closure_reason", "holiday", "rainfall_mm",
    "expected_demand", "demand", "prepared", "sold", "sold_out_at", "leftover",
]


@dataclass(frozen=True)
class Stall:
    stall_id: str
    stall_type: str
    menu: str
    size: float
    yearly_trend: float
    owner_buffer: float
    rest_day: int


def load_holidays(path: Path = HOLIDAYS_PATH) -> dict[date, str]:
    with open(path, newline="", encoding="utf-8") as file:
        return {date.fromisoformat(row["date"]): row["holiday"] for row in csv.DictReader(file)}


def load_rainfall(path: Path = RAINFALL_PATH) -> dict[tuple[int, int], list[float]]:
    """Rainfall by (month, day), one value per recorded year, oldest year first."""
    by_day: dict[tuple[int, int], list[float]] = {}
    with open(path, newline="", encoding="utf-8") as file:
        for row in csv.DictReader(file):
            day = date.fromisoformat(row["date"])
            by_day.setdefault((day.month, day.day), []).append(float(row["rainfall_mm"]))
    return by_day


def borrowed_rainfall(day: date, rainfall: dict[tuple[int, int], list[float]]) -> float:
    """The rainfall recorded on the same day of the year, in a recorded year picked by the
    simulated year, so each simulated year gets one real year's weather pattern."""
    options = rainfall.get((day.month, day.day)) or rainfall[(2, 28)]
    return options[day.year % len(options)]


def rain_factor(rainfall_mm: float) -> float:
    for upper, factor in A.RAIN_FACTORS:
        if rainfall_mm < upper:
            return factor
    return A.RAIN_FACTORS[-1][1]


def make_stalls(count: int, rng: np.random.Generator) -> list[Stall]:
    types = list(A.STALL_TYPES)
    menus = list(A.MENUS)
    stalls = []
    for index in range(count):
        stall_type = types[index % len(types)]
        stalls.append(Stall(
            stall_id=f"S{index + 1:02d}",
            stall_type=stall_type,
            menu=menus[(index // len(types)) % len(menus)],
            size=float(np.exp(rng.normal(0, A.STALL_SIZE_SPREAD))),
            yearly_trend=float(rng.uniform(*A.YEARLY_TREND_RANGE)),
            owner_buffer=float(rng.uniform(*A.OWNER_BUFFER_RANGE)),
            rest_day=int(rng.choice(A.STALL_TYPES[stall_type].rest_day_choices)),
        ))
    return stalls


def closures(stall: Stall, dates: list[date], holidays: dict[date, str],
             rng: np.random.Generator) -> dict[date, str]:
    """Closed dates with a reason. Earlier reasons win when two overlap."""
    closed: dict[date, str] = {}

    def close(start: date, days: int, reason: str) -> None:
        for offset in range(days):
            closed.setdefault(start + timedelta(days=offset), reason)

    for year in sorted({day.year for day in dates}):
        for quarter in range(A.CLEANINGS_PER_YEAR):
            quarter_start = date(year, 1 + quarter * 3, 1)
            start = quarter_start + timedelta(days=int(rng.integers(0, 80)))
            close(start, int(rng.integers(A.CLEANING_DAYS_RANGE[0], A.CLEANING_DAYS_RANGE[1] + 1)),
                  "Hawker centre cleaning")

        new_year = sorted(day for day, name in holidays.items()
                          if day.year == year and name.startswith("Chinese New Year"))
        if new_year:
            extra = int(rng.integers(A.CNY_EXTRA_CLOSED_DAYS_RANGE[0], A.CNY_EXTRA_CLOSED_DAYS_RANGE[1] + 1))
            close(new_year[0], (new_year[-1] - new_year[0]).days + 1 + extra, "Chinese New Year")

        close(date(year, 1, 1) + timedelta(days=int(rng.integers(0, 358))), A.LEAVE_DAYS, "Leave")

    for day in dates:
        if day.weekday() == stall.rest_day:
            closed.setdefault(day, "Rest day")
    return closed


def arrival_curve(opening_hours: tuple[float, float],
                  arrival_peaks: tuple[tuple[float, float, float], ...]) -> tuple[np.ndarray, np.ndarray]:
    """Minutes since midnight across trading hours, and the share of the day's customers who
    have arrived by each minute."""
    opens, closes = opening_hours
    minutes = np.arange(opens * 60, closes * 60 + 1)
    density = sum(weight * np.exp(-0.5 * ((minutes / 60 - peak) / spread) ** 2)
                  for peak, spread, weight in arrival_peaks)
    cumulative = np.cumsum(density)
    return minutes, cumulative / cumulative[-1]


def sold_out_minute(prepared: int, demand: int, curve: tuple[np.ndarray, np.ndarray]) -> int:
    """When the last portion went, in minutes since midnight: the moment the arriving customers
    reached what was prepared."""
    minutes, share = curve
    return int(minutes[min(np.searchsorted(share, prepared / demand), len(minutes) - 1)])


def sold_out_time(prepared: int, demand: int, curve: tuple[np.ndarray, np.ndarray]) -> str:
    minute = sold_out_minute(prepared, demand, curve)
    return f"{minute // 60:02d}:{minute % 60:02d}"


def simulate(stall_count: int = 24, start: date = date(2024, 1, 1), end: date = date(2026, 6, 30),
             seed: int = 7) -> pd.DataFrame:
    """One row per stall, dish and calendar date. Closed days have open=False and no sales.

    expected_demand is the average the day's demand was drawn around, before the stall's
    footfall shock and the dish's own noise: the best any forecaster could hope to know."""
    rng = np.random.default_rng(seed)
    holidays = load_holidays()
    rainfall = load_rainfall()
    dates = [start + timedelta(days=offset) for offset in range((end - start).days + 1)]
    rain = np.array([borrowed_rainfall(day, rainfall) for day in dates])
    rain_effect = np.array([rain_factor(mm) for mm in rain])
    years_in = np.array([(day - start).days / 365.25 for day in dates])

    rows = []
    for stall in make_stalls(stall_count, rng):
        stall_type = A.STALL_TYPES[stall.stall_type]
        closed = closures(stall, dates, holidays, rng)
        curve = arrival_curve(stall_type.opening_hours, stall_type.arrival_peaks)
        weekday = np.array([stall_type.weekday_factor[day.weekday()] for day in dates])
        holiday = np.array([stall_type.holiday_factor if day in holidays else 1.0 for day in dates])
        day_shock = np.exp(rng.normal(0, A.DAY_SHOCK, len(dates)))
        expected_footfall = stall.size * weekday * holiday * rain_effect * (1 + stall.yearly_trend) ** years_in
        footfall = expected_footfall * day_shock

        for dish in A.MENUS[stall.menu]:
            mean_demand = dish.base_portions * footfall
            demand = rng.negative_binomial(A.DISH_DISPERSION,
                                           A.DISH_DISPERSION / (A.DISH_DISPERSION + mean_demand))
            past_sales = {weekday_index: deque(maxlen=A.OWNER_HABIT_WEEKS) for weekday_index in range(7)}

            for index, day in enumerate(dates):
                base = [stall.stall_id, stall.stall_type, stall.menu, day, dish.name, dish.price,
                        dish.unit_cost]
                calendar = [holidays.get(day, ""), float(rain[index])]
                if day in closed:
                    rows.append(base + [False, closed[day]] + calendar + [0.0, 0, 0, 0, "", 0])
                    continue

                past = past_sales[day.weekday()]
                if len(past) < A.OWNER_HABIT_WEEKS:
                    guess = (dish.base_portions * stall.size * stall_type.weekday_factor[day.weekday()]
                             * A.OWNER_START_MULTIPLE)
                else:
                    guess = sum(past) / len(past) * (1 + stall.owner_buffer)
                prepared = max(A.PREP_BATCH, math.ceil(guess / A.PREP_BATCH) * A.PREP_BATCH)

                wanted = int(demand[index])
                sold = min(wanted, prepared)
                sold_out = sold_out_time(prepared, wanted, curve) if wanted > prepared else ""
                expected = float(dish.base_portions * expected_footfall[index])
                rows.append(base + [True, ""] + calendar
                            + [expected, wanted, prepared, sold, sold_out, prepared - sold])
                past.append(sold * (1 + A.OWNER_SOLD_OUT_BUMP) if sold_out else sold)

    frame = pd.DataFrame(rows, columns=COLUMNS)
    frame["date"] = pd.to_datetime(frame["date"])
    return frame


def recipes() -> dict[str, dict[str, tuple[float, str]]]:
    """Dish name -> recipe, across every menu."""
    return {dish.name: dish.recipe for menu in A.MENUS.values() for dish in menu}
