"""Reproducible, date-grouped comparison of restaurant demand regressors.

Run from the repository root: python -m ml.model_comparison
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import platform

import numpy as np
import pandas as pd
import sklearn
import xgboost
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestRegressor
from sklearn.linear_model import Ridge
from sklearn.metrics import mean_absolute_error, mean_squared_error
from sklearn.model_selection import ParameterSampler
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from xgboost import XGBRegressor


ROOT = Path(__file__).resolve().parents[1]
RAW_DATA = ROOT / "ml/data/raw/restaurant.csv"
OUTPUT_DIR = ROOT / "ml/artifacts/model_comparison"
SEED = 42
DISHES = ("CALAMARI", "FISH", "PRAWNS", "CHICKEN", "KOFTA", "LAMB", "STEAK")
SHARED_COLUMNS = (
    "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY",
    "MONTH_JAN", "MONTH_FEB", "MONTH_MAR", "MONTH_APR", "MONTH_MAY", "MONTH_JUN",
    "MONTH_JUL", "MONTH_AUG", "MONTH_SEP", "MONTH_OCT", "MONTH_NOV", "MONTH_DEC",
    "ISHOLIDAY", "WEEKEND", "WIND", "CLOUD_COVER", "PRECIPITATION", "SUNSHINE",
    "AIR_TEMPERATURE",
)
TRANSLATIONS = {
    "Montag": "MONDAY", "Dienstag": "TUESDAY", "Mittwoch": "WEDNESDAY",
    "Donnerstag": "THURSDAY", "Freitag": "FRIDAY", "Samstag": "SATURDAY",
    "Sonntag": "SUNDAY", "FISCHPROD": "FISH_PRODUCTS", "FISCH": "FISH",
    "GARNELEN": "PRAWNS", "HAEHNCHEN": "CHICKEN", "KOEFTE": "KOFTA",
    "LAMM": "LAMB", "FLEISCH": "MEAT", "BEWOELKUNG": "CLOUD_COVER",
    "NIEDERSCHLAG": "PRECIPITATION", "SONNE": "SUNSHINE",
    "LUFTTEMPERATUR": "AIR_TEMPERATURE",
}


def load_dish_days(path: Path = RAW_DATA) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Reproduce PR #2's EDA renaming and modeling notebook's long table."""
    wide = pd.read_csv(path, sep=";").drop(columns="Unnamed: 0")
    translated = []
    for column in wide.columns:
        for german, english in TRANSLATIONS.items():
            column = column.replace(german, english)
        translated.append(column)
    wide.columns = translated
    # Source dates are month.day.year (e.g. 10.13.2013); make this explicit.
    wide["DEMAND_DATE"] = pd.to_datetime(wide["DEMAND_DATE"], format="%m.%d.%Y")
    wide = wide.sort_values("DEMAND_DATE").reset_index(drop=True)
    if wide["DEMAND_DATE"].duplicated().any():
        raise ValueError("Duplicate source dates would mix date groups")

    parts = []
    for dish in DISHES:
        part = pd.DataFrame({
            "DEMAND_DATE": wide["DEMAND_DATE"], "DISH": dish, "DEMAND": wide[dish],
        })
        for lag in range(1, 8):
            part[f"DEMAND_T{lag}"] = wide[f"{dish}_DEMAND_T{lag}"]
        for week in range(2, 5):
            part[f"MEAN_SAME_WDAY_W{week}"] = wide[f"{dish}_MEAN_SAME_WDAY_DEMANDS_W{week}"]
        for lag in range(2, 8):
            part[f"CUM_DEMAND_T{lag}"] = wide[f"{dish}_CUM_DEMAND_T{lag}"]
        part["HML_DEMAND_T7"] = wide[f"{dish}_HML_DEMAND_T7"]
        part["NO_DAYS_ABOVE_7D_MEAN"] = wide[f"{dish}_NO_DAYS_ABOVE_7D_MEAN"]
        part["NO_DAYS_BELOW_7D_MEAN"] = wide[f"{dish}_NO_DAYS_BELOW_7D_MEAN"]
        parts.append(part)

    long = pd.concat(parts, ignore_index=True)
    long = long.merge(wide[["DEMAND_DATE", *SHARED_COLUMNS]], on="DEMAND_DATE", how="left", validate="many_to_one")
    if long.isna().any().any():
        raise ValueError("Missing values in model observations")
    if len(long) != len(wide) * len(DISHES):
        raise ValueError("Incorrect dish-day count")
    return wide, long


def split_by_date(data: pd.DataFrame) -> dict[str, pd.DataFrame]:
    """The original notebook's integer-truncated 70/15/15 boundaries."""
    dates = np.sort(data["DEMAND_DATE"].unique())
    train_end, val_end = int(len(dates) * 0.70), int(len(dates) * 0.85)
    groups = {
        "train": dates[:train_end],
        "validation": dates[train_end:val_end],
        "test": dates[val_end:],
    }
    return {name: data[data["DEMAND_DATE"].isin(group)].copy() for name, group in groups.items()}


def expanding_date_folds(train: pd.DataFrame, *, n_splits: int = 3, test_dates: int = 70):
    """Three nonoverlapping, forward 70-date blocks strictly inside training."""
    dates = np.sort(train["DEMAND_DATE"].unique())
    if len(dates) <= n_splits * test_dates:
        raise ValueError("Not enough training dates for expanding folds")
    for fold in range(n_splits):
        start = len(dates) - (n_splits - fold) * test_dates
        fit_mask = train["DEMAND_DATE"].isin(dates[:start]).to_numpy()
        score_mask = train["DEMAND_DATE"].isin(dates[start:start + test_dates]).to_numpy()
        yield np.flatnonzero(fit_mask), np.flatnonzero(score_mask)


def lag_audit(wide: pd.DataFrame) -> dict[str, object]:
    """Check known prior-row features; undefined warm-up rows are excluded."""
    errors = []
    for dish in DISHES:
        target = wide[dish]
        for lag in range(1, 8):
            actual = wide[f"{dish}_DEMAND_T{lag}"].iloc[7:].to_numpy()
            expected = target.shift(lag).iloc[7:].to_numpy()
            if not np.allclose(actual, expected):
                errors.append(f"{dish}_DEMAND_T{lag}")
        for lag in range(2, 8):
            prior = pd.concat([target.shift(i) for i in range(1, lag + 1)], axis=1)
            actual = wide[f"{dish}_CUM_DEMAND_T{lag}"].iloc[7:].to_numpy()
            if not np.allclose(actual, prior.sum(axis=1).iloc[7:].to_numpy()):
                errors.append(f"{dish}_CUM_DEMAND_T{lag}")
        prior7 = pd.concat([target.shift(i) for i in range(1, 8)], axis=1)
        actual_hml = wide[f"{dish}_HML_DEMAND_T7"].iloc[7:].to_numpy()
        expected_hml = (prior7.max(axis=1) - prior7.min(axis=1)).iloc[7:].to_numpy()
        if not np.allclose(actual_hml, expected_hml):
            errors.append(f"{dish}_HML_DEMAND_T7")
        mean = prior7.mean(axis=1)
        for direction, expected in (
            ("ABOVE", prior7.gt(mean, axis=0).sum(axis=1)),
            ("BELOW", prior7.lt(mean, axis=0).sum(axis=1)),
        ):
            column = f"{dish}_NO_DAYS_{direction}_7D_MEAN"
            if not np.allclose(wide[column].iloc[7:].to_numpy(), expected.iloc[7:].to_numpy()):
                errors.append(column)
    if errors:
        raise ValueError(f"Prior-row feature audit failed: {errors}")
    gaps = wide["DEMAND_DATE"].diff().dt.days.iloc[1:]
    gap_count = int((gaps != 1).sum())
    missing_dates = int((gaps - 1).clip(lower=0).sum())
    t7_nonweekly = int(((wide["DEMAND_DATE"] - wide["DEMAND_DATE"].shift(7)).dt.days.iloc[7:] != 7).sum())
    return {
        "checked": "T1–T7, cumulative T2–T7, HML T7, above/below seven-row mean after warm-up",
        "result": "all match prior recorded demand rows",
        "calendar_gap_events": gap_count,
        "missing_calendar_dates": missing_dates,
        "t7_not_seven_calendar_days": t7_nonweekly,
        "unverified": "same-weekday mean feature definitions and upstream provenance",
    }


def make_preprocessor(numeric: list[str], *, scale: bool) -> ColumnTransformer:
    return ColumnTransformer([
        ("dish", OneHotEncoder(handle_unknown="ignore"), ["DISH"]),
        ("numeric", StandardScaler() if scale else "passthrough", numeric),
    ])


