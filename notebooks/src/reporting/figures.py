"""Render the two static figures into reports/figures/.

    small_multiples.png   one panel per item, faceted by category
    combined_indexed.png  all items rebased to 100, category medians overlaid

Neither figure fills gaps. Matplotlib breaks a line wherever the value is NaN,
so a missing month reads as a visible discontinuity, which is the point.
"""

from __future__ import annotations

import logging
import math
import textwrap
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.dates as mdates
import matplotlib.pyplot as plt
import pandas as pd
from matplotlib.gridspec import GridSpec
from matplotlib.lines import Line2D

from src.data.categories import CATEGORY_COLOURS, CATEGORY_ORDER
from src.data.singstat import PROJECT_ROOT, load_prices

log = logging.getLogger("figures")

FIG_DIR = PROJECT_ROOT / "reports" / "figures"
NCOLS = 8

# Known shocks, shaded on both figures. Mirrors SHOCK_BANDS in explorer.py.
SHOCKS: list[dict[str, str]] = [
    {"name": "COVID-19", "start": "2020-03-01", "end": "2021-12-31", "colour": "#6c757d"},
    {"name": "MY chicken export ban", "start": "2022-06-01", "end": "2022-10-31", "colour": "#d55e00"},
]


def _shade(ax: plt.Axes, alpha: float = 0.10) -> None:
    for s in SHOCKS:
        ax.axvspan(
            pd.Timestamp(s["start"]), pd.Timestamp(s["end"]),
            color=s["colour"], alpha=alpha, linewidth=0, zorder=0,
        )


