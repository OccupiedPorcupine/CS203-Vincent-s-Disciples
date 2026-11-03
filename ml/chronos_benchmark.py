"""
Benchmark pretrained forecasting models (Chronos-2, Chronos-Bolt tiny) against the app's
baseline (issue #87).

    pip install -r ml/requirements.txt -r ml/requirements-chronos.txt
    python -m ml.chronos_benchmark                       # everything, about an hour on a laptop CPU
    python -m ml.chronos_benchmark --parts restaurant resources --models chronos-bolt-tiny

Three parts:
- simulator: the closed-loop prep backtest from #85 (ml/prep_backtest.py): leftovers,
  sell-outs and profit against the true demand of simulated hawker stalls.
- restaurant: one-day-ahead forecasts of the Stuttgart restaurant's 7 dishes over the test
  dates of ml/model_comparison.py, from recorded sales, as the trained models there were scored.
- resources: load time, memory and speed on this machine's CPU.

Writes CSVs to ml/artifacts/chronos/ (ignored by Git) and prints the tables.
"""

from __future__ import annotations

# PyTorch first: on macOS, loading XGBoost's or CatBoost's OpenMP before PyTorch's (through
# ml.model_comparison) makes the first forecast crash with a segmentation fault.
import torch  # noqa: F401  isort: skip

import argparse
import resource
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

from ml.model_comparison import DISHES, load_dish_days, split_by_date
from ml.src.simulation.backtest import (
    AppBaseline,
    Forecast,
    History,
    SoldOutAwareBaseline,
    Target,
    forecast_all,
    run_backtest,
    summarise,
)
from ml.src.simulation.chronos_forecaster import MODELS, QUANTILE_LEVELS, ChronosForecaster, load_pipeline
from ml.src.simulation.simulator import simulate

OUTPUT_DIR = Path(__file__).resolve().parent / "artifacts" / "chronos"


def peak_memory_mb() -> float:
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return peak / 1e6 if sys.platform == "darwin" else peak / 1e3  # bytes on macOS, KiB on Linux


def forecasters_for(models: list[str], calendar: pd.DataFrame, pipelines: dict, sell_out_variants: bool) -> list:
    chosen = []
    for model in models:
        covariates = model == "chronos-2"
        chosen.append(ChronosForecaster(model, pipelines[model], calendar, use_covariates=covariates))
        if sell_out_variants:
            chosen.append(ChronosForecaster(f"{model} + sell-out times", pipelines[model], calendar,
                                            use_covariates=covariates, adjust_sold_out=True))
    return chosen


# ---- Simulator ------------------------------------------------------------


def simulator_part(models: list[str], pipelines: dict, stalls: int, seed: int) -> pd.DataFrame:
    sales = simulate(stalls, seed=seed)
    calendar = sales[["date", "holiday", "rainfall_mm"]].drop_duplicates("date")
    forecasters = [AppBaseline(), SoldOutAwareBaseline(), *forecasters_for(models, calendar, pipelines, True)]
    started = time.time()
    summary = summarise(run_backtest(sales, forecasters))
    print(f"Simulator backtest took {time.time() - started:.0f} s")
    return summary


# ---- Stuttgart restaurant ---------------------------------------------------


class LastValue:
    name = "Naive: previous recorded day"

    def forecast(self, history: History, target: Target) -> Forecast | None:
        return None if len(history.sold) == 0 else Forecast(float(history.sold[-1]), lambda p, v=float(history.sold[-1]): v)


def pinball(actual: np.ndarray, quantiles: np.ndarray) -> float:
    """Mean quantile loss over QUANTILE_LEVELS; quantiles has one row per forecast."""
    levels = np.array(QUANTILE_LEVELS)
    diff = actual[:, None] - quantiles
    return float(np.mean(np.maximum(levels * diff, (levels - 1) * diff)))


