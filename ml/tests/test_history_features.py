import pandas as pd
import pytest

from ml.src.features.history import (
    recompute_same_weekday_features,
)


def test_same_weekday_mean_skips_missing_dates():
    """
    Missing calendar dates should not break weekday alignment.

    We intentionally omit one Thursday and confirm that a later
    Thursday uses the previous observed Thursdays.
    """

    df = pd.DataFrame({
        "DEMAND_DATE": pd.to_datetime([
            "2026-01-01",  # Thursday
            "2026-01-08",  # Thursday
            # 2026-01-15 intentionally absent
            "2026-01-22",  # Thursday
            "2026-01-29",  # Thursday
            "2026-02-05",  # Thursday
        ]),
        "TEST_DISH": [
            10,
            20,
            30,
            40,
            50,
        ],
    })

    result = recompute_same_weekday_features(
        df,
        ["TEST_DISH"],
    )

    last_row = result.iloc[-1]

    assert (
        last_row[
            "TEST_DISH_MEAN_SAME_WDAY_DEMANDS_W2"
        ]
        == pytest.approx(35.0)
    )

    assert (
        last_row[
            "TEST_DISH_MEAN_SAME_WDAY_DEMANDS_W3"
        ]
        == pytest.approx(30.0)
    )

    assert (
        last_row[
            "TEST_DISH_MEAN_SAME_WDAY_DEMANDS_W4"
        ]
        == pytest.approx(25.0)
    )