from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from pathlib import Path
import os

import joblib
import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestRegressor
from sklearn.linear_model import Ridge
from sklearn.metrics import mean_absolute_error
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from xgboost import XGBRegressor

from .schemas import ForecastRequest, ForecastResponse, ItemForecast, ValidationResponse


DAILY_SHEET = "Daily Summary"
ITEM_SHEET = "Daily Item Sales"
DAILY_REQUIRED = {"Date", "Status", "Public Holiday", "Weather", "Orders", "Revenue (S$)"}
ITEM_REQUIRED = {"Date", "Item", "Qty Sold", "Revenue (S$)"}
CATEGORICAL = ["day_name", "weather"]
NUMERIC = [
    "is_open", "is_holiday", "month", "revenue_lag_1", "revenue_lag_7",
    "revenue_mean_7", "revenue_mean_28", "orders_lag_1", "orders_mean_7",
]
FEATURES = [*CATEGORICAL, *NUMERIC]


@dataclass
class TrainedBundle:
    model_name: str
    model: Pipeline | None
    history: pd.DataFrame
    items: pd.DataFrame
    mae: float
    wape: float
    residual_std: float
    artifact_path: str


def _read_excel(path: Path) -> tuple[pd.DataFrame, pd.DataFrame, list[str]]:
    workbook = pd.ExcelFile(path, engine="openpyxl")
    sheets = workbook.sheet_names
    if DAILY_SHEET not in sheets:
        raise ValueError(f"Missing required sheet: {DAILY_SHEET}")
    daily = pd.read_excel(workbook, sheet_name=DAILY_SHEET)
    missing = DAILY_REQUIRED - set(daily.columns)
    if missing:
        raise ValueError(f"{DAILY_SHEET} is missing columns: {', '.join(sorted(missing))}")
    items = pd.DataFrame()
    if ITEM_SHEET in sheets:
        items = pd.read_excel(workbook, sheet_name=ITEM_SHEET)
        item_missing = ITEM_REQUIRED - set(items.columns)
        if item_missing:
            raise ValueError(f"{ITEM_SHEET} is missing columns: {', '.join(sorted(item_missing))}")
    return daily, items, sheets


def _clean_daily(frame: pd.DataFrame) -> pd.DataFrame:
    data = frame[list(DAILY_REQUIRED)].copy()
    data = data.rename(columns={
        "Date": "date", "Status": "status", "Public Holiday": "public_holiday",
        "Weather": "weather", "Orders": "orders", "Revenue (S$)": "revenue",
    })
    data["date"] = pd.to_datetime(data["date"], errors="coerce")
    data["orders"] = pd.to_numeric(data["orders"], errors="coerce")
    data["revenue"] = pd.to_numeric(data["revenue"], errors="coerce")
    if data[["date", "orders", "revenue"]].isna().any().any():
        raise ValueError("Daily Summary contains invalid dates, orders, or revenue values")
    if (data[["orders", "revenue"]] < 0).any().any():
        raise ValueError("Orders and revenue must not be negative")
    if data["date"].duplicated().any():
        raise ValueError("Daily Summary contains duplicate dates")
    data["is_open"] = data["status"].astype(str).str.lower().eq("open").astype(int)
    data["is_holiday"] = data["public_holiday"].fillna("").astype(str).str.strip().ne("").astype(int)
    data["weather"] = data["weather"].replace("-", "Unknown").fillna("Unknown").astype(str)
    return data.sort_values("date").reset_index(drop=True)


def validate_workbook(path: Path) -> ValidationResponse:
    errors: list[str] = []
    sheets: list[str] = []
    try:
        daily, items, sheets = _read_excel(path)
        cleaned = _clean_daily(daily)
        if len(cleaned) < 60:
            errors.append("At least 60 daily records are required for time-based validation")
        if cleaned["date"].max() - cleaned["date"].min() < pd.Timedelta(days=60):
            errors.append("The source must cover at least 60 calendar days")
        item_rows = len(items.dropna(how="all")) if not items.empty else 0
        return ValidationResponse(
            valid=not errors,
            date_start=cleaned["date"].min().date(),
            date_end=cleaned["date"].max().date(),
            row_count=len(cleaned) + item_rows,
            sheets=[sheet for sheet in sheets if sheet in {DAILY_SHEET, ITEM_SHEET}],
            errors=errors,
        )
    except Exception as exception:
        return ValidationResponse(valid=False, sheets=sheets, errors=[str(exception)])


def _feature_frame(history: pd.DataFrame) -> pd.DataFrame:
    data = history.copy()
    data["day_name"] = data["date"].dt.day_name()
    data["month"] = data["date"].dt.month
    data["revenue_lag_1"] = data["revenue"].shift(1)
    data["revenue_lag_7"] = data["revenue"].shift(7)
    data["revenue_mean_7"] = data["revenue"].shift(1).rolling(7, min_periods=4).mean()
    data["revenue_mean_28"] = data["revenue"].shift(1).rolling(28, min_periods=7).mean()
    data["orders_lag_1"] = data["orders"].shift(1)
    data["orders_mean_7"] = data["orders"].shift(1).rolling(7, min_periods=4).mean()
    return data.dropna(subset=NUMERIC).reset_index(drop=True)


def _make_model(name: str) -> Pipeline:
    preprocess = ColumnTransformer([
        ("categorical", OneHotEncoder(handle_unknown="ignore"), CATEGORICAL),
        ("numeric", StandardScaler() if name == "Ridge" else "passthrough", NUMERIC),
    ])
    if name == "Ridge":
        estimator = Ridge(alpha=8.0)
    elif name == "Random Forest":
        estimator = RandomForestRegressor(n_estimators=250, max_depth=8, min_samples_leaf=3, random_state=42, n_jobs=1)
    elif name == "XGBoost":
        estimator = XGBRegressor(n_estimators=250, max_depth=3, learning_rate=.035, subsample=.85, colsample_bytree=.85, objective="reg:squarederror", random_state=42, n_jobs=1)
    else:
        raise ValueError(name)
    return Pipeline([("preprocess", preprocess), ("model", estimator)])


def _load_sources(paths: list[Path]) -> tuple[pd.DataFrame, pd.DataFrame]:
    daily_parts, item_parts = [], []
    for path in paths:
        validation = validate_workbook(path)
        if not validation.valid:
            raise ValueError(f"{path.name}: {'; '.join(validation.errors)}")
        daily, items, _ = _read_excel(path)
        daily_parts.append(_clean_daily(daily))
        if not items.empty:
            selected = items[list(ITEM_REQUIRED)].copy()
            selected = selected.rename(columns={"Date": "date", "Item": "item", "Qty Sold": "quantity", "Revenue (S$)": "revenue"})
            selected["date"] = pd.to_datetime(selected["date"], errors="coerce")
            item_parts.append(selected.dropna(subset=["date", "item", "quantity", "revenue"]))
    history = pd.concat(daily_parts, ignore_index=True).sort_values("date").drop_duplicates("date", keep="last").reset_index(drop=True)
    items = pd.concat(item_parts, ignore_index=True) if item_parts else pd.DataFrame(columns=["date", "item", "quantity", "revenue"])
    return history, items


def train_models(paths: list[Path], run_id: str, model_root: Path) -> TrainedBundle:
    history, items = _load_sources(paths)
    featured = _feature_frame(history)
    split = max(45, int(len(featured) * .8))
    if len(featured) - split < 14:
        split = len(featured) - 14
    train, validation = featured.iloc[:split], featured.iloc[split:]
    if train.empty or validation.empty:
        raise ValueError("Not enough chronological data for training and validation")

    results: dict[str, tuple[float, float, np.ndarray, Pipeline | None]] = {}
    baseline = validation["revenue_lag_7"].to_numpy()
    actual = validation["revenue"].to_numpy()
    denominator = max(float(np.abs(actual).sum()), 1.0)
    results["Seasonal baseline"] = (
        float(mean_absolute_error(actual, baseline)), float(np.abs(actual - baseline).sum() / denominator * 100), baseline, None
    )
    for name in ("Ridge", "Random Forest", "XGBoost"):
        model = _make_model(name)
        model.fit(train[FEATURES], train["revenue"])
        predicted = np.maximum(0, model.predict(validation[FEATURES]))
        results[name] = (
            float(mean_absolute_error(actual, predicted)),
            float(np.abs(actual - predicted).sum() / denominator * 100),
            predicted,
            model,
        )
    winner, (mae, wape, predicted, _) = min(results.items(), key=lambda item: item[1][0])
    final_model = None if winner == "Seasonal baseline" else _make_model(winner)
    if final_model is not None:
        final_model.fit(featured[FEATURES], featured["revenue"])
    residual_std = float(np.std(actual - predicted)) or mae

    model_root.mkdir(parents=True, exist_ok=True)
    artifact = model_root / f"{run_id}.joblib"
    bundle = TrainedBundle(winner, final_model, history, items, mae, wape, residual_std, str(artifact.resolve()))
    joblib.dump(bundle, artifact)
    joblib.dump(bundle, model_root / "active.joblib")
    return bundle


