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
    assert len(wide) == 760
    assert len(long) == 5320
    assert [len(splits[name]) for name in ("train", "validation", "test")] == [3724, 798, 798]
    assert [(splits[name]["DEMAND_DATE"].min().strftime("%Y-%m-%d"),
             splits[name]["DEMAND_DATE"].max().strftime("%Y-%m-%d"))
            for name in ("train", "validation", "test")] == [
                ("2013-10-04", "2015-03-24"),
                ("2015-03-25", "2015-07-16"),
                ("2015-07-17", "2015-11-07"),
            ]
    groups = [set(frame["DEMAND_DATE"]) for frame in splits.values()]
    assert not (groups[0] & groups[1] or groups[0] & groups[2] or groups[1] & groups[2])
    assert all(frame.groupby("DEMAND_DATE")["DISH"].nunique().eq(len(DISHES)).all()
               for frame in splits.values())


def test_expanding_folds_never_cross_dates_or_leave_training(prepared):
    train = prepared[2]["train"]
    folds = list(expanding_date_folds(train))
    assert len(folds) == 3
    score_dates = []
    for fit_idx, score_idx in folds:
        fit, score = train.iloc[fit_idx], train.iloc[score_idx]
        assert fit["DEMAND_DATE"].nunique() >= 322
        assert score["DEMAND_DATE"].nunique() == 70
        assert fit["DEMAND_DATE"].max() < score["DEMAND_DATE"].min()
        assert len(score) == 490
        score_dates.append(set(score["DEMAND_DATE"]))
    assert not (score_dates[0] & score_dates[1] or score_dates[1] & score_dates[2])


def test_prior_row_audit_detects_current_target_contamination(prepared):
    wide = prepared[0]
    audit = lag_audit(wide)
    assert audit["calendar_gap_events"] == 3
    assert audit["missing_calendar_dates"] == 5
    changed = wide.copy()
    changed.loc[20, "CALAMARI_DEMAND_T1"] = changed.loc[20, "CALAMARI"]
    with pytest.raises(ValueError, match="CALAMARI_DEMAND_T1"):
        lag_audit(changed)


def test_ridge_and_baselines_reproduce_original_validation(prepared):
    long, splits = prepared[1:]
    numeric = [c for c in long if c not in ("DEMAND_DATE", "DISH", "DEMAND")]
    assert len(numeric) == 45
    train, val = splits["train"], splits["validation"]
    ridge = make_model("Ridge", numeric)
    ridge.fit(train[["DISH", *numeric]], train["DEMAND"])
    ridge_scores = metrics(val["DEMAND"].to_numpy(), ridge.predict(val[["DISH", *numeric]]))
    naive = metrics(val["DEMAND"].to_numpy(), val["DEMAND_T1"].to_numpy())
    seasonal = metrics(val["DEMAND"].to_numpy(), val["DEMAND_T7"].to_numpy())
    assert ridge_scores["MAE"] == pytest.approx(4.954089116003066, abs=1e-6)
    assert ridge_scores["RMSE"] == pytest.approx(7.092024393249851, abs=1e-6)
    assert naive["MAE"] == pytest.approx(7.6441102757, abs=1e-6)
    assert seasonal["MAE"] == pytest.approx(6.3947368421, abs=1e-6)
    assert np.isfinite(ridge_scores["WAPE_pct"])
