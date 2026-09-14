"""Generate reports/explorer.html - a single self-contained Plotly explorer.

No server and no build step: the generated file opens by double-click.

By default Plotly.js is INLINED from the installed `plotly` package rather than
pulled from a CDN, so the file genuinely works with no network (the brief asked
for both "from CDN" and "opens offline"; those conflict, and offline won). Pass
``--cdn`` to emit the CDN variant instead, which is ~3.5 MB smaller but needs a
connection on first open.
"""

from __future__ import annotations

import argparse
import json
import logging
from pathlib import Path

import pandas as pd
import plotly

from src.data.categories import CATEGORY_COLOURS, CATEGORY_ORDER
from src.data.singstat import PROJECT_ROOT, load_prices

log = logging.getLogger("explorer")

OUT_PATH = PROJECT_ROOT / "reports" / "explorer.html"
CDN_URL = "https://cdn.plot.ly/plotly-3.0.1.min.js"
BASE_DATE = "2015-01-31"


def build_payload(df: pd.DataFrame) -> dict[str, object]:
    """Serialise the panel: shared date axis + per-item value arrays with nulls."""
    dates = sorted(df["date"].unique())
    date_strs = [pd.Timestamp(d).strftime("%Y-%m-%d") for d in dates]

    items = []
    for item_id, g in df.groupby("item_id", sort=False):
        g = g.set_index("date").reindex(dates)
        vals = [None if pd.isna(v) else round(float(v), 4) for v in g["price_sgd"]]
        meta = df[df["item_id"] == item_id].iloc[0]
        items.append(
            {
                "id": item_id,
                "name": str(meta["item_name"]),
                "unit": str(meta["unit"]),
                "category": str(meta["category"]),
                "values": vals,
            }
        )
    items.sort(key=lambda d: (CATEGORY_ORDER.index(d["category"]), d["name"]))

    return {
        "dates": date_strs,
        "items": items,
        "categories": [c for c in CATEGORY_ORDER if any(i["category"] == c for i in items)],
        "colours": CATEGORY_COLOURS,
        "baseDate": BASE_DATE,
    }


def render_html(payload: dict[str, object], inline_plotly: bool) -> str:
    if inline_plotly:
        js_path = Path(plotly.__file__).parent / "package_data" / "plotly.min.js"
        if not js_path.exists():
            raise FileNotFoundError(f"Bundled plotly.min.js not found at {js_path}")
        plotly_tag = f"<script>{js_path.read_text(encoding='utf-8')}</script>"
        source_note = "Plotly.js inlined - works fully offline."
    else:
        plotly_tag = f'<script src="{CDN_URL}" charset="utf-8"></script>'
        source_note = "Plotly.js from CDN - needs a connection on first open."

    return (
        TEMPLATE
        .replace("__PLOTLY__", plotly_tag)
        .replace("__SOURCE_NOTE__", source_note)
        .replace("__PAYLOAD__", json.dumps(payload, separators=(",", ":")))
    )


TEMPLATE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>SG Retail Price Explorer</title>
__PLOTLY__
<style>
  :root{
    --bg:#ffffff; --panel:#f7f7f9; --ink:#1a1a1a; --muted:#6b7280;
    --line:#e3e3e8; --accent:#0072B2;
  }
  *{box-sizing:border-box}
  body{margin:0;font:13px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
       color:var(--ink);background:var(--bg);}
  #app{display:flex;height:100vh;overflow:hidden}
  #sidebar{width:290px;flex:0 0 290px;border-right:1px solid var(--line);
           background:var(--panel);display:flex;flex-direction:column}
  #main{flex:1;display:flex;flex-direction:column;min-width:0}
  header{padding:12px 16px 10px;border-bottom:1px solid var(--line)}
  h1{margin:0 0 3px;font-size:15px;letter-spacing:-.01em}
  .sub{color:var(--muted);font-size:11px}
  #controls{padding:10px 16px;border-bottom:1px solid var(--line);display:flex;
            gap:18px;align-items:center;flex-wrap:wrap}
  .ctl{display:flex;align-items:center;gap:7px}
  .ctl label{color:var(--muted);font-size:11px;text-transform:uppercase;
             letter-spacing:.05em;font-weight:600}
  select,input[type=search]{font:inherit;padding:4px 7px;border:1px solid var(--line);
    border-radius:5px;background:#fff;color:var(--ink)}
  input[type=search]{width:100%}
  #filterwrap{padding:9px 12px;border-bottom:1px solid var(--line)}
  #cats{overflow-y:auto;flex:1;padding:4px 0 20px}
  .cat{border-bottom:1px solid var(--line)}
  .cathead{display:flex;align-items:center;gap:7px;padding:7px 12px;
           font-weight:700;font-size:11px;text-transform:uppercase;letter-spacing:.05em}
  .swatch{width:9px;height:9px;border-radius:2px;flex:0 0 auto}
  .catname{flex:1;cursor:pointer}
  .mini{font-size:10px;color:var(--muted);cursor:pointer;text-transform:none;
        letter-spacing:0;font-weight:500;padding:1px 4px;border-radius:3px}
  .mini:hover{background:#e6e6ea;color:var(--ink)}
  .item{display:flex;align-items:center;gap:7px;padding:2px 12px 2px 26px;cursor:pointer}
  .item:hover{background:#ececf0}
  .item label{cursor:pointer;flex:1;font-size:12px}
  .item .u{color:var(--muted);font-size:10px}
  #chart{flex:1 1 auto;min-height:0}
  #status{flex:0 0 auto;padding:7px 16px;border-top:1px solid var(--line);
          color:var(--muted);font-size:11px;background:var(--panel)}
  .hide{display:none !important}
  .btn{font:inherit;font-size:11px;padding:4px 9px;border:1px solid var(--line);
       background:#fff;border-radius:5px;cursor:pointer}
  .btn:hover{border-color:var(--accent);color:var(--accent)}
</style>
</head>
<body>
<div id="app">
  <div id="sidebar">
    <header>
      <h1>Retail Price Explorer</h1>
      <div class="sub">SingStat M213761 &middot; <span id="nsel">0</span> of <span id="ntot">0</span> items shown</div>
    </header>
    <div id="filterwrap">
      <input type="search" id="filter" placeholder="Filter items, e.g. chicken, fish, petrol">
      <div style="display:flex;gap:6px;margin-top:7px">
        <button class="btn" id="allBtn" style="flex:1">Select all</button>
        <button class="btn" id="noneBtn" style="flex:1">Clear all</button>
      </div>
    </div>
    <div id="cats"></div>
  </div>

  <div id="main">
    <div id="controls">
      <div class="ctl">
        <label for="view">View</label>
        <select id="view">
          <option value="raw">Raw price (S$)</option>
          <option value="indexed">Indexed to 100</option>
          <option value="yoy">YoY % change</option>
          <option value="mom">MoM log return (%)</option>
        </select>
      </div>
      <div class="ctl">
        <label for="logy">Log y</label>
        <input type="checkbox" id="logy">
      </div>
      <div class="ctl">
        <label for="bands">Shock bands</label>
        <input type="checkbox" id="bands" checked>
      </div>
      <div class="ctl">
        <label for="medians">Category medians</label>
        <input type="checkbox" id="medians">
      </div>
    </div>
    <div id="chart"></div>
    <div id="status"></div>
  </div>
</div>

<script>
// ---------------------------------------------------------------------------
// CONFIG: shaded shock bands. Add entries here; `end: null` means "onwards"
// and extends the band to the end of the data.
// ---------------------------------------------------------------------------
const SHOCK_BANDS = [
  { name: "COVID-19",              start: "2020-03-01", end: "2021-12-31", colour: "#6c757d" },
  // The brief specified "June 2022 onwards". The ban was actually lifted in
  // stages from Oct 2022, so the band is closed there. Set end:null for the
  // literal open-ended reading.
  { name: "MY chicken export ban", start: "2022-06-01", end: "2022-10-31", colour: "#d55e00" },
  { name: "2024 CPI basket rebase", start: "2024-01-01", end: "2024-01-31", colour: "#9467bd" },
];

const DATA = __PAYLOAD__;
const DATES = DATA.dates;
const ITEMS = DATA.items;
const COLOURS = DATA.colours;
const selected = new Set();

const VIEWS = {
  raw:     { label: "Price (S$)",        allowLog: true  },
  indexed: { label: "Index (100 = base)", allowLog: true  },
  yoy:     { label: "YoY change (%)",     allowLog: false },
  mom:     { label: "MoM log return (%)", allowLog: false },
};

// --- transforms. All of them refuse to bridge a gap: a null on either side of
// --- a comparison yields null, so nothing is invented across missing months.
function transform(values, view, baseIdx) {
  if (view === "raw") return values.slice();
  if (view === "indexed") {
    let anchor = values[baseIdx];
    if (anchor == null) anchor = values.find(v => v != null);   // late starter
    if (anchor == null || anchor === 0) return values.map(() => null);
    return values.map(v => (v == null ? null : (100 * v) / anchor));
  }
  const lag = view === "yoy" ? 12 : 1;
  return values.map((v, i) => {
    if (i < lag) return null;
    const p = values[i - lag];
    if (v == null || p == null || p <= 0) return null;
    return view === "yoy" ? (v / p - 1) * 100 : Math.log(v / p) * 100;
  });
}

// --- colour ramp -----------------------------------------------------------
// When every selected item sits in ONE category, a flat category colour makes
// the lines indistinguishable. In that case each item gets its own step along
// a lightness ramp built from the category's base hue, so the group still
// reads as "one category" while individual series stay tellable apart.
function hexToHsl(hex) {
  const r = parseInt(hex.slice(1, 3), 16) / 255,
        g = parseInt(hex.slice(3, 5), 16) / 255,
        b = parseInt(hex.slice(5, 7), 16) / 255;
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn;
  const l = (mx + mn) / 2;
  let h = 0, sat = 0;
  if (d !== 0) {
    sat = d / (1 - Math.abs(2 * l - 1));
    if (mx === r)      h = 60 * (((g - b) / d) % 6);
    else if (mx === g) h = 60 * ((b - r) / d + 2);
    else               h = 60 * ((r - g) / d + 4);
  }
  if (h < 0) h += 360;
  return { h: h, s: sat * 100, l: l * 100 };
}

// Adjacent sidebar entries are usually the most confusable items ("Chicken
// Wing, Chilled" vs "...Frozen"), so walking the ramp in list order gives the
// two hardest-to-tell-apart series the two most similar shades. Interleaving
// sends consecutive items to opposite ends of the ramp instead.
function rampSlot(i, n) {
  const half = Math.ceil(n / 2);
  return i % 2 === 0 ? i / 2 : half + (i - 1) / 2;
}

function shadeRamp(baseHex, n, i) {
  if (n <= 1) return baseHex;
  i = rampSlot(i, n);
  const c = hexToHsl(baseHex);
  // Walk lightness across a band centred on the base colour. Clamped so the
  // darkest step stays readable and the lightest stays visible on white.
  const lo = Math.max(24, c.l - 27), hi = Math.min(74, c.l + 27);
  const t = i / (n - 1);
  const L = lo + t * (hi - lo);
  // Lift saturation slightly at the pale end so light steps do not wash out.
  const S = Math.max(28, Math.min(96, c.s - (t - 0.5) * 14));
  return "hsl(" + c.h.toFixed(1) + " " + S.toFixed(1) + "% " + L.toFixed(1) + "%)";
}

function darken(baseHex, amount) {
  const c = hexToHsl(baseHex);
  return "hsl(" + c.h.toFixed(1) + " " + Math.min(96, c.s + 6).toFixed(1) + "% " +
         Math.max(16, c.l - amount).toFixed(1) + "%)";
}

function median(xs) {
  const a = xs.filter(v => v != null).sort((x, y) => x - y);
  if (!a.length) return null;
  const m = a.length >> 1;
  return a.length % 2 ? a[m] : (a[m - 1] + a[m]) / 2;
}

// --- sidebar ---------------------------------------------------------------
function buildSidebar() {
  const host = document.getElementById("cats");
  DATA.categories.forEach(cat => {
    const members = ITEMS.filter(i => i.category === cat);
    const box = document.createElement("div");
    box.className = "cat";
    box.dataset.cat = cat;
    box.innerHTML =
      '<div class="cathead">' +
        '<span class="swatch" style="background:' + COLOURS[cat] + '"></span>' +
        '<span class="catname">' + cat.replace(/_/g, "/") + '</span>' +
        '<span class="mini" data-act="all">all</span>' +
        '<span class="mini" data-act="none">none</span>' +
      '</div>';
    members.forEach(it => {
      const row = document.createElement("div");
      row.className = "item";
      row.dataset.id = it.id;
      row.dataset.search = (it.name + " " + it.category + " " + it.unit).toLowerCase();
      row.innerHTML =
        '<input type="checkbox" id="cb_' + it.id + '">' +
        '<label for="cb_' + it.id + '">' + it.name +
        ' <span class="u">' + it.unit + '</span></label>';
      row.querySelector("input").addEventListener("change", e => {
        e.target.checked ? selected.add(it.id) : selected.delete(it.id);
        render();
      });
      box.appendChild(row);
    });
    box.querySelector('[data-act="all"]').addEventListener("click", () => setCat(cat, true));
    box.querySelector('[data-act="none"]').addEventListener("click", () => setCat(cat, false));
    box.querySelector(".catname").addEventListener("click", () => {
      const vis = visibleIn(cat);
      setCat(cat, !vis.every(i => selected.has(i.id)));
    });
    host.appendChild(box);
  });
  document.getElementById("ntot").textContent = ITEMS.length;
}

function visibleIn(cat) {
  return ITEMS.filter(i => i.category === cat &&
    !document.querySelector('.item[data-id="' + i.id + '"]').classList.contains("hide"));
}

function setCat(cat, on) {
  visibleIn(cat).forEach(i => {
    on ? selected.add(i.id) : selected.delete(i.id);
    document.getElementById("cb_" + i.id).checked = on;
  });
  render();
}

function setAll(on) {
  ITEMS.forEach(i => {
    const row = document.querySelector('.item[data-id="' + i.id + '"]');
    if (row.classList.contains("hide")) return;
    on ? selected.add(i.id) : selected.delete(i.id);
    document.getElementById("cb_" + i.id).checked = on;
  });
  render();
}

function applyFilter() {
  const q = document.getElementById("filter").value.trim().toLowerCase();
  document.querySelectorAll(".item").forEach(row => {
    row.classList.toggle("hide", q !== "" && !row.dataset.search.includes(q));
  });
  document.querySelectorAll(".cat").forEach(box => {
    const any = [...box.querySelectorAll(".item")].some(r => !r.classList.contains("hide"));
    box.classList.toggle("hide", !any);
  });
}

// --- chart -----------------------------------------------------------------
function render() {
  const view = document.getElementById("view").value;
  const logy = document.getElementById("logy").checked;
  const showBands = document.getElementById("bands").checked;
  const showMed = document.getElementById("medians").checked;
  const cfg = VIEWS[view];

  const logBox = document.getElementById("logy");
  logBox.disabled = !cfg.allowLog;
  logBox.parentElement.style.opacity = cfg.allowLog ? 1 : 0.4;
  const useLog = logy && cfg.allowLog;

  const baseIdx = Math.max(0, DATES.indexOf(DATA.baseDate));
  const chosen = ITEMS.filter(i => selected.has(i.id));
  const traces = [];
  const byCat = {};

  // Single-category mode: shade each item instead of flattening them all to
  // one colour, and show the legend (a handful of entries is readable, 85 is
  // not - which is what the sidebar is for).
  const catsChosen = [...new Set(chosen.map(i => i.category))];
  const single = catsChosen.length === 1 && chosen.length > 1;

  chosen.forEach((it, i) => {
    const y = transform(it.values, view, baseIdx);
    (byCat[it.category] = byCat[it.category] || []).push(y);
    traces.push({
      type: "scattergl", mode: "lines", name: it.name, x: DATES, y: y,
      line: {
        color: single ? shadeRamp(COLOURS[it.category], chosen.length, i)
                      : COLOURS[it.category],
        width: single ? 1.7 : 1.4,
      },
      opacity: showMed ? (single ? 0.55 : 0.35) : (single ? 1 : 0.9),
      showlegend: single,
      connectgaps: false,               // gaps stay gaps
      customdata: it.values.map(v => [it.unit, v]),
      hovertemplate:
        "<b>" + it.name + "</b><br>%{x|%b %Y}<br>" +
        (view === "raw"
          ? "S$%{y:.2f} %{customdata[0]}"
          : cfg.label + ": %{y:.2f}<br>price: S$%{customdata[1]:.2f} %{customdata[0]}") +
        "<extra></extra>",
    });
  });

  if (showMed) {
    Object.entries(byCat).forEach(([cat, series]) => {
      const med = DATES.map((_, i) => median(series.map(s => s[i])));
      traces.push({
        type: "scattergl", mode: "lines", name: cat.replace(/_/g, "/") + " median",
        x: DATES, y: med,
        line: { color: single ? darken(COLOURS[cat], 22) : COLOURS[cat],
                width: single ? 3.6 : 3,
                dash: single ? "dash" : "solid" },
        showlegend: true, connectgaps: false,
        hovertemplate: "<b>" + cat + " median</b><br>%{x|%b %Y}<br>%{y:.2f}<extra></extra>",
      });
    });
  }

  const shapes = [], anns = [];
  if (showBands) {
    SHOCK_BANDS.forEach(b => {
      shapes.push({
        type: "rect", xref: "x", yref: "paper",
        x0: b.start, x1: b.end === null ? DATES[DATES.length - 1] : b.end,
        y0: 0, y1: 1, fillcolor: b.colour, opacity: 0.12,
        line: { width: 0 }, layer: "below",
      });
      anns.push({
        x: b.start, y: 1, xref: "x", yref: "paper", text: b.name,
        showarrow: false, xanchor: "left", yanchor: "bottom",
        font: { size: 10, color: b.colour }, yshift: 2,
      });
    });
  }
  if (["yoy", "mom"].includes(view)) {
    shapes.push({ type: "line", xref: "paper", yref: "y", x0: 0, x1: 1, y0: 0, y1: 0,
                  line: { color: "#333", width: 1, dash: "dot" } });
  } else if (view === "indexed") {
    shapes.push({ type: "line", xref: "paper", yref: "y", x0: 0, x1: 1, y0: 100, y1: 100,
                  line: { color: "#333", width: 1, dash: "dot" } });
  }

  const layout = {
    margin: { l: 58, r: 18, t: 22, b: 38 },
    hovermode: "closest",
    xaxis: { rangeslider: { visible: true, thickness: 0.07 }, type: "date", gridcolor: "#f0f0f3" },
    yaxis: { title: { text: cfg.label, font: { size: 11 } },
             type: useLog ? "log" : "linear", gridcolor: "#f0f0f3", zeroline: false },
    shapes: shapes, annotations: anns,
    showlegend: showMed || single,
    legend: { orientation: "h", y: -0.18, font: { size: 10 },
              itemwidth: 30, traceorder: "normal" },
    plot_bgcolor: "#fff", paper_bgcolor: "#fff",
    font: { family: "-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif", size: 11 },
  };

  Plotly.react("chart", traces, layout, { responsive: true, displaylogo: false });

  document.getElementById("nsel").textContent = chosen.length;
  const miss = chosen.reduce((n, i) => n + i.values.filter(v => v == null).length, 0);
  document.getElementById("status").textContent =
    chosen.length
      ? chosen.length + " item(s) - " + miss + " missing month(s) left unfilled - gaps are breaks in the line"
        + (single ? " - shaded by item within " + catsChosen[0].replace(/_/g, "/") : "")
      : "No items selected. Pick items from the sidebar.";
}

// --- wire up ---------------------------------------------------------------
buildSidebar();
document.getElementById("filter").addEventListener("input", applyFilter);
document.getElementById("allBtn").addEventListener("click", () => setAll(true));
document.getElementById("noneBtn").addEventListener("click", () => setAll(false));
["view", "logy", "bands", "medians"].forEach(id =>
  document.getElementById(id).addEventListener("change", render));

// Sensible opening state: one representative item per category. Pick the
// longest-running item rather than the alphabetically first, so the opening
// view is not dominated by the 21 series that only begin at the 2024 rebase.
DATA.categories.forEach(cat => {
  const pick = ITEMS
    .filter(i => i.category === cat)
    .sort((a, b) => b.values.filter(v => v != null).length -
                    a.values.filter(v => v != null).length)[0];
  if (pick) { selected.add(pick.id); document.getElementById("cb_" + pick.id).checked = true; }
});
render();
</script>
</body>
</html>
"""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build the standalone explorer.")
    parser.add_argument("--cdn", action="store_true",
                        help="link Plotly from CDN instead of inlining it")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(levelname)-7s %(name)s: %(message)s")
    df = load_prices()
    html = render_html(build_payload(df), inline_plotly=not args.cdn)
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(html, encoding="utf-8")
    print(f"wrote {OUT_PATH.relative_to(PROJECT_ROOT)} ({OUT_PATH.stat().st_size / 1_048_576:.1f} MB, "
          f"{'CDN' if args.cdn else 'offline/inlined'})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
