from __future__ import annotations

import pandas as pd


def recompute_same_weekday_features(
    dataframe: pd.DataFrame,
    dishes: tuple[str, ...] | list[str],
) -> pd.DataFrame:
    """
    Recompute same-weekday rolling demand means from observed dates.

    For each dish and date:

    W2 = mean of the previous 2 observed occurrences
         of the same weekday

    W3 = mean of the previous 3 observed occurrences
         of the same weekday

    W4 = mean of the previous 4 observed occurrences
         of the same weekday

    Missing calendar dates are left absent. They are not filled
    with zero or imputed demand.

    The current day's demand is excluded using shift(1).
    """

    df = (
        dataframe
        .sort_values("DEMAND_DATE")
        .reset_index(drop=True)
        .copy()
    )

    # Temporary grouping column:
    # Monday=0, Tuesday=1, ..., Sunday=6.
    df["_WEEKDAY"] = (
        df["DEMAND_DATE"]
        .dt.dayofweek
    )

    for dish in dishes:

        for weeks in (2, 3, 4):

            column = (
                f"{dish}_MEAN_SAME_WDAY_DEMANDS_W{weeks}"
            )

            df[column] = (
                df
                .groupby(
                    "_WEEKDAY",
                    sort=False,
                )[dish]
                .transform(
                    lambda series:
                    series
                    .shift(1)
                    .rolling(
                        window=weeks,
                        min_periods=weeks,
                    )
                    .mean()
                )
            )

    df = df.drop(
        columns="_WEEKDAY"
    )

    return df