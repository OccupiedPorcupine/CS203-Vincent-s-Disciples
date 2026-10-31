"""
Every assumption the demand simulator makes, in one place.

None of these numbers come from real hawker sales: no public dataset of them exists
(see issue #85). They are plausible guesses, chosen so methods can be compared on data whose
true demand is known. Results on simulated data show whether a method recovers these
assumptions, not whether it works for a real stall (#6). Change them here, not in the code.

The calendar and weather inputs are real: Singapore public holidays (data.gov.sg, Ministry of
Manpower) and daily rainfall at NEA's Admiralty station (data.gov.sg), in ml/data/raw/.
"""

from __future__ import annotations

from dataclasses import dataclass, field

# ---------------------------------------------------------------------------
# Menus
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Dish:
    name: str
    # Portions sold on an ordinary weekday at a stall of average size, before any effects.
    base_portions: float
    price: float
    unit_cost: float
    # What one portion uses: ingredient -> (amount, unit).
    recipe: dict[str, tuple[float, str]] = field(default_factory=dict)


MENUS: dict[str, list[Dish]] = {
    "chicken rice": [
        Dish("Roasted chicken rice", 55, 4.50, 1.60,
             {"Chicken rice": (200, "g"), "Roast chicken": (110, "g"), "Cucumber": (20, "g")}),
        Dish("Steamed chicken rice", 48, 4.50, 1.60,
             {"Chicken rice": (200, "g"), "Steamed chicken": (110, "g"), "Cucumber": (20, "g")}),
        Dish("Char siew rice", 30, 5.00, 2.00,
             {"Chicken rice": (200, "g"), "Char siew": (100, "g")}),
        Dish("Chicken drumstick rice", 18, 5.50, 2.20,
             {"Chicken rice": (200, "g"), "Chicken drumstick": (1, "pcs")}),
        Dish("Braised egg", 25, 0.80, 0.25, {"Egg": (1, "pcs")}),
    ],
    "noodles": [
        Dish("Bak chor mee", 60, 5.00, 1.90,
             {"Noodles": (130, "g"), "Minced pork": (70, "g"), "Fishball": (3, "pcs")}),
        Dish("Fishball noodle soup", 35, 4.50, 1.50, {"Noodles": (130, "g"), "Fishball": (5, "pcs")}),
        Dish("Mee pok tah", 25, 5.00, 1.80,
             {"Noodles": (130, "g"), "Minced pork": (50, "g"), "Fishball": (3, "pcs")}),
    ],
    "nasi lemak": [
        Dish("Nasi lemak with chicken wing", 45, 4.00, 1.50,
             {"Coconut rice": (180, "g"), "Chicken wing": (1, "pcs"), "Egg": (1, "pcs"),
              "Ikan bilis": (15, "g")}),
        Dish("Nasi lemak with otah", 20, 3.80, 1.40,
             {"Coconut rice": (180, "g"), "Otah": (1, "pcs"), "Egg": (1, "pcs")}),
        Dish("Nasi lemak with fish", 25, 4.50, 1.80,
             {"Coconut rice": (180, "g"), "Fish fillet": (80, "g"), "Egg": (1, "pcs")}),
        Dish("Mee siam", 18, 3.50, 1.10, {"Bee hoon": (120, "g"), "Egg": (1, "pcs")}),
    ],
}

# ---------------------------------------------------------------------------
# Where the stall is
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class StallType:
    # Demand multiplier, Monday first (matching date.weekday()).
    weekday_factor: tuple[float, ...]
    # Multiplier on a public holiday when the stall is open.
    holiday_factor: float
    # Weekly rest day choices, Monday = 0. One is picked per stall.
    rest_day_choices: tuple[int, ...]
    # Trading hours (24-hour clock) and when customers arrive: (peak hour, spread in hours, weight).
    opening_hours: tuple[float, float]
    arrival_peaks: tuple[tuple[float, float, float], ...]


