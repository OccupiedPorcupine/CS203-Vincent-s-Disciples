"""
Simulate hawker stalls and compare prep policies on waste, sell-outs and profit (issue #85).

    python -m ml.prep_backtest                       # 24 stalls, Jan 2024 - Jun 2026
    python -m ml.prep_backtest --stalls 8 --eval-days 60

Writes the simulated sales, every evaluated day and the ingredient totals to
ml/artifacts/simulation/ (ignored by Git), and prints the comparison table. The data is
simulated: read ml/src/simulation/assumptions.py before quoting any number from it.
"""

from __future__ import annotations

import argparse
from datetime import date
from pathlib import Path

from ml.src.simulation.backtest import (
    AppBaseline,
    SoldOutAwareBaseline,
    ingredient_totals,
    run_backtest,
    summarise,
)
from ml.src.simulation.simulator import simulate

DEFAULT_OUTPUT_DIR = Path(__file__).resolve().parent / "artifacts" / "simulation"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("--stalls", type=int, default=24)
    parser.add_argument("--start", type=date.fromisoformat, default=date(2024, 1, 1))
    parser.add_argument("--end", type=date.fromisoformat, default=date(2026, 6, 30))
    parser.add_argument("--eval-days", type=int, default=182, help="days at the end that are scored")
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    args = parser.parse_args()

    sales = simulate(args.stalls, args.start, args.end, args.seed)
    result = run_backtest(sales, [AppBaseline(), SoldOutAwareBaseline()], eval_days=args.eval_days)
    summary = summarise(result)
    ingredients = ingredient_totals(result)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    sales.to_csv(args.output_dir / "simulated_sales.csv", index=False)
    result.to_csv(args.output_dir / "backtest_days.csv", index=False)
    summary.to_csv(args.output_dir / "summary.csv", index=False)
    ingredients.to_csv(args.output_dir / "ingredients.csv", index=False)

    open_days = sales[sales["open"]]
    print(f"{args.stalls} simulated stalls, {args.start} to {args.end}, seed {args.seed}: "
          f"{len(open_days):,} open dish-days, {100 * (open_days['sold_out_at'] != '').mean():.1f}% "
          f"sold out under the owners' own habits. Scored the last {args.eval_days} days.\n")
    print(summary.round(1).to_string(index=False))
    print(f"\nWritten to {args.output_dir}")


if __name__ == "__main__":
    main()