def make_model(name: str, numeric: list[str], params: dict | None = None) -> Pipeline:
    params = params or {}
    if name == "Ridge":
        estimator = Ridge(alpha=1.0)
    elif name == "Random Forest":
        estimator = RandomForestRegressor(random_state=SEED, n_jobs=1, **params)
    elif name == "XGBoost":
        estimator = XGBRegressor(random_state=SEED, n_jobs=1, tree_method="hist", objective="reg:squarederror", **params)
    else:
        raise ValueError(f"Unknown model: {name}")
    return Pipeline([
        ("preprocessing", make_preprocessor(numeric, scale=name == "Ridge")),
        ("model", estimator),
    ])


def candidate_params(name: str, count: int = 20) -> list[dict]:
    if name == "Random Forest":
        space = {
            "n_estimators": [100, 200],
            "max_depth": [8, 12, None],
            "min_samples_leaf": [1, 2, 4, 8],
            "max_features": [0.5, 0.8, 1.0],
        }
    elif name == "XGBoost":
        space = {
            "n_estimators": [150, 300, 500],
            "max_depth": [2, 3, 4, 6],
            "learning_rate": [0.03, 0.05, 0.1],
            "min_child_weight": [1, 3, 6],
            "subsample": [0.8, 1.0],
            "colsample_bytree": [0.8, 1.0],
        }
    else:
        raise ValueError(name)
    return list(ParameterSampler(space, n_iter=count, random_state=SEED))


def tune(name: str, train: pd.DataFrame, numeric: list[str], count: int) -> tuple[dict, list[dict]]:
    x = train[["DISH", *numeric]]
    y = train["DEMAND"]
    folds = list(expanding_date_folds(train))
    records = []
    for index, params in enumerate(candidate_params(name, count)):
        fold_mae = []
        for fit_idx, score_idx in folds:
            model = make_model(name, numeric, params)
            model.fit(x.iloc[fit_idx], y.iloc[fit_idx])
            prediction = model.predict(x.iloc[score_idx])
            fold_mae.append(float(mean_absolute_error(y.iloc[score_idx], prediction)))
        records.append({
            "model": name, "candidate": index, "params": json.dumps(params, sort_keys=True),
            "mean_cv_mae": float(np.mean(fold_mae)),
            **{f"fold_{i + 1}_mae": value for i, value in enumerate(fold_mae)},
        })
    winner = min(records, key=lambda row: (row["mean_cv_mae"], row["candidate"]))
    return json.loads(winner["params"]), records


def metrics(actual: np.ndarray, predicted: np.ndarray) -> dict[str, float]:
    denominator = float(np.abs(actual).sum())
    return {
        "MAE": float(mean_absolute_error(actual, predicted)),
        "RMSE": float(np.sqrt(mean_squared_error(actual, predicted))),
        "WAPE_pct": float(np.abs(actual - predicted).sum() / denominator * 100) if denominator else float("nan"),
    }


def evaluate(split: str, data: pd.DataFrame, predictions: dict[str, np.ndarray]) -> tuple[list[dict], pd.DataFrame]:
    results = data[["DEMAND_DATE", "DISH", "DEMAND"]].copy().reset_index(drop=True)
    results.insert(0, "split", split)
    rows = []
    for name, predicted in predictions.items():
        predicted = np.asarray(predicted)
        if predicted.shape != (len(data),):
            raise ValueError(f"Incorrect prediction length for {name}")
        results[name] = predicted
        for dish in ("ALL", *DISHES):
            mask = np.ones(len(data), dtype=bool) if dish == "ALL" else data["DISH"].to_numpy() == dish
            rows.append({
                "split": split, "dish": dish, "model": name, "observations": int(mask.sum()),
                **metrics(data["DEMAND"].to_numpy()[mask], predicted[mask]),
                "negative_predictions": int((predicted[mask] < 0).sum()),
            })
    return rows, results