def small_multiples(df: pd.DataFrame, path: Path) -> Path:
    """Grid of per-item line charts, grouped into category blocks."""
    cats = [c for c in CATEGORY_ORDER if c in set(df["category"])]
    blocks = {
        c: sorted(df[df["category"] == c]["item_id"].unique(),
                  key=lambda i: df.loc[df["item_id"] == i, "item_name"].iloc[0])
        for c in cats
    }
    # One header row + ceil(n/NCOLS) panel rows per category.
    rows_per_cat = {c: math.ceil(len(v) / NCOLS) for c, v in blocks.items()}
    total_rows = sum(rows_per_cat.values())

    fig = plt.figure(figsize=(NCOLS * 2.45, total_rows * 1.85 + 1.8))
    gs = GridSpec(
        total_rows, NCOLS, figure=fig,
        hspace=0.95, wspace=0.42, top=0.945, bottom=0.035, left=0.045, right=0.985,
    )

    xmin, xmax = df["date"].min(), df["date"].max()
    r = 0
    for cat in cats:
        items = blocks[cat]
        colour = CATEGORY_COLOURS[cat]
        for k, item_id in enumerate(items):
            ax = fig.add_subplot(gs[r + k // NCOLS, k % NCOLS])
            g = df[df["item_id"] == item_id].sort_values("date")
            _shade(ax)
            ax.plot(g["date"], g["price_sgd"], color=colour, linewidth=1.15, solid_capstyle="round")

            name = g["item_name"].iloc[0]
            unit = g["unit"].iloc[0]
            title = name if len(name) <= 26 else name[:24] + "…"
            ax.set_title(title, fontsize=7.2, pad=3.5, loc="left")
            ax.annotate(
                unit, xy=(0, 1.0), xycoords="axes fraction", xytext=(0, 10),
                textcoords="offset points", fontsize=5.4, color="#888", ha="left",
            )
            ax.set_xlim(xmin, xmax)
            ax.tick_params(labelsize=5.6, length=2, pad=1.5)
            ax.xaxis.set_major_locator(mdates.YearLocator(4))
            ax.xaxis.set_major_formatter(mdates.DateFormatter("%y"))
            ax.margins(y=0.14)
            for side in ("top", "right"):
                ax.spines[side].set_visible(False)
            for side in ("left", "bottom"):
                ax.spines[side].set_color("#cccccc")
            ax.grid(axis="y", color="#eeeeee", linewidth=0.5)
            ax.set_axisbelow(True)

            # Mark a late start so raggedness is visible, not just implied.
            first = g.loc[g["price_sgd"].notna(), "date"]
            if not first.empty and first.iloc[0] > xmin:
                ax.axvline(first.iloc[0], color="#cc0000", linewidth=0.7, linestyle=":", alpha=0.8)

        # Category label above the block's first row. Read the slot geometry
        # straight off the GridSpec: calling add_subplot on an occupied slot
        # stacks a new opaque axes on top and hides the panel underneath.
        pos = gs[r, 0].get_position(fig)
        fig.text(
            0.006, pos.y1 + 0.012, cat.replace("_", "/").upper(),
            fontsize=8.2, fontweight="bold", color=colour, va="bottom",
        )
        r += rows_per_cat[cat]

    fig.suptitle(
        "Singapore average retail prices by item, 2015–2026  ·  SingStat M213761",
        fontsize=13, fontweight="bold", x=0.045, ha="left", y=0.988,
    )
    fig.text(
        0.045, 0.966,
        "Independent y-axes (prices span orders of magnitude) · shared x-axis · "
        "gaps left unfilled · red dotted line = first observation "
        "· shaded: COVID-19, MY chicken export ban",
        fontsize=7.6, color="#666", ha="left",
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=150, facecolor="white")
    plt.close(fig)
    log.info("wrote %s", path)
    return path


def pick_base_date(df: pd.DataFrame) -> tuple[pd.Timestamp, int]:
    """Choose the rebase date: earliest month whose item coverage equals the
    long-run maximum, so the index keeps the most history at full width.

    Returns (base_date, n_items_covered).
    """
    counts = df[df["price_sgd"].notna()].groupby("date")["item_id"].nunique()
    # Ignore the 2024 rebase jump: use the modal long-run coverage level.
    plateau = int(counts.loc[:"2023-12-31"].max())
    base = counts.loc[:"2023-12-31"].idxmax()
    return pd.Timestamp(base), plateau


def combined_indexed(df: pd.DataFrame, path: Path) -> tuple[Path, pd.Timestamp, int]:
    """All items rebased to 100, thin lines by category, medians overlaid."""
    base, n_base = pick_base_date(df)

    frames = []
    for item_id, g in df.groupby("item_id", sort=False):
        g = g.sort_values("date").copy()
        at_base = g.loc[g["date"] == base, "price_sgd"]
        late = at_base.empty or pd.isna(at_base.iloc[0])
        if late:
            obs = g.loc[g["price_sgd"].notna()]
            if obs.empty:
                continue
            anchor = obs["price_sgd"].iloc[0]  # own first observation
        else:
            anchor = at_base.iloc[0]
        g["indexed"] = 100.0 * g["price_sgd"] / anchor
        g["late"] = late
        frames.append(g)
    idx = pd.concat(frames, ignore_index=True)

    fig, ax = plt.subplots(figsize=(15.5, 8.6))
    _shade(ax, alpha=0.09)
    for s in SHOCKS:
        ax.annotate(
            s["name"], xy=(pd.Timestamp(s["start"]), 0.985), xycoords=("data", "axes fraction"),
            fontsize=8, color=s["colour"], ha="left", va="top", xytext=(3, 0),
            textcoords="offset points", fontweight="bold",
        )

    for item_id, g in idx.groupby("item_id", sort=False):
        g = g.sort_values("date")
        ax.plot(
            g["date"], g["indexed"],
            color=CATEGORY_COLOURS[g["category"].iloc[0]],
            linewidth=0.65, alpha=0.30,
            linestyle=":" if bool(g["late"].iloc[0]) else "-",
            zorder=2,
        )

    for cat in CATEGORY_ORDER:
        sub = idx[(idx["category"] == cat) & (~idx["late"])]
        if sub.empty:
            continue
        med = sub.groupby("date")["indexed"].median()
        ax.plot(med.index, med.to_numpy(), color=CATEGORY_COLOURS[cat],
                linewidth=2.6, alpha=0.95, zorder=4, solid_capstyle="round")

    ax.axhline(100, color="#333", linewidth=0.9, linestyle="--", alpha=0.65, zorder=3)
    ax.axvline(base, color="#333", linewidth=0.9, alpha=0.45, zorder=3)
    ax.set_ylabel(f"Index (100 = {base:%b %Y})", fontsize=10)
    ax.set_xlim(df["date"].min(), df["date"].max())
    ax.grid(axis="y", color="#eeeeee", linewidth=0.7)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)

    handles = [
        Line2D([], [], color=CATEGORY_COLOURS[c], linewidth=2.6,
               label=f"{c.replace('_', '/')} (median)")
        for c in CATEGORY_ORDER if c in set(idx["category"])
    ]
    handles.append(Line2D([], [], color="#999", linewidth=0.9, linestyle=":",
                          label="late start (rebased at own first obs)"))
    ax.legend(handles=handles, fontsize=8.5, ncol=6, loc="upper left",
              frameon=False, bbox_to_anchor=(0, -0.065))

    n_late = int(idx.groupby("item_id")["late"].first().sum())
    fig.suptitle(
        f"All items rebased to 100 at {base:%B %Y}  ·  thick line = category median",
        fontsize=13.5, fontweight="bold", x=0.045, ha="left", y=0.975,
    )
    subtitle = (
        f"Base {base:%b %Y} chosen as the earliest month with maximal coverage "
        f"({n_base}/{df['item_id'].nunique()} items), preserving the full history. "
        f"{n_late} items begin at the 2024 basket rebase and cannot share that base: "
        f"they are dotted and rebased at their own first observation, so their "
        f"levels are NOT comparable to the rest."
    )
    fig.text(
        0.045, 0.945, "\n".join(textwrap.wrap(subtitle, width=150)),
        fontsize=8.4, color="#666", ha="left", va="top", linespacing=1.5,
    )
    fig.subplots_adjust(top=0.885, bottom=0.155, left=0.055, right=0.985)
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=150, facecolor="white")
    plt.close(fig)
    log.info("wrote %s", path)
    return path, base, n_base


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)-7s %(name)s: %(message)s")
    df = load_prices()
    FIG_DIR.mkdir(parents=True, exist_ok=True)
    small_multiples(df, FIG_DIR / "small_multiples.png")
    _, base, n = combined_indexed(df, FIG_DIR / "combined_indexed.png")
    print(f"figures written to {FIG_DIR.relative_to(PROJECT_ROOT)}  (rebase base={base:%Y-%m}, {n} items)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
