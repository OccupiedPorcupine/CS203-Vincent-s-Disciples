"""Generate reports/data_quality.md from the tidy panel.

Everything here is computed on the raw ragged panel. No filling, no
interpolation, no smoothing: month-on-month moves are computed only where BOTH
the current and prior month were actually observed, so a move is never
manufactured across a gap.
"""

from __future__ import annotations

import logging
from pathlib import Path

import pandas as pd

from src.data.categories import CATEGORY_ORDER
from src.data.singstat import PROJECT_ROOT, load_prices

log = logging.getLogger("quality")

REPORT_PATH = PROJECT_ROOT / "reports" / "data_quality.md"

# An item needs at least this share of the last 10 years observed to count as
# "continuous"; and at least this many observations overall to be modelable.
CONTINUOUS_TOLERANCE = 0  # interior gaps allowed within the 10y window
SPARSE_MIN_OBS = 60       # < 5 years of monthly data
BASKET_BREAK = pd.Timestamp("2024-01-31")


def per_item_stats(df: pd.DataFrame) -> pd.DataFrame:
    """One row per item with coverage and volatility diagnostics."""
    out: list[dict[str, object]] = []
    for item_id, g in df.groupby("item_id", sort=False):
        g = g.sort_values("date")
        obs = g[g["price_sgd"].notna()]
        if obs.empty:
            continue

        first, last = obs["date"].iloc[0], obs["date"].iloc[-1]
        span = g[(g["date"] >= first) & (g["date"] <= last)]
        interior_gaps = int(span["price_sgd"].isna().sum())

        # MoM % change only where the immediately preceding month was observed.
        s = g.set_index("date")["price_sgd"]
        pct = s.pct_change() * 100.0
        pct = pct.where(s.notna() & s.shift(1).notna())
        if pct.notna().any():
            idx = pct.abs().idxmax()
            biggest, biggest_when = float(pct.loc[idx]), idx
        else:
            biggest, biggest_when = float("nan"), pd.NaT

        out.append(
            {
                "item_id": item_id,
                "item_name": g["item_name"].iloc[0],
                "unit": g["unit"].iloc[0],
                "category": g["category"].iloc[0],
                "first_date": first,
                "last_date": last,
                "n_obs": int(len(obs)),
                "interior_gaps": interior_gaps,
                "pct_missing": 100.0 * g["price_sgd"].isna().sum() / len(g),
                "min_price": float(obs["price_sgd"].min()),
                "max_price": float(obs["price_sgd"].max()),
                "largest_mom_pct": biggest,
                "largest_mom_when": biggest_when,
            }
        )
    return pd.DataFrame(out).sort_values(["category", "item_name"]).reset_index(drop=True)


def all_mom_moves(df: pd.DataFrame) -> pd.DataFrame:
    """Every valid month-on-month move in the panel, gaps excluded."""
    frames = []
    for item_id, g in df.groupby("item_id", sort=False):
        g = g.sort_values("date").set_index("date")
        s = g["price_sgd"]
        pct = (s.pct_change() * 100.0).where(s.notna() & s.shift(1).notna())
        frames.append(
            pd.DataFrame(
                {
                    "date": pct.index,
                    "item_id": item_id,
                    "item_name": g["item_name"].iloc[0],
                    "category": g["category"].iloc[0],
                    "unit": g["unit"].iloc[0],
                    "prev_price": s.shift(1).to_numpy(),
                    "price": s.to_numpy(),
                    "mom_pct": pct.to_numpy(),
                }
            )
        )
    moves = pd.concat(frames, ignore_index=True)
    return moves[moves["mom_pct"].notna()].reset_index(drop=True)