def load_active(model_root: Path) -> TrainedBundle | None:
    active = model_root / "active.joblib"
    return joblib.load(active) if active.exists() else None


def _tomorrow_features(bundle: TrainedBundle, request: ForecastRequest) -> pd.DataFrame:
    history = bundle.history
    revenue = history["revenue"]
    orders = history["orders"]
    return pd.DataFrame([{
        "day_name": pd.Timestamp(request.date).day_name(),
        "weather": request.weather,
        "is_open": int(request.stall_open),
        "is_holiday": int(request.holiday),
        "month": request.date.month,
        "revenue_lag_1": float(revenue.iloc[-1]),
        "revenue_lag_7": float(revenue.iloc[-7]),
        "revenue_mean_7": float(revenue.iloc[-7:].mean()),
        "revenue_mean_28": float(revenue.iloc[-28:].mean()),
        "orders_lag_1": float(orders.iloc[-1]),
        "orders_mean_7": float(orders.iloc[-7:].mean()),
    }])


def _item_forecasts(bundle: TrainedBundle, target: date, revenue_ratio: float) -> list[ItemForecast]:
    if bundle.items.empty:
        return []
    items = bundle.items.copy()
    items["weekday"] = items["date"].dt.weekday
    matching = items[items["weekday"] == target.weekday()]
    if matching.empty:
        matching = items
    recent_dates = sorted(matching["date"].unique())[-6:]
    recent = matching[matching["date"].isin(recent_dates)]
    grouped = recent.groupby("item", as_index=False).agg(quantity=("quantity", "mean"), revenue=("revenue", "mean"))
    grouped["quantity"] = (grouped["quantity"] * revenue_ratio).round().clip(lower=0)
    grouped["revenue"] = (grouped["revenue"] * revenue_ratio).clip(lower=0)
    grouped = grouped.sort_values("revenue", ascending=False).head(10)
    return [ItemForecast(item=row.item, quantity=int(row.quantity), revenue=round(float(row.revenue), 2)) for row in grouped.itertuples()]


def predict(bundle: TrainedBundle, request: ForecastRequest) -> ForecastResponse:
    history = bundle.history
    recent_average = float(history["revenue"].iloc[-7:].mean())
    if not request.stall_open:
        estimate = 0.0
    else:
        row = _tomorrow_features(bundle, request)
        estimate = float(row["revenue_lag_7"].iloc[0]) if bundle.model is None else float(bundle.model.predict(row[FEATURES])[0])
        estimate = max(0.0, estimate)
    average_ticket = float(history.loc[history["orders"] > 0, "revenue"].sum() / history.loc[history["orders"] > 0, "orders"].sum())
    orders = 0 if not request.stall_open else max(0, round(estimate / average_ticket))
    spread = max(bundle.residual_std * 1.28, bundle.mae)
    ratio = estimate / recent_average if recent_average else 1.0
    return ForecastResponse(
        date=request.date,
        predicted_revenue=round(estimate, 2),
        predicted_orders=orders,
        lower_bound=round(max(0.0, estimate - spread), 2),
        upper_bound=round(estimate + spread, 2),
        recent_average=round(recent_average, 2),
        model_name=bundle.model_name,
        training_cutoff=history["date"].max().date(),
        item_forecasts=_item_forecasts(bundle, request.date, ratio) if request.stall_open else [],
    )


def configured_model_root() -> Path:
    return Path(os.getenv("MODEL_STORAGE_PATH", ".local-data/models")).resolve()