def run(raw_path: Path = RAW_DATA, output_dir: Path = OUTPUT_DIR, candidates: int = 20) -> pd.DataFrame:
    wide, long = load_dish_days(raw_path)
    audit = lag_audit(wide)
    splits = split_by_date(long)
    numeric = [column for column in long.columns if column not in ("DEMAND_DATE", "DISH", "DEMAND")]
    if len(wide) != 760 or len(numeric) != 45 or [len(splits[k]) for k in ("train", "validation", "test")] != [3724, 798, 798]:
        raise ValueError("Data or features differ from PR #2's experiment")
    train, validation, test = (splits[name] for name in ("train", "validation", "test"))
    for name, expected in (("validation", validation), ("test", test)):
        if set(train["DEMAND_DATE"]).intersection(expected["DEMAND_DATE"]):
            raise ValueError(f"Train and {name} dates overlap")

    selected = {}
    cv_records = []
    for name in ("Random Forest", "XGBoost"):
        print(f"Tuning {name} on training dates only ({candidates} candidates, 3 folds)...", flush=True)
        params, records = tune(name, train, numeric, candidates)
        selected[name] = params
        cv_records.extend(records)
        print(f"Selected {name}: {params}", flush=True)

    models = {}
    x_train = train[["DISH", *numeric]]
    for name in ("Ridge", "Random Forest", "XGBoost"):
        model = make_model(name, numeric, selected.get(name))
        model.fit(x_train, train["DEMAND"])
        models[name] = model

    def predict(frame: pd.DataFrame) -> dict[str, np.ndarray]:
        x = frame[["DISH", *numeric]]
        return {
            "Naive T1": frame["DEMAND_T1"].to_numpy(),
            "Seasonal T7": frame["DEMAND_T7"].to_numpy(),
            **{name: model.predict(x) for name, model in models.items()},
        }

    validation_rows, validation_predictions = evaluate("validation", validation, predict(validation))
    contenders = ("Ridge", "Random Forest", "XGBoost")
    validation_winner = min(
        (row for row in validation_rows if row["dish"] == "ALL" and row["model"] in contenders),
        key=lambda row: (row["MAE"], contenders.index(row["model"])),
    )["model"]
    # Record the decision before touching test predictions. All final fits use
    # only the original 3,724 training rows; none are refitted on validation.
    test_rows, test_predictions = evaluate("test", test, predict(test))
    all_metrics = validation_rows + test_rows
    all_predictions = [validation_predictions, test_predictions]

    output_dir.mkdir(parents=True, exist_ok=True)
    metric_table = pd.DataFrame(all_metrics)
    metric_table.to_csv(output_dir / "metrics.csv", index=False)
    pd.DataFrame(cv_records).to_csv(output_dir / "cv_results.csv", index=False)
    pd.concat(all_predictions, ignore_index=True).to_csv(output_dir / "predictions.csv", index=False)
    metadata = {
        "source": str(raw_path), "source_sha256": hashlib.sha256(raw_path.read_bytes()).hexdigest(),
        "seed": SEED, "candidates_per_tree_model": candidates,
        "validation_winner_by_overall_mae": validation_winner,
        "features": ["DISH", *numeric], "selected_parameters": selected,
        "folds": [
            {"fit_dates": int(len(np.unique(train.iloc[fit_idx]["DEMAND_DATE"]))),
             "score_dates": int(len(np.unique(train.iloc[score_idx]["DEMAND_DATE"]))),
             "fit_last": str(train.iloc[fit_idx]["DEMAND_DATE"].max().date()),
             "score_first": str(train.iloc[score_idx]["DEMAND_DATE"].min().date())}
            for fit_idx, score_idx in expanding_date_folds(train)
        ],
        "splits": {
            name: {"first": str(frame["DEMAND_DATE"].min().date()),
                   "last": str(frame["DEMAND_DATE"].max().date()),
                   "dates": int(frame["DEMAND_DATE"].nunique()), "observations": len(frame)}
            for name, frame in splits.items()
        },
        "lag_audit": audit,
        "versions": {"python": platform.python_version(), "pandas": pd.__version__,
                     "numpy": np.__version__, "scikit_learn": sklearn.__version__,
                     "xgboost": xgboost.__version__},
    }
    (output_dir / "run.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(metric_table[metric_table["dish"] == "ALL"].to_string(index=False))
    print(f"Saved results to {output_dir}")
    return metric_table


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw-data", type=Path, default=RAW_DATA)
    parser.add_argument("--output-dir", type=Path, default=OUTPUT_DIR)
    parser.add_argument("--candidates", type=int, default=20, help="Configurations per tree model; default 20")
    args = parser.parse_args()
    if args.candidates < 1:
        parser.error("--candidates must be positive")
    run(args.raw_data, args.output_dir, args.candidates)


if __name__ == "__main__":
    main()
