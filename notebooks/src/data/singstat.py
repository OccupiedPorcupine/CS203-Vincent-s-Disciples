"""Ingest SingStat 'Average Retail Prices of Selected Consumer Items' (monthly).

API contract (verified 2026-09-14 against the live service, not assumed):

    base            https://tablebuilder.singstat.gov.sg/api/table
    resource search GET /resourceid?keyword=<kw>&searchOption=all
    table metadata  GET /metadata/<resourceId>
    table data      GET /tabledata/<resourceId>?limit=<n>&offset=<n>

Four behaviours of this API drive the parser below:

1. Missing months are encoded by OMISSION. There are no "na" / "-" / "" value
   tokens anywhere in the payload; a month with no price simply has no entry in
   ``columns``. Gaps are therefore only discoverable by reindexing each series
   onto the full month axis, which `to_tidy` does.
2. ``limit`` counts DATA POINTS, not rows, and the server hard-caps the
   response at 5000 points. An unpaged request silently returns ~40 of the 85
   items and looks perfectly well-formed. Pagination is mandatory.
3. Pages split MID-SERIES: a series straddling the boundary appears in both
   pages with different column subsets, so pages are merged by ``seriesNo``.
4. Period keys ("2015 Jan") do not sort correctly as strings and must be
   parsed to timestamps before ordering.

No API key is required for this endpoint. If SingStat later gates it, set
SINGSTAT_API_KEY in the environment; it is read here and never committed.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import re
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol, runtime_checkable

import pandas as pd
import requests

from src.data.categories import CATEGORY_BY_SERIES_NO

log = logging.getLogger("singstat")

BASE_URL = "https://tablebuilder.singstat.gov.sg/api/table"
SEARCH_KEYWORD = "average retail prices"
TABLE_TITLE_MATCH = "monthly"
EXPECTED_ITEM_COUNT = 85
ITEM_COUNT_TOLERANCE = 10
PAGE_POINTS = 5000

# The API rejects default library User-Agent strings, so send an explicit one.
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = PROJECT_ROOT / "data" / "raw"
PROCESSED_PATH = PROJECT_ROOT / "data" / "processed" / "retail_prices.parquet"

# Values that mean "no observation". Kept even though the live API emits none,
# because the data.gov.sg mirror and manual CSV exports both use them.
MISSING_TOKENS = {"na", "n.a.", "n/a", "-", "--", "", "nil", "none"}


class IngestionError(RuntimeError):
    """Raised when the API contract is violated in a way we refuse to paper over."""


@runtime_checkable
class PriceSource(Protocol):
    """Anything that can produce the tidy long-format price panel."""

    def fetch(self) -> pd.DataFrame: ...


def slugify(name: str) -> str:
    """Convert an item name into a stable lowercase slug."""
    slug = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
    return re.sub(r"_+", "_", slug)


def split_name_and_unit(row_text: str) -> tuple[str, str]:
    """Split ``rowText`` into a cleaned item name and its unit.

    The API's own ``uoM`` field is the literal string "Dollar" for all 85 items
    and carries no packaging information, so the unit is recovered from the
    trailing parenthetical of the name instead::

        "Lean Pork, Chilled (Per Kilogram)" -> ("Lean Pork, Chilled", "per kilogram")
        "Hen Eggs (Per 10)"                 -> ("Hen Eggs", "per 10")
        "Toilet Paper (10 Rolls)"           -> ("Toilet Paper", "per 10 rolls")

    Leading parentheticals that are part of the name ("Spanish Mackerel
    (Batang)") are preserved, since only the final group is a unit.
    """
    text = row_text.strip()
    matches = list(re.finditer(r"\(([^()]*)\)", text))
    if not matches:
        return text, "each"

    last = matches[-1]
    if last.end() != len(text):  # trailing parenthetical is not final -> no unit
        return text, "each"

    inner = last.group(1).strip()
    name = text[: last.start()].strip().rstrip(",").strip()

    unit = inner.lower()
    if unit == "each":
        unit = "each"
    elif not unit.startswith("per"):
        # "10 Rolls" / "640 Ml" / "60 Pieces Per Pack" -> prefix with "per"
        unit = f"per {unit}"
    return (name or text), unit


def parse_period(period: str) -> pd.Timestamp:
    """Parse a SingStat period key ("2015 Jan") to a month-END timestamp."""
    ts = pd.to_datetime(period.strip(), format="%Y %b")
    return ts + pd.offsets.MonthEnd(0)


def parse_value(raw: Any) -> float:
    """Parse a price to float. Missing markers -> NaN. Never returns 0 for missing."""
    if raw is None:
        return float("nan")
    text = str(raw).strip()
    if text.lower() in MISSING_TOKENS:
        return float("nan")
    text = text.replace(",", "").replace("$", "")
    try:
        return float(text)
    except ValueError:
        return float("nan")


@dataclass
class SingStatAPISource:
    """Live source. Fetches from the API and archives raw JSON to ``data/raw/``.

    Every response is written verbatim to disk before parsing, so a later
    `CachedSource` run reproduces the same panel with no network access.
    """

    raw_dir: Path = RAW_DIR
    keyword: str = SEARCH_KEYWORD
    timeout: int = 60
    max_retries: int = 3
    session: requests.Session = field(default_factory=requests.Session, repr=False)

    def __post_init__(self) -> None:
        self.raw_dir.mkdir(parents=True, exist_ok=True)
        self.session.headers.update(
            {"User-Agent": USER_AGENT, "Accept": "application/json"}
        )
        api_key = os.environ.get("SINGSTAT_API_KEY")
        if api_key:
            self.session.headers["Authorization"] = api_key
            log.info("Using SINGSTAT_API_KEY from environment.")

    def _get(self, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        url = f"{BASE_URL}/{path}"
        last_exc: Exception | None = None
        for attempt in range(1, self.max_retries + 1):
            try:
                resp = self.session.get(url, params=params, timeout=self.timeout)
                resp.raise_for_status()
                payload = resp.json()
            except Exception as exc:  # noqa: BLE001 - retried and re-raised below
                last_exc = exc
                if attempt == self.max_retries:
                    break
                sleep_s = 2**attempt
                log.warning("GET %s failed (%s); retrying in %ss", path, exc, sleep_s)
                time.sleep(sleep_s)
                continue
            if str(payload.get("StatusCode")) != "200":
                raise IngestionError(f"API error for {url}: {payload.get('Message')!r}")
            return payload
        raise IngestionError(f"GET {url} failed after {self.max_retries} attempts: {last_exc}")

    def _write_raw(self, name: str, payload: dict[str, Any]) -> None:
        path = self.raw_dir / name
        path.write_text(json.dumps(payload, indent=1, ensure_ascii=False), encoding="utf-8")
        log.info("wrote raw %s (%.0f KB)", path.name, path.stat().st_size / 1024)

    def discover_resource_id(self) -> str:
        """Find the monthly table's resource ID by keyword search."""
        payload = self._get("resourceid", {"keyword": self.keyword, "searchOption": "all"})
        self._write_raw("resource_search.json", payload)
        records = (payload.get("Data") or {}).get("records") or []
        if not records:
            raise IngestionError(f"No tables matched keyword {self.keyword!r}.")

        for rec in records:
            if TABLE_TITLE_MATCH in str(rec.get("title", "")).lower():
                log.info("Resolved resource %s -> %s", rec["id"], rec["title"])
                return str(rec["id"])
        found = ", ".join(f"{r.get('id')}={r.get('title')}" for r in records)
        raise IngestionError(f"No monthly table among matches: {found}")

    def fetch_metadata(self, resource_id: str) -> dict[str, Any]:
        payload = self._get(f"metadata/{resource_id}")
        self._write_raw("metadata.json", payload)
        return (payload.get("Data") or {}).get("records") or {}

    def fetch_pages(self, resource_id: str, total_points: int) -> list[dict[str, Any]]:
        """Page through the table, honouring the server's 5000-point cap."""
        pages: list[dict[str, Any]] = []
        offset = 0
        page_no = 0
        while offset < total_points:
            page_no += 1
            payload = self._get(
                f"tabledata/{resource_id}", {"limit": PAGE_POINTS, "offset": offset}
            )
            self._write_raw(f"tabledata_page{page_no:02d}.json", payload)
            rows = ((payload.get("Data") or {}).get("row")) or []
            got = sum(len(r.get("columns") or []) for r in rows)
            if got == 0:
                break
            pages.append(payload)
            offset += got
            log.info("page %d: %d rows, %d points (%d/%d)", page_no, len(rows), got, offset, total_points)
        return pages

    def fetch(self) -> pd.DataFrame:
        resource_id = self.discover_resource_id()
        meta = self.fetch_metadata(resource_id)

        item_count = len(meta.get("row") or [])
        total_points = int(meta.get("total") or 0)
        log.info(
            "table %s: %d items, %d points, %s -> %s",
            resource_id, item_count, total_points,
            meta.get("startPeriod"), meta.get("endPeriod"),
        )
        _validate_item_count(item_count)

        pages = self.fetch_pages(resource_id, total_points)
        rows = merge_pages(pages)
        if len(rows) != item_count:
            raise IngestionError(
                f"Merged {len(rows)} series but metadata declares {item_count}. "
                "Pagination is incomplete; refusing to emit a truncated panel."
            )
        collected = sum(len(r["columns"]) for r in rows.values())
        if collected != total_points:
            raise IngestionError(
                f"Collected {collected} points but metadata declares {total_points}."
            )
        return to_tidy(rows)


@dataclass
class CachedSource:
    """Offline source. Reads the processed parquet; makes zero network calls."""

    path: Path = PROCESSED_PATH

    def fetch(self) -> pd.DataFrame:
        if not self.path.exists():
            raise FileNotFoundError(
                f"No cache at {self.path}. Run ingestion once with --refresh first."
            )
        df = pd.read_parquet(self.path)
        log.info("loaded cache %s (%d rows, no network)", self.path.name, len(df))
        return df


def _validate_item_count(count: int) -> None:
    low = EXPECTED_ITEM_COUNT - ITEM_COUNT_TOLERANCE
    high = EXPECTED_ITEM_COUNT + ITEM_COUNT_TOLERANCE
    if not (low <= count <= high):
        raise IngestionError(
            f"Item count {count} is outside the expected range {low}-{high}. "
            "The table may have been restructured; stopping rather than "
            "silently ingesting the wrong shape."
        )


def merge_pages(pages: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    """Merge paged responses into one record per series.

    Pages split mid-series, so a series can appear in two pages carrying
    different halves of its time axis. Columns are unioned and de-duplicated
    by period key.
    """
    merged: dict[str, dict[str, Any]] = {}
    for payload in pages:
        for row in ((payload.get("Data") or {}).get("row")) or []:
            key = str(row.get("seriesNo"))
            slot = merged.setdefault(
                key,
                {
                    "seriesNo": key,
                    "rowText": row.get("rowText", ""),
                    "uoM": row.get("uoM", ""),
                    "footnote": row.get("footnote", ""),
                    "columns": [],
                    "_seen": set(),
                },
            )
            for col in row.get("columns") or []:
                period = col.get("key")
                if period in slot["_seen"]:
                    continue
                slot["_seen"].add(period)
                slot["columns"].append(col)
    for slot in merged.values():
        slot.pop("_seen", None)
    return merged


def to_tidy(rows: dict[str, dict[str, Any]]) -> pd.DataFrame:
    """Build the tidy long panel: one row per item-month.

    Each series is reindexed onto the full observed month axis so that gaps
    become explicit NaN rows. Nothing is filled or interpolated: a NaN here
    means SingStat published no price for that item that month, which is
    information the forecasting work needs to see.
    """
    records: list[dict[str, Any]] = []
    for series_no, row in rows.items():
        name, unit = split_name_and_unit(row["rowText"])
        item_id = slugify(name)
        category = CATEGORY_BY_SERIES_NO.get(int(series_no), "uncategorised")
        for col in row["columns"]:
            records.append(
                {
                    "date": parse_period(col["key"]),
                    "series_no": int(series_no),
                    "item_id": item_id,
                    "item_name": name,
                    "unit": unit,
                    "category": category,
                    "price_sgd": parse_value(col.get("value")),
                }
            )

    df = pd.DataFrame.from_records(records)
    if df.empty:
        raise IngestionError("Parsed zero observations.")

    # Reindex every item onto the full month axis so omitted months surface as
    # explicit NaN rows rather than absent ones.
    full_months = pd.date_range(df["date"].min(), df["date"].max(), freq="ME")
    attrs = (
        df[["item_id", "series_no", "item_name", "unit", "category"]]
        .drop_duplicates("item_id")
        .set_index("item_id")
    )
    grid = pd.MultiIndex.from_product(
        [attrs.index, full_months], names=["item_id", "date"]
    )
    df = (
        df.set_index(["item_id", "date"])[["price_sgd"]]
        .reindex(grid)
        .join(attrs, on="item_id")
        .reset_index()
    )

    # A price of exactly 0 is not a real retail price; treat it as missing.
    zeros = int((df["price_sgd"] == 0).sum())
    if zeros:
        log.warning("coerced %d zero prices to NaN", zeros)
        df.loc[df["price_sgd"] == 0, "price_sgd"] = pd.NA

    df["price_sgd"] = df["price_sgd"].astype("float64")
    df = df[["date", "item_id", "item_name", "unit", "category", "price_sgd", "series_no"]]
    return df.sort_values(["category", "item_id", "date"]).reset_index(drop=True)


def load_prices(refresh: bool = False, processed_path: Path = PROCESSED_PATH) -> pd.DataFrame:
    """Return the panel, preferring the offline cache.

    With ``refresh=False`` and an existing parquet this makes zero network
    calls. Selecting the source here keeps `PriceSource` swappable.
    """
    source: PriceSource
    if not refresh and processed_path.exists():
        source = CachedSource(processed_path)
        return source.fetch()

    source = SingStatAPISource()
    df = source.fetch()
    processed_path.parent.mkdir(parents=True, exist_ok=True)
    df.to_parquet(processed_path, index=False)
    log.info("wrote %s (%d rows)", processed_path, len(df))
    return df


def summarise(df: pd.DataFrame) -> str:
    obs = int(df["price_sgd"].notna().sum())
    return (
        f"items={df['item_id'].nunique()}  months={df['date'].nunique()}  "
        f"rows={len(df)}  observed={obs}  missing={len(df) - obs} "
        f"({(len(df) - obs) / len(df):.1%})  "
        f"range={df['date'].min():%Y-%m} -> {df['date'].max():%Y-%m}"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--refresh", action="store_true", help="force a live API fetch")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.INFO if args.verbose else logging.WARNING,
        format="%(levelname)-7s %(name)s: %(message)s",
        stream=sys.stderr,
    )
    try:
        df = load_prices(refresh=args.refresh)
    except IngestionError as exc:
        print(f"INGESTION STOPPED: {exc}", file=sys.stderr)
        return 1
    print(summarise(df))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
