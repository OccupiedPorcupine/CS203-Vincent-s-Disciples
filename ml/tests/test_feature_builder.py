import pandas as pd
import pytest
import numpy as np

from ml.model_comparison import (
    load_dish_days,
)

from ml.src.features.feature_builder import (
    WEATHER_COLUMNS,
    build_features,
)


def test_build_features_from_sales_history():
    history = pd.DataFrame({
        "DEMAND_DATE": pd.date_range(
            "2026-01-01",
            periods=35,
            freq="D",
        ),
        "DEMAND": range(
            1,
            36,
        ),
    })

    weather = {
        "WIND": 5.0,
        "CLOUD_COVER": 3.0,
        "PRECIPITATION": 1.0,
        "SUNSHINE": 4.0,
        "AIR_TEMPERATURE": 30.0,
    }

    features = build_features(
        dish="TEST_DISH",
        forecast_date="2026-02-05",
        sales_history=history,
        weather=weather,
        is_holiday=False,
    )

    # Previous recorded demands are:
    # 29, 30, 31, 32, 33, 34, 35

    assert features["DEMAND_T1"] == 35
    assert features["DEMAND_T7"] == 29

    assert features[
        "CUM_DEMAND_T2"
    ] == 69

    assert features[
        "CUM_DEMAND_T7"
    ] == 224

    assert features[
        "HML_DEMAND_T7"
    ] == 6

    assert features[
        "NO_DAYS_ABOVE_7D_MEAN"
    ] == 3

    assert features[
        "NO_DAYS_BELOW_7D_MEAN"
    ] == 3

    # Feb 5, 2026 is Thursday.
    #
    # Previous Thursdays:
    # Jan 29 -> 29
    # Jan 22 -> 22
    # Jan 15 -> 15
    # Jan 08 -> 8

    assert features[
        "MEAN_SAME_WDAY_W2"
    ] == pytest.approx(25.5)

    assert features[
        "MEAN_SAME_WDAY_W3"
    ] == pytest.approx(22.0)

    assert features[
        "MEAN_SAME_WDAY_W4"
    ] == pytest.approx(18.5)

    assert features["THURSDAY"] == 1
    assert features["WEDNESDAY"] == 0

    assert features["MONTH_FEB"] == 1
    assert features["MONTH_JAN"] == 0

    assert features["WEEKEND"] == 0
    assert features["ISHOLIDAY"] == 0

    assert features["AIR_TEMPERATURE"] == 30.0


def test_feature_builder_matches_training_features():
    wide, long = load_dish_days()

    dish = "CHICKEN"

    # Deliberately choose a date around the known
    # Christmas calendar gaps.
    forecast_date = pd.Timestamp(
        "2014-12-27"
    )

    history = (
        wide.loc[
            wide["DEMAND_DATE"]
            < forecast_date,
            [
                "DEMAND_DATE",
                dish,
            ],
        ]
        .rename(
            columns={
                dish: "DEMAND",
            }
        )
    )

    expected = long[
        (long["DEMAND_DATE"] == forecast_date)
        & (long["DISH"] == dish)
    ].iloc[0]

    weather = {
        column: float(expected[column])
        for column in WEATHER_COLUMNS
    }

    actual = build_features(
        dish=dish,
        forecast_date=forecast_date,
        sales_history=history,
        weather=weather,
        is_holiday=bool(
            expected["ISHOLIDAY"]
        ),
    )

    assert actual["DISH"] == expected["DISH"]

    for feature, value in actual.items():

        if feature == "DISH":
            continue

        assert float(value) == pytest.approx(
            float(expected[feature]),
        ), feature