def coverage_last_n_years(df: pd.DataFrame, stats: pd.DataFrame, years: int = 10) -> tuple[pd.DataFrame, pd.Timestamp]:
    """Classify items by coverage over the trailing ``years`` window."""
    end = df["date"].max()
    start = (end - pd.DateOffset(years=years)) + pd.offsets.MonthEnd(0)
    win = df[df["date"] >= start]
    n_months = win["date"].nunique()

    rows = []
    for item_id, g in win.groupby("item_id", sort=False):
        n_obs = int(g["price_sgd"].notna().sum())
        rows.append(
            {
                "item_id": item_id,
                "obs_in_window": n_obs,
                "months_in_window": n_months,
                "missing_in_window": n_months - n_obs,
                "continuous": n_obs == n_months,
            }
        )
    cov = pd.DataFrame(rows).merge(stats[["item_id", "item_name", "category"]], on="item_id")
    return cov, start


def _fmt_date(ts: object) -> str:
    return "-" if pd.isna(ts) else pd.Timestamp(ts).strftime("%Y-%m")


def build_report(df: pd.DataFrame) -> str:
    stats = per_item_stats(df)
    moves = all_mom_moves(df)
    cov, win_start = coverage_last_n_years(df, stats, years=10)

    n_items = len(stats)
    n_months = df["date"].nunique()
    observed = int(df["price_sgd"].notna().sum())
    continuous = cov[cov["continuous"]]
    sparse = stats[stats["n_obs"] < SPARSE_MIN_OBS]
    gappy = stats[stats["interior_gaps"] > 0]

    L: list[str] = []
    a = L.append
    a("# Data Quality Report — SingStat Average Retail Prices (Monthly)")
    a("")
    a(f"Source table `M213761`, generated from `data/processed/retail_prices.parquet`.")
    a("")
    a("| | |")
    a("|---|---|")
    a(f"| Items | {n_items} |")
    a(f"| Months | {n_months} ({df['date'].min():%Y-%m} → {df['date'].max():%Y-%m}) |")
    a(f"| Panel cells | {len(df):,} |")
    a(f"| Observed | {observed:,} ({observed / len(df):.1%}) |")
    a(f"| Missing | {len(df) - observed:,} ({1 - observed / len(df):.1%}) |")
    a(f"| Items with interior gaps | {len(gappy)} |")
    a("")
    a("> **The panel is ragged and has not been filled.** Missing months are")
    a("> absent from the API payload entirely (there are no `na`/`-` tokens);")
    a("> they are reindexed here into explicit `NaN` rows. Nothing is")
    a("> forward-filled or interpolated anywhere in this pipeline.")
    a("")
    a("> **Basket break:** SingStat rebased to the 2024 CPI basket at")
    a(f"> **{BASKET_BREAK:%Y-%m}**. Per the API footnote, prices either side are")
    a("> *not* a pure price comparison — the brand/outlet sample changed. Moves")
    a("> at that boundary are flagged ⚠ in the tables below and should be")
    a("> excluded from any volatility estimate.")
    a("")

    # ---- Section 1: per-item table
    a("## 1. Per-item coverage")
    a("")
    a("| Item | Cat | Unit | First | Last | Obs | Gaps | Miss % | Min | Max | Largest MoM | When |")
    a("|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|")
    for r in stats.itertuples():
        flag = " ⚠" if not pd.isna(r.largest_mom_when) and pd.Timestamp(r.largest_mom_when) == BASKET_BREAK else ""
        mom = "-" if pd.isna(r.largest_mom_pct) else f"{r.largest_mom_pct:+.1f}%"
        a(
            f"| {r.item_name} | {r.category} | {r.unit} | {_fmt_date(r.first_date)} | "
            f"{_fmt_date(r.last_date)} | {r.n_obs} | {r.interior_gaps} | "
            f"{r.pct_missing:.1f} | {r.min_price:.2f} | {r.max_price:.2f} | "
            f"{mom} | {_fmt_date(r.largest_mom_when)}{flag} |"
        )
    a("")

    # ---- Section 2: the three questions
    a("## 2. Summary")
    a("")
    a(f"### Q1. How many items have continuous coverage over the last 10 years?")
    a("")
    a(
        f"**{len(continuous)} of {n_items} items** are continuous across the trailing "
        f"10-year window ({win_start:%Y-%m} → {df['date'].max():%Y-%m}, "
        f"{cov['months_in_window'].iloc[0]} months), with zero missing months."
    )
    a("")
    incomplete = cov[~cov["continuous"]].sort_values("missing_in_window", ascending=False)
    if not incomplete.empty:
        a(f"The remaining **{len(incomplete)}** have at least one missing month in that window:")
        a("")
        a("| Item | Category | Observed | Missing |")
        a("|---|---|---:|---:|")
        for r in incomplete.itertuples():
            a(f"| {r.item_name} | {r.category} | {r.obs_in_window}/{r.months_in_window} | {r.missing_in_window} |")
        a("")

    a("### Q2. Which items are too sparse to model?")
    a("")
    if sparse.empty:
        a(f"No item falls below the {SPARSE_MIN_OBS}-observation threshold.")
    else:
        a(
            f"**{len(sparse)} items** have fewer than {SPARSE_MIN_OBS} observations "
            f"(~5 years of monthly data) and cannot support a seasonal model — "
            f"they lack the repeated annual cycles needed to identify seasonality:"
        )
        a("")
        a("| Item | Category | Obs | First | Last | Why |")
        a("|---|---|---:|---|---|---|")
        for r in sparse.itertuples():
            why = "starts at 2024 basket rebase" if pd.Timestamp(r.first_date) >= BASKET_BREAK else "short history"
            a(f"| {r.item_name} | {r.category} | {r.n_obs} | {_fmt_date(r.first_date)} | {_fmt_date(r.last_date)} | {why} |")
        a("")
    disc = stats[stats["last_date"] < df["date"].max()]
    if not disc.empty:
        a(
            f"Separately, **{len(disc)} item(s)** are discontinued — they stop before "
            f"the panel end ({df['date'].max():%Y-%m}) and cannot be forecast forward at all:"
        )
        a("")
        a("| Item | Category | Last observed | Obs |")
        a("|---|---|---|---:|")
        for r in disc.itertuples():
            a(f"| {r.item_name} | {r.category} | {_fmt_date(r.last_date)} | {r.n_obs} |")
        a("")

    a("### Q3. The 20 largest month-on-month moves in the panel")
    a("")
    top = moves.reindex(moves["mom_pct"].abs().sort_values(ascending=False).index).head(20)
    a("| # | Date | Item | Category | From | To | Move |")
    a("|---:|---|---|---|---:|---:|---:|")
    for i, r in enumerate(top.itertuples(), 1):
        flag = " ⚠" if pd.Timestamp(r.date) == BASKET_BREAK else ""
        a(
            f"| {i} | {_fmt_date(r.date)}{flag} | {r.item_name} | {r.category} | "
            f"{r.prev_price:.2f} | {r.price:.2f} | {r.mom_pct:+.1f}% |"
        )
    a("")
    n_break = int((top["date"] == BASKET_BREAK).sum())
    if n_break:
        a(
            f"⚠ **{n_break} of the top 20 land exactly on {BASKET_BREAK:%Y-%m}**, the CPI "
            f"basket rebase. These are measurement artefacts from a changed brand/outlet "
            f"sample, not market price moves. Treat them as a level shift and exclude "
            f"them when fitting."
        )
        a("")

    # ---- Section 3: category rollup
    a("## 3. By category")
    a("")
    a("| Category | Items | Observed | Missing % | Median MoM |σ| |")
    a("|---|---:|---:|---:|---:|")
    for cat in CATEGORY_ORDER:
        sub = df[df["category"] == cat]
        if sub.empty:
            continue
        mv = moves[moves["category"] == cat]
        obs_c = int(sub["price_sgd"].notna().sum())
        a(
            f"| {cat} | {sub['item_id'].nunique()} | {obs_c:,} | "
            f"{100 * (1 - obs_c / len(sub)):.1f} | {mv['mom_pct'].std():.2f}% |"
        )
    a("")
    a("---")
    a(f"_Generated by `src/reporting/quality.py`. Regenerate: `python -m src.reporting.quality`._")
    return "\n".join(L)


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)-7s %(name)s: %(message)s")
    df = load_prices()
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(build_report(df), encoding="utf-8")
    print(f"wrote {REPORT_PATH.relative_to(PROJECT_ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
