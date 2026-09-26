import numpy as np
import pytest

from ml.model_comparison import (
    DISHES,
    expanding_date_folds,
    lag_audit,
    load_dish_days,
    make_model,
    metrics,
    split_by_date,
)


@pytest.fixture(scope="module")
def prepared():
    wide, long = load_dish_days()
    return wide, long, split_by_date(long)


def test_same_original_observations_and_grouped_chronological_splits(prepared):
    wide, long, splits = prepared
    assert len(wide) == 732
    assert len(long) == len(wide) * len(DISHES)

    assert (
        len(splits["train"])
        + len(splits["validation"])
        + len(splits["test"])
        == len(long)
    )
    assert [(splits[name]["DEMAND_DATE"].min().strftime("%Y-%m-%d"),
             splits[name]["DEMAND_DATE"].max().strftime("%Y-%m-%d"))
            for name in ("train", "validation", "test")] == [
                ("2013-11-01", "2015-04-01"),
                ("2015-04-02", "2015-07-20"),
                ("2015-07-21", "2015-11-07"),
            ]
    groups = [set(frame["DEMAND_DATE"]) for frame in splits.values()]
    assert not (groups[0] & groups[1] or groups[0] & groups[2] or groups[1] & groups[2])
    assert all(frame.groupby("DEMAND_DATE")["DISH"].nunique().eq(len(DISHES)).all()
               for frame in splits.values())


def test_expanding_folds_never_cross_dates_or_leave_training(
    prepared,
):
    train = prepared[2]["train"]
    folds = list(
        expanding_date_folds(train)
    )
    assert len(folds) == 3
    train_dates = set(
        train["DEMAND_DATE"].unique()
    )
    score_dates_seen = set()
    for fit_idx, score_idx in folds:
        fit = train.iloc[fit_idx]
        score = train.iloc[score_idx]
        fit_dates = set(
            fit["DEMAND_DATE"].unique()
        )
        score_dates = set(
            score["DEMAND_DATE"].unique()
        )
        # each scoring fold should contain 70 dates
        assert len(score_dates) == 70
        # training happens  before scoring
        assert (
            fit["DEMAND_DATE"].max()
            < score["DEMAND_DATE"].min()
        )
        # fit and score portions must not overlap
        assert fit_dates.isdisjoint(
            score_dates
        )
        assert fit_dates.issubset(
            train_dates
        )
        assert score_dates.issubset(
            train_dates
        )
        # scoring folds themselves must not overlap
        assert score_dates_seen.isdisjoint(
            score_dates
        )
        score_dates_seen.update(
            score_dates
        )


def test_prior_row_audit_detects_current_target_contamination(prepared):
    wide = prepared[0]
    audit = lag_audit(wide)
    assert audit["calendar_gap_events"] == 3
    assert audit["missing_calendar_dates"] == 5
    changed = wide.copy()
    changed.loc[20, "CALAMARI_DEMAND_T1"] = changed.loc[20, "CALAMARI"]
    with pytest.raises(ValueError, match="CALAMARI_DEMAND_T1"):
        lag_audit(changed)


def test_ridge_and_baselines_reproduce_corrected_validation(prepared):
    long, splits = prepared[1:]

    numeric = [
        c
        for c in long
        if c not in (
            "DEMAND_DATE",
            "DISH",
            "DEMAND",
        )
    ]

    assert len(numeric) == 45

    train = splits["train"]
    val = splits["validation"]

    ridge = make_model(
        "Ridge",
        numeric,
    )

    ridge.fit(
        train[["DISH", *numeric]],
        train["DEMAND"],
    )

    ridge_scores = metrics(
        val["DEMAND"].to_numpy(),
        ridge.predict(
            val[["DISH", *numeric]]
        ),
    )

    naive_scores = metrics(
        val["DEMAND"].to_numpy(),
        val["DEMAND_T1"].to_numpy(),
    )

    seasonal_scores = metrics(
        val["DEMAND"].to_numpy(),
        val["DEMAND_T7"].to_numpy(),
    )

    assert ridge_scores["MAE"] == pytest.approx(
        4.951362011964557,
        abs=1e-6,
    )

    assert ridge_scores["RMSE"] == pytest.approx(
        7.057543378645058,
        abs=1e-6,
    )

    assert ridge_scores["WAPE_pct"] == pytest.approx(
        27.98200916853364,
        abs=1e-6,
    )

    assert naive_scores["MAE"] == pytest.approx(
        7.397402597402597,
        abs=1e-6,
    )

    assert naive_scores["RMSE"] == pytest.approx(
        11.12432691674644,
        abs=1e-6,
    )

    assert naive_scores["WAPE_pct"] == pytest.approx(
        41.80550458715596,
        abs=1e-6,
    )

    assert seasonal_scores["MAE"] == pytest.approx(
        6.364935064935065,
        abs=1e-6,
    )

    assert seasonal_scores["RMSE"] == pytest.approx(
        9.494974747441168,
        abs=1e-6,
    )

    assert seasonal_scores["WAPE_pct"] == pytest.approx(
        35.97064220183486,
        abs=1e-6,
    )
