from __future__ import annotations

import calendar
from collections.abc import Mapping

import numpy as np
import pandas as pd


WEEKDAY_COLUMNS = (
    "MONDAY",
    "TUESDAY",
    "WEDNESDAY",
    "THURSDAY",
    "FRIDAY",
    "SATURDAY",
    "SUNDAY",
)

MONTH_COLUMNS = (
    "MONTH_JAN",
    "MONTH_FEB",
    "MONTH_MAR",
    "MONTH_APR",
    "MONTH_MAY",
    "MONTH_JUN",
    "MONTH_JUL",
    "MONTH_AUG",
    "MONTH_SEP",
    "MONTH_OCT",
    "MONTH_NOV",
    "MONTH_DEC",
)

WEATHER_COLUMNS = (
    "WIND",
    "CLOUD_COVER",
    "PRECIPITATION",
    "SUNSHINE",
    "AIR_TEMPERATURE",
)


def _prepare_history(
    sales_history: pd.DataFrame,
    forecast_date: pd.Timestamp,
) -> pd.DataFrame:
    """
    Validate and prepare one dish's historical sales.

    Expected columns:
        DEMAND_DATE
        DEMAND
    """

    required = {
        "DEMAND_DATE",
        "DEMAND",
    }

    missing = required - set(sales_history.columns)

    if missing:
        raise ValueError(
            f"Sales history is missing columns: {sorted(missing)}"
        )

    history = sales_history[
        [
            "DEMAND_DATE",
            "DEMAND",
        ]
    ].copy()

    history["DEMAND_DATE"] = pd.to_datetime(
        history["DEMAND_DATE"]
    ).dt.normalize()

    history["DEMAND"] = pd.to_numeric(
        history["DEMAND"],
        errors="raise",
    )

    if history["DEMAND_DATE"].duplicated().any():
        raise ValueError(
            "Sales history contains duplicate dates"
        )

    if history["DEMAND"].isna().any():
        raise ValueError(
            "Sales history contains missing demand values"
        )

    if not np.isfinite(history["DEMAND"]).all():
        raise ValueError(
            "Sales history contains non-finite demand values"
        )

    if (history["DEMAND"] < 0).any():
        raise ValueError(
            "Demand cannot be negative"
        )

    # Anything on or after the forecast date would leak
    # future information into the prediction.
    if (
        history["DEMAND_DATE"]
        >= forecast_date
    ).any():
        raise ValueError(
            "Sales history must contain only dates "
            "before the forecast date"
        )

    history = (
        history
        .sort_values("DEMAND_DATE")
        .reset_index(drop=True)
    )

    if len(history) < 7:
        raise ValueError(
            "At least 7 previous sales observations are required"
        )

    return history


def build_features(
    *,
    dish: str,
    forecast_date: str | pd.Timestamp,
    sales_history: pd.DataFrame,
    weather: Mapping[str, float],
    is_holiday: bool,
) -> dict[str, object]:
    """
    Build one model-ready CatBoost feature row.

    sales_history must contain one dish's historical demand
    using columns DEMAND_DATE and DEMAND.
    """

    if not isinstance(dish, str) or not dish.strip():
        raise ValueError(
            "dish must be a non-empty string"
        )

    forecast_date = pd.Timestamp(
        forecast_date
    ).normalize()

    history = _prepare_history(
        sales_history,
        forecast_date,
    )

    features: dict[str, object] = {
        "DISH": dish,
    }

    # --------------------------------------------------
    # 1. Previous recorded-demand features
    # --------------------------------------------------

    previous_7 = (
        history["DEMAND"]
        .tail(7)
        .astype(float)
        .to_numpy()
    )

    # T1 = most recent recorded demand
    # T7 = seventh-most-recent recorded demand
    for lag in range(1, 8):
        features[f"DEMAND_T{lag}"] = float(
            previous_7[-lag]
        )

    # --------------------------------------------------
    # 2. Same-weekday historical means
    # --------------------------------------------------

    same_weekday = history[
        history["DEMAND_DATE"].dt.dayofweek
        == forecast_date.dayofweek
    ]["DEMAND"]

    if len(same_weekday) < 4:
        raise ValueError(
            "At least 4 previous observations of the "
            "forecast weekday are required"
        )

    for weeks in (2, 3, 4):
        features[
            f"MEAN_SAME_WDAY_W{weeks}"
        ] = float(
            same_weekday
            .tail(weeks)
            .mean()
        )

    # --------------------------------------------------
    # 3. Cumulative recent demand
    # --------------------------------------------------

    for lag in range(2, 8):
        features[
            f"CUM_DEMAND_T{lag}"
        ] = float(
            previous_7[-lag:].sum()
        )

    # --------------------------------------------------
    # 4. Seven-observation distribution features
    # --------------------------------------------------

    features["HML_DEMAND_T7"] = float(
        previous_7.max()
        - previous_7.min()
    )

    mean_7 = float(
        previous_7.mean()
    )

    features[
        "NO_DAYS_ABOVE_7D_MEAN"
    ] = int(
        (previous_7 > mean_7).sum()
    )

    features[
        "NO_DAYS_BELOW_7D_MEAN"
    ] = int(
        (previous_7 < mean_7).sum()
    )

    # --------------------------------------------------
    # 5. Weekday one-hot features
    # --------------------------------------------------

    weekday_name = (
        forecast_date
        .day_name()
        .upper()
    )

    for weekday in WEEKDAY_COLUMNS:
        features[weekday] = int(
            weekday == weekday_name
        )

    # --------------------------------------------------
    # 6. Month one-hot features
    # --------------------------------------------------

    month_name = (
        calendar.month_abbr[
            forecast_date.month
        ]
        .upper()
    )

    for month_column in MONTH_COLUMNS:
        features[month_column] = int(
            month_column
            == f"MONTH_{month_name}"
        )

    # --------------------------------------------------
    # 7. Holiday / weekend
    # --------------------------------------------------

    features["ISHOLIDAY"] = int(
        is_holiday
    )

    features["WEEKEND"] = int(
        forecast_date.dayofweek >= 5
    )

    # --------------------------------------------------
    # 8. Weather
    # --------------------------------------------------

    missing_weather = (
        set(WEATHER_COLUMNS)
        - set(weather)
    )

    if missing_weather:
        raise ValueError(
            "Missing weather features: "
            f"{sorted(missing_weather)}"
        )

    for column in WEATHER_COLUMNS:
        value = float(
            weather[column]
        )

        if not np.isfinite(value):
            raise ValueError(
                f"{column} must be finite"
            )

        features[column] = value

    return features