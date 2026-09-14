# SingStat Retail Price Ingestion

Step zero for the food price forecasting module: get SingStat's monthly average
retail prices on disk, understand their shape, and make them inspectable.
**No modelling here.**

Source: SingStat Table Builder table **`M213761`** — *Average Retail Prices of
Selected Consumer Items, Monthly*. 85 items, 2015-01 → 2026-07.

---

## Quick start

```bash
uv venv --python 3.13 .venv
uv pip install -r requirements.txt --python .venv/bin/python
source .venv/bin/activate

python -m src.data.singstat --refresh -v   # ingest (network, first run only)
python -m src.reporting.quality            # -> reports/data_quality.md
python -m src.reporting.figures            # -> reports/figures/*.png
python -m src.reporting.explorer           # -> reports/explorer.html
```

Then open `reports/explorer.html` by double-clicking it.

Every script is idempotent: rerun any of them any number of times and you get
the same outputs.

## Ingestion

```bash
python -m src.data.singstat            # uses cache if present, no network
python -m src.data.singstat --refresh  # force a live API fetch
python -m src.data.singstat -v         # progress logging
```

The first run hits the API and archives every raw response to `data/raw/`.
**Every run after that is fully offline** — `load_prices()` reads the parquet
and makes zero network calls, so backtests never depend on a live API.

Verify that claim yourself:

```bash
python -c "
import socket, ssl
from src.data.singstat import main
def boom(*a, **k): raise RuntimeError('network!')
socket.socket.connect = boom; socket.create_connection = boom; socket.getaddrinfo = boom
raise SystemExit(main(['-v']))"
```

Swap the source out via the `PriceSource` protocol:

```python
from src.data.singstat import CachedSource, SingStatAPISource, PriceSource

source: PriceSource = CachedSource()        # offline parquet
source = SingStatAPISource()                # live API
df = source.fetch()
```

## Where things land

| Path | What |
|---|---|
| `data/raw/resource_search.json` | keyword search that resolved the resource ID |
| `data/raw/metadata.json` | table metadata: item list, units, period range |
| `data/raw/tabledata_page*.json` | verbatim paged API responses |
| `data/processed/retail_prices.parquet` | tidy long panel, one row per item-month |
| `reports/data_quality.md` | per-item coverage + the three summary answers |
| `reports/figures/small_multiples.png` | one panel per item, faceted by category |
| `reports/figures/combined_indexed.png` | all items rebased to 100, category medians |
| `reports/explorer.html` | interactive explorer, self-contained |

### Parquet schema

| column | type | notes |
|---|---|---|
| `date` | timestamp | month-END |
| `item_id` | str | stable slug, e.g. `lean_pork_chilled` |
| `item_name` | str | cleaned, unit stripped |
| `unit` | str | parsed from the name, e.g. `per kilogram`, `per 10` |
| `category` | str | see `src/data/categories.py` |
| `price_sgd` | float64 | NaN where missing, never 0 |
| `series_no` | int | SingStat series number, for tracing back to the API |

## The data, honestly

- **139 months, 2015-01 → 2026-07.** This is the *entire* history the API
  serves; the annual companion table `M213821` is also 2015+. There is no
  deeper history available through this source.
- **The panel is ragged and is never filled.** 19.2% of cells are missing.
  Nothing in this pipeline forward-fills, interpolates, or smooths — in the
  figures a gap is a visible break in the line.
- **Missing months are encoded by omission**, not by an `na` or `-` token. The
  parser reindexes each series onto the full month axis to make gaps explicit.
- **Zero interior gaps.** Raggedness is entirely at the edges: 21 items begin
  at 2024-01 and 1 item (Orange) ends early.
- **2024-01 is a structural break.** SingStat rebased to the 2024 CPI basket;
  per its own footnote, prices either side are not a pure price comparison.
  Two of the 20 largest month-on-month moves in the panel are this artefact,
  not a market event. Treat it as a level shift.

## Regenerating figures

`figures.py` and `explorer.py` both read the parquet, so they need no network:

```bash
python -m src.reporting.figures
python -m src.reporting.explorer          # offline, Plotly inlined (~4.7 MB)
python -m src.reporting.explorer --cdn    # ~1 MB, loads Plotly from CDN
```

### Explorer controls

Sidebar checkboxes grouped by category (with per-category *all* / *none*, a
text filter, and global select/clear — all of which respect the active filter),
plus: view toggle (raw / indexed to 100 / YoY % / MoM log return), log-y
(auto-disabled for the two return views, where it is undefined), shock bands,
category medians, and a date range slider.

**Single-category shading.** When every selected item belongs to one category,
each item gets its own step along a lightness ramp built from that category's
hue, and the legend switches on (a handful of entries is readable; 85 is not).
Select across two or more categories and it reverts to one flat colour per
category. The ramp is interleaved rather than walked in list order, so
adjacent sidebar entries - usually the most confusable items, e.g. "Chicken
Wing, Chilled" vs "...Frozen" - land at opposite ends of the ramp instead of
receiving near-identical shades.

Shaded shock bands are a config array at the top of the `<script>` in
`src/reporting/explorer.py` — edit `SHOCK_BANDS` and regenerate:

```js
const SHOCK_BANDS = [
  { name: "COVID-19", start: "2020-03-01", end: "2021-12-31", colour: "#6c757d" },
  ...
];
```

`end: null` means "onwards" and extends the band to the end of the data.

## Deliberate deviations from the brief

1. **A tenth category, `fruit`.** The brief's nine categories had no home for
   the six fruit items; folding them into `vegetables` would distort the
   category-median overlay. Change in `src/data/categories.py`.
2. **Plotly is inlined, not loaded from CDN** (`--cdn` restores CDN). The brief
   asked for both "from CDN" and "opens offline"; those conflict, and offline
   won, since the file is meant to be committed and shared.
3. **The chicken-ban band is closed at 2022-10**, not left open-ended. The ban
   was lifted in stages from October 2022, and an open band would shade four
   years of chart. Set `end: null` for the literal "June 2022 onwards".

## API notes

No API key is required. If SingStat gates the endpoint later, set
`SINGSTAT_API_KEY` in your environment; it is read at runtime and never
committed.

Three behaviours of this API are load-bearing and are handled in
`src/data/singstat.py`:

1. `limit` counts **data points, not rows**, and the server caps responses at
   5000. An unpaged request returns a well-formed-looking table containing only
   40 of the 85 items. Ingestion pages and then asserts the point count against
   the metadata `total`, failing loudly rather than emitting a short panel.
2. Pages split **mid-series**, so rows are merged by `seriesNo`.
3. Period keys (`"2015 Jan"`) do not sort correctly as strings.

The API also rejects default library User-Agents, so an explicit one is sent.