def restaurant_part(models: list[str], pipelines: dict) -> pd.DataFrame:
    wide, long = load_dish_days()
    test_dates = np.sort(split_by_date(long)["test"]["DEMAND_DATE"].unique())
    calendar = pd.DataFrame({"date": wide["DEMAND_DATE"],
                             "holiday": np.where(wide["ISHOLIDAY"] == 1, "holiday", ""),
                             "rainfall_mm": wide["PRECIPITATION"]})
    dates = wide["DEMAND_DATE"].to_numpy().astype("datetime64[D]")
    no_sell_outs = np.full(len(dates), np.nan)

    cases = []  # (dish, history, target, actual)
    for day in test_dates:
        row = wide[wide["DEMAND_DATE"] == day].iloc[0]
        before = dates < np.datetime64(day, "D")
        target = Target(np.datetime64(day, "D"), "holiday" if row["ISHOLIDAY"] == 1 else "", float(row["PRECIPITATION"]))
        for dish in DISHES:
            history = History(dates[before], wide[dish].to_numpy(dtype=float)[before], no_sell_outs[before])
            cases.append((dish, history, target, float(row[dish])))

    actual = np.array([case[3] for case in cases])
    rows = []
    for forecaster in [LastValue(), AppBaseline(), *forecasters_for(models, calendar, pipelines, False)]:
        started = time.time()
        forecasts = forecast_all(forecaster, [case[1] for case in cases], [case[2] for case in cases])
        point = np.array([forecast.point for forecast in forecasts])
        quantiles = np.array([[forecast.quantile(level) for level in QUANTILE_LEVELS] for forecast in forecasts])
        low, high = quantiles[:, QUANTILE_LEVELS.index(0.1)], quantiles[:, QUANTILE_LEVELS.index(0.9)]
        rows.append({
            "model": forecaster.name,
            "observations": len(actual),
            "MAE": float(np.mean(np.abs(actual - point))),
            "RMSE": float(np.sqrt(np.mean((actual - point) ** 2))),
            "WAPE_pct": float(np.abs(actual - point).sum() / actual.sum() * 100),
            "pinball_loss": pinball(actual, quantiles) if forecaster.name != LastValue.name else np.nan,
            "range_80_coverage_pct": float(np.mean((actual >= low) & (actual <= high)) * 100)
            if forecaster.name != LastValue.name else np.nan,
            "seconds": time.time() - started,
        })
    print(f"Restaurant: {len(test_dates)} test dates x {len(DISHES)} dishes, "
          f"{pd.Timestamp(test_dates[0]).date()} to {pd.Timestamp(test_dates[-1]).date()}")
    return pd.DataFrame(rows)


# ---- Resources --------------------------------------------------------------


def resources_part(models: list[str], output_dir: Path) -> pd.DataFrame:
    """Each model in a fresh process, so one model's memory doesn't count towards the next."""
    frames = []
    for model in models:
        csv = output_dir / f"resources-{model}.csv"
        subprocess.run([sys.executable, "-m", "ml.chronos_benchmark", "--measure", model, "--output-dir", str(output_dir)],
                       check=True)
        frames.append(pd.read_csv(csv))
        csv.unlink()
    return pd.concat(frames, ignore_index=True)


def measure(model: str) -> pd.DataFrame:
    rng = np.random.default_rng(0)
    calendar = pd.DataFrame({"date": pd.date_range("2025-01-01", "2026-12-31"), "holiday": "", "rainfall_mm": 0.0})
    dates = np.arange(np.datetime64("2025-11-01"), np.datetime64("2026-11-01"), dtype="datetime64[D]")
    target = Target(np.datetime64("2026-11-01"), "", 0.0)
    before = peak_memory_mb()
    started = time.time()
    pipeline = load_pipeline(MODELS[model])
    row = {"model": model, "load_seconds": time.time() - started, "process_memory_before_mb": before,
           "peak_memory_after_load_mb": peak_memory_mb()}
    forecaster = ChronosForecaster(model, pipeline, calendar, use_covariates=model == "chronos-2")
    forecaster.forecast_batch([History(dates, np.full(len(dates), 50.0), np.full(len(dates), np.nan))], [target])  # warm-up
    for count in (5, 100, 400):  # one stall's menu; many stalls at once; the simulator's daily batch
        histories = [History(dates, 50 + rng.normal(0, 8, len(dates)), np.full(len(dates), np.nan)) for _ in range(count)]
        started = time.time()
        forecaster.forecast_batch(histories, [target] * count)
        row[f"seconds_for_{count}_dishes"] = time.time() - started
    row["peak_memory_mb"] = peak_memory_mb()
    return pd.DataFrame([row])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("--parts", nargs="+", default=["resources", "restaurant", "simulator"],
                        choices=["resources", "restaurant", "simulator"])
    parser.add_argument("--models", nargs="+", default=list(MODELS), choices=list(MODELS))
    parser.add_argument("--stalls", type=int, default=24)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--threads", type=int, default=4, help="PyTorch CPU threads")
    parser.add_argument("--output-dir", type=Path, default=OUTPUT_DIR)
    parser.add_argument("--measure", choices=list(MODELS), help=argparse.SUPPRESS)  # used by resources_part
    args = parser.parse_args()

    torch.set_num_threads(args.threads)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    pd.set_option("display.width", 250)

    if args.measure:
        measure(args.measure).to_csv(args.output_dir / f"resources-{args.measure}.csv", index=False)
        return

    if "resources" in args.parts:
        usage = resources_part(args.models, args.output_dir)
        usage.to_csv(args.output_dir / "resources.csv", index=False)
        print(usage.round(2).to_string(index=False), "\n")
    pipelines = {model: load_pipeline(MODELS[model]) for model in args.models
                 if {"restaurant", "simulator"} & set(args.parts)}
    if "restaurant" in args.parts:
        restaurant = restaurant_part(args.models, pipelines)
        restaurant.to_csv(args.output_dir / "restaurant.csv", index=False)
        print(restaurant.round(3).to_string(index=False), "\n")
    if "simulator" in args.parts:
        summary = simulator_part(args.models, pipelines, args.stalls, args.seed)
        summary.to_csv(args.output_dir / "simulator.csv", index=False)
        print(summary.round(1).to_string(index=False))
    print(f"\nWritten to {args.output_dir}")


if __name__ == "__main__":
    main()