STALL_TYPES: dict[str, StallType] = {
    # Busier at the weekend and on holidays. The weekday shape follows hawker_sales.xlsx (#6),
    # which is labelled as mock data, so it is a guess too.
    "residential": StallType(
        weekday_factor=(0.92, 0.90, 0.94, 0.93, 1.03, 1.22, 1.15),
        holiday_factor=1.15,
        rest_day_choices=(0, 1, 2, 3),
        opening_hours=(7.0, 20.0),
        arrival_peaks=((8.5, 0.75, 0.20), (12.5, 1.0, 0.50), (18.5, 1.0, 0.30)),
    ),
    # Office crowd: busy on weekdays, quiet at the weekend and on holidays.
    "office": StallType(
        weekday_factor=(1.05, 1.02, 1.03, 1.00, 1.10, 0.45, 0.30),
        holiday_factor=0.35,
        rest_day_choices=(6,),
        opening_hours=(7.0, 15.0),
        arrival_peaks=((8.0, 0.75, 0.25), (12.5, 0.75, 0.75)),
    ),
}

# ---------------------------------------------------------------------------
# What changes demand from day to day
# ---------------------------------------------------------------------------

# Daily rainfall (mm) -> demand multiplier. hawker_sales.xlsx shows heavy-rain days about 20%
# below sunny ones (#6). Daily totals don't say *when* it rained, so this is blunt on purpose.
RAIN_FACTORS: tuple[tuple[float, float], ...] = (
    (1.0, 1.00),   # under 1 mm: dry
    (10.0, 0.95),  # 1-10 mm: light rain
    (30.0, 0.88),  # 10-30 mm: moderate
    (float("inf"), 0.80),  # 30 mm or more: heavy
)

# Stall size: each stall's base portions are scaled by exp(normal(0, STALL_SIZE_SPREAD)).
STALL_SIZE_SPREAD = 0.3
# Yearly growth or decline per stall, drawn uniformly from this range.
YEARLY_TREND_RANGE = (-0.08, 0.12)
# Footfall shock shared by every dish at a stall on a day: exp(normal(0, DAY_SHOCK)).
DAY_SHOCK = 0.10
# Dish-level noise: negative binomial with this dispersion (variance = mean + mean^2 / r).
DISH_DISPERSION = 60.0

# ---------------------------------------------------------------------------
# When the stall is closed
# ---------------------------------------------------------------------------

# NEA closes each hawker centre for cleaning about once a quarter, typically for a few days.
CLEANINGS_PER_YEAR = 4
CLEANING_DAYS_RANGE = (2, 5)
# Most Chinese New Year holidays are closed, plus a few extra days after.
CNY_EXTRA_CLOSED_DAYS_RANGE = (0, 3)
# One week of leave a year.
LEAVE_DAYS = 7

# ---------------------------------------------------------------------------
# How much the stall prepares (before using the app)
# ---------------------------------------------------------------------------

# Owners prepare roughly what they sold on the last few same weekdays, plus a personal margin,
# rounded up to a batch. They see sales, not demand, but remember running out: a sold-out day
# counts as this much more than it sold.
OWNER_HABIT_WEEKS = 4
OWNER_BUFFER_RANGE = (0.0, 0.25)
OWNER_SOLD_OUT_BUMP = 0.15
PREP_BATCH = 5
# Before an owner has OWNER_HABIT_WEEKS of history, they prepare this multiple of a typical day.
OWNER_START_MULTIPLE = 1.2

# ---------------------------------------------------------------------------
# What the app knows about sell-outs (#50)
# ---------------------------------------------------------------------------

# Owners report a sell-out time roughly ("around 1pm"), to the nearest this many minutes.
SOLD_OUT_REPORT_ROUNDING_MINUTES = 30
# The app doesn't know each stall's customer pattern, so it assumes this generic hawker one
# (breakfast, lunch, dinner) when it scales up a sold-out day.
GENERIC_OPENING_HOURS = (7.0, 20.0)
GENERIC_ARRIVAL_PEAKS = ((8.5, 0.75, 0.20), (12.5, 1.0, 0.55), (18.5, 1.0, 0.25))
# Never scale a sold-out day's sales by more than 1 / this share.
MIN_ARRIVED_SHARE = 0.3
