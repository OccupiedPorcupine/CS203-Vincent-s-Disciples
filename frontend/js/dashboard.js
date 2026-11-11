async function requireLogin() {
  try {
    return await apiRequest("/api/profile");
  } catch (error) {
    if (error.status === 401) {
      window.location.href = "index.html";
      return null;
    }

    throw error;
  }
}

// Sale dates are plain "YYYY-MM-DD" strings; doing the arithmetic in UTC keeps them from
// shifting a day in the browser's time zone.
function addDays(isoDate, days) {
  const date = new Date(`${isoDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function formatDay(isoDate, options) {
  return new Date(`${isoDate}T00:00:00Z`).toLocaleDateString("en-SG", { ...options, timeZone: "UTC" });
}

const sum = values => values.reduce((total, value) => total + value, 0);

// Charts by canvas ID, so they can be redrawn after new sales are saved.
const charts = {};

// Read from <body>, which inherits every :root token and also sees the ones this page defines (--sand).
const cssVar = name => getComputedStyle(document.body).getPropertyValue(name).trim();
const count = new Intl.NumberFormat("en-SG");
const plural = (value, word) => `${count.format(value)} ${word}${value === 1 ? "" : "s"}`;

// "24–30 Oct", or "28 Oct – 3 Nov" across a month.
function formatRange(from, to) {
  const month = date => formatDay(date, { month: "short" });
  const day = date => formatDay(date, { day: "numeric" });
  return month(from) === month(to)
    ? `${day(from)}–${day(to)} ${month(to)}`
    : `${day(from)} ${month(from)} – ${day(to)} ${month(to)}`;
}

// Bars by rank: the leader darkest, then the brand teal. (The treemap uses lighter tints of the same
// hue for its small tiles, because they carry text.)
const barColor = rank => rank === 0 ? cssVar("--heat-4") : rank === 1 ? cssVar("--teal-strong") : cssVar("--teal");

// The by-dish tooltip is an element, not canvas drawing, so it can sit beside the hovered row without ever
// covering the dish names on the left, and stay inside the card and the window.
const dishTip = document.createElement("div");
dishTip.className = "chart-tip";
dishTip.setAttribute("role", "tooltip");
dishTip.hidden = true;
document.querySelector(".by-dish").append(dishTip);

function showDishTip(chart, ranked, total, index) {
  const dish = ranked[index];
  const swatch = document.createElement("span");
  swatch.className = "tip-swatch";
  swatch.style.setProperty("--swatch", barColor(index));
  const name = document.createElement("strong");
  name.append(swatch, dish.name);
  const portions = document.createElement("span");
  portions.textContent = plural(dish.value, "portion");
  dishTip.replaceChildren(name, portions);
  if (total > 0) {
    const share = document.createElement("span");
    share.textContent = `${Math.round(dish.value / total * 100)}% of all portions`;
    dishTip.append(share);
  }
  dishTip.hidden = false;

  // Where the row is, in the window: the canvas's box now (it may have scrolled or been scaled) times its own scale.
  const canvasBox = chart.canvas.getBoundingClientRect();
  const scale = canvasBox.width / chart.width;
  const bar = chart.getDatasetMeta(0).data[index];
  const rowY = canvasBox.top + bar.y * scale;
  const plotLeft = canvasBox.left + chart.chartArea.left * scale;
  const cardBox = dishTip.parentElement.getBoundingClientRect();
  chart.ctx.save();
  chart.ctx.font = `${Chart.defaults.font.size + 1}px ${Chart.defaults.font.family}`;
  const valueWidth = chart.ctx.measureText(count.format(dish.value)).width;
  chart.ctx.restore();

  const margin = 8;
  const rightLimit = Math.min(cardBox.right, window.innerWidth) - margin;
  // Never wider than the space to the right of the dish names, so it can always sit clear of them (the text wraps).
  dishTip.style.maxWidth = `${Math.max(140, Math.min(250, rightLimit - Math.max(plotLeft, cardBox.left + margin)))}px`;
  const width = dishTip.offsetWidth, height = dishTip.offsetHeight;
  const rowHalf = (bar.height * scale) / 2;
  let left, top;
  const beside = canvasBox.left + bar.x * scale + 8 + valueWidth * scale + 12;   // clear of the row's own figure
  if (beside + width <= rightLimit) {
    left = beside;
    top = rowY - height / 2;
  } else {
    // No room beside the bar: just under the row (or above it), starting at the plot area so the names stay clear.
    left = Math.min(Math.max(plotLeft, cardBox.left + margin), rightLimit - width);
    top = rowY + rowHalf + 6;
    if (top + height > Math.min(cardBox.bottom, window.innerHeight) - margin) top = rowY - rowHalf - 6 - height;
  }
  top = Math.min(Math.max(top, Math.max(cardBox.top, 0) + margin), Math.min(cardBox.bottom, window.innerHeight) - margin - height);
  dishTip.style.left = `${left - cardBox.left}px`;
  dishTip.style.top = `${top - cardBox.top}px`;
}

function hideDishTip() {
  dishTip.hidden = true;
}

// Chart.js measures axis labels when it draws. If the page's web font arrives after that, labels would be
// wider than the room reserved for them, so lay the charts out again once the fonts are ready.
document.fonts?.ready.then(() => Object.values(charts).forEach(chart => chart.update("none")));

// A dish name as up to two lines that fit `room` pixels, ending in "…" if it had to be cut.
function fitLines(text, room, width, maxLines = 2) {
  const clip = line => {
    while (line.length > 1 && width(`${line}…`) > room) line = line.slice(0, -1);
    return `${line}…`;
  };
  const lines = [];
  let line = "";
  text.split(/\s+/).forEach(word => {
    const next = line ? `${line} ${word}` : word;
    if (width(next) <= room) { line = next; return; }
    if (line) lines.push(line);
    line = width(word) <= room ? word : clip(word);
  });
  lines.push(line);
  if (lines.length <= maxLines) return lines;
  return [...lines.slice(0, maxLines - 1), clip(lines.slice(maxLines - 1).join(" "))];
}

function resetChart(canvasId) {
  charts[canvasId]?.destroy();
  const canvas = document.getElementById(canvasId);
  canvas.chickyKeyboard?.dispose();
  canvas.chickyPointer?.dispose();
}

// Demand over the week: the shared recorded-values line chart (js/chart-theme.js), also used by the forecast page.
function drawDemandChart({ labels, dates, values }) {
  resetChart("demand-chart");
  const canvas = document.getElementById("demand-chart");
  charts["demand-chart"] = chickyChart.recordedChart(canvas, { labels, dates, values, averageOf: "the daily average" });
  const spoken = labels.map((label, index) => values[index] === null ? `${label}` : `${label} ${values[index]}`).join(", ");
  canvas.setAttribute("aria-label", `Line chart of portions sold each day, all dishes combined. ${spoken}.`);
}

// Demand by dish, most first, with each dish's portions written at the end of its bar.
function drawDishChart(ranked, range) {
  resetChart("dish-chart");
  const canvas = document.getElementById("dish-chart");
  const ink = cssVar("--ink"), teal = cssVar("--teal");
  const total = sum(ranked.map(dish => dish.value));

  // Row height: a few dishes get generous rows at the top of the card; many get tighter rows, the card grows up
  // to 520px and then the chart scrolls inside it.
  const rows = ranked.length;
  const needed = rows <= 5 ? rows * 64 + 16 : rows * 38 + 8;
  canvas.parentElement.parentElement.style.minHeight = `${Math.min(Math.max(needed, 120), 520)}px`;
  canvas.parentElement.style.height = rows <= 5 || needed > 520 ? `${needed}px` : "";

  const plugins = [{
    id: "dishRows",
    // A soft band behind the row under the pointer or keyboard focus, in that dish's own colour.
    beforeDatasetsDraw(chart) {
      const active = chart.tooltip?.getActiveElements?.() ?? [];
      if (!active.length) return;
      const { element, index } = active[0];
      chart.ctx.save();
      chart.ctx.fillStyle = `${barColor(index)}29`;
      chart.ctx.fillRect(0, element.y - element.height / 2 - 3, chart.width, element.height + 6);
      chart.ctx.restore();
    },
    afterDatasetsDraw(chart) {
      const { ctx } = chart;
      ctx.save();
      ctx.fillStyle = ink;
      ctx.font = `${Chart.defaults.font.size + 1}px ${Chart.defaults.font.family}`;
      ctx.textAlign = "left";
      ctx.textBaseline = "middle";
      chart.getDatasetMeta(0).data.forEach((bar, index) => ctx.fillText(count.format(ranked[index].value), bar.x + 8, bar.y));
      ctx.restore();
    }
  }];

  charts["dish-chart"] = new Chart(canvas.getContext("2d"), {
    type: "bar",
    data: {
      labels: ranked.map(dish => dish.name),
      datasets: [{
        label: "Portions sold",
        data: ranked.map(dish => dish.value),
        backgroundColor: ranked.map((dish, rank) => barColor(rank)),
        hoverBackgroundColor: ranked.map((dish, rank) => barColor(rank)),   // the colour stays; only the outline marks the active bar
        hoverBorderColor: ink,
        hoverBorderWidth: 2,
        borderRadius: 6,
        borderSkipped: false,
        maxBarThickness: 26,
        categoryPercentage: 0.82,
        barPercentage: 1
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      indexAxis: "y",
      layout: { padding: { right: 48 } },
      events: [],   // hover and tap come from chickyChart.pointer, which also finds a row from its label or figure
      plugins: {
        legend: { display: false },
        tooltip: {
          enabled: false,
          external({ chart, tooltip }) {
            const active = tooltip.getActiveElements();
            if (active.length) showDishTip(chart, ranked, total, active[0].index);
            else hideDishTip();
          }
        }
      },
      scales: {
        // The figure at the end of each bar replaces the axis.
        // Ends exactly at the biggest dish, so its bar fills the row (the default would round up to a "nice" number).
        x: { display: false, beginAtZero: true, max: Math.max(1, ...ranked.map(dish => dish.value)) },
        y: {
          grid: { display: false },
          border: { display: false },
          ticks: {
            color: ink,
            padding: 10,
            font: { size: 13 },
            autoSkip: false,   // every bar keeps its name, even when rows are close together
            // Chart.js gives a vertical axis at most half the chart's width, so long names wrap to two lines
            // that fit (and are cut with "…" only beyond that). The tooltip and the chart's label carry the full name.
            callback(value) {
              const { chart } = this;
              const room = (chart.width - 48) / 2 - 16;
              chart.ctx.save();
              chart.ctx.font = `13px ${Chart.defaults.font.family}`;
              const lines = fitLines(this.getLabelForValue(value), room, text => chart.ctx.measureText(text).width);
              chart.ctx.restore();
              return lines.length > 1 ? lines : lines[0];
            }
          }
        }
      }
    },
    plugins
  });

  const spoken = ranked.map(dish => `${dish.name} ${dish.value}`).join(", ");
  canvas.setAttribute("aria-label", `Bar chart of portions sold per dish, ${range}, most first. ${spoken}.`);
  // A long list scrolls inside its card: fade the bottom edge as a cue, and keep the focused row in view.
  const box = canvas.parentElement.parentElement;
  const markScrollable = () => box.classList.toggle("is-scrollable", box.scrollHeight - box.scrollTop - box.clientHeight > 4);
  box.onscroll = markScrollable;
  requestAnimationFrame(markScrollable);
  document.fonts?.ready.then(markScrollable);
  chickyChart.pointer(charts["dish-chart"], { axis: "y" });
  chickyChart.keyboard(charts["dish-chart"], index => `${ranked[index].name}: ${plural(ranked[index].value, "portion")}`, {
    start: "first",
    onShow(index) {
      const bar = charts["dish-chart"].getDatasetMeta(0).data[index];
      if (bar.y - 24 < box.scrollTop) box.scrollTop = Math.max(0, bar.y - 24);
      else if (bar.y + 24 > box.scrollTop + box.clientHeight) box.scrollTop = bar.y + 24 - box.clientHeight;
    }
  });
}

const WEEKDAYS = ["SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"];

// Whether the stall is closed that day: a weekly rest day or a one-off closed day.
function isClosed(isoDate, { restDays, closedDates }) {
  const weekday = WEEKDAYS[new Date(`${isoDate}T00:00:00Z`).getUTCDay()];
  return restDays.includes(weekday) || closedDates.some(closed => closed.date === isoDate);
}

function showEmpty(message) {
  document.getElementById("waste-note").textContent = "";
  document.getElementById("sold-out-note").textContent = "";
  document.getElementById("demand-note").textContent = message;
  document.getElementById("dish-note").textContent = message;
  document.getElementById("revenue-note").textContent = message;
  setTreemapMessage(message);
}

async function loadDashboard() {
  const profile = await requireLogin();

  if (!profile) {
    return;
  }

  if (profile.name) {
    document.getElementById("welcome-heading").textContent = `Welcome, ${profile.name}`;
  }

  const dishes = await apiRequest("/api/dishes");

  if (dishes.length === 0) {
    showEmpty("No dishes yet. Add one to see your sales here.");
    return;
  }

  const [closures, ...salesByDish] = await Promise.all([
    apiRequest("/api/closures"),
    ...dishes.map(dish => apiRequest(`/api/dishes/${dish.id}/sales`))
  ]);

  const latestDate = salesByDish
    .flat()
    .map(sale => sale.date)
    .sort()
    .at(-1);

  if (!latestDate) {
    showEmpty("No sales recorded yet.");
    return;
  }

  // The 7 days ending on the most recent recorded sale, so older datasets still show something.
  const days = Array.from({ length: 7 }, (_, index) => addDays(latestDate, index - 6));
  const firstDay = days[0];

  // A closed day with no sales is a gap, not a day with zero sales. Sales recorded on a closed
  // day mean the stall opened after all, so that day counts as open.
  const closedDays = days.filter(day =>
    isClosed(day, closures) && !salesByDish.flat().some(sale => sale.date === day)
  );

  const dailyTotals = days.map(day =>
    closedDays.includes(day)
      ? null
      : sum(salesByDish.flat().filter(sale => sale.date === day).map(sale => sale.quantitySold))
  );

  const dishTotals = salesByDish.map(sales =>
    sum(sales.filter(sale => sale.date >= firstDay && sale.date <= latestDate).map(sale => sale.quantitySold))
  );

  const range = formatRange(firstDay, latestDate);

  document.getElementById("demand-total").textContent = count.format(sum(dailyTotals));
  document.getElementById("demand-note").textContent = ["Portions per day", range, closedDays.length ? "closed days left out" : null]
    .filter(Boolean).join(" · ");
  document.getElementById("dish-note").textContent = `Portions sold · ${range}`;

  const dayLabel = day => formatDay(day, { weekday: "short" });
  drawDemandChart({
    labels: days.map(day => closedDays.includes(day) ? `${dayLabel(day)} (closed)` : dayLabel(day)),
    dates: days.map(day => formatDay(day, { weekday: "long", day: "numeric", month: "short" })),
    values: dailyTotals
  });

  loadRevenue(firstDay, latestDate).catch(error => {
    console.error(error);
    document.getElementById("revenue-note").textContent = "Revenue could not be loaded.";
    setTreemapMessage("Revenue could not be loaded.");
  });
  loadWaste(firstDay, latestDate).catch(error => {
    console.error(error);
    document.getElementById("waste-note").textContent = "Leftovers could not be loaded.";
  });

  // Most demand first; dishes with the same demand stay in name order.
  const ranked = dishes.map((dish, index) => ({ name: dish.name, value: dishTotals[index] }))
    .sort((a, b) => b.value - a.value || a.name.localeCompare(b.name));
  drawDishChart(ranked, range);
}

// ---- Revenue, gross profit and revenue by dish (issue #43) ----

const money = new Intl.NumberFormat("en-SG", { style: "currency", currency: "SGD", currencyDisplay: "narrowSymbol" });
const wholeMoney = new Intl.NumberFormat("en-SG", { style: "currency", currency: "SGD", currencyDisplay: "narrowSymbol", maximumFractionDigits: 0 });

async function loadRevenue(from, to) {
  const revenue = await apiRequest(`/api/revenue?from=${from}&to=${to}`);
  const total = document.getElementById("revenue-total");
  const profit = document.getElementById("profit-total");
  const note = document.getElementById("revenue-note");
  const missing = document.getElementById("revenue-missing");
  const unpriced = revenue.dishes.filter(dish => dish.price === null && dish.portions > 0);
  const revenueValue = revenue.revenue === null ? null : Number(revenue.revenue);

  if (revenueValue === null) {
    total.textContent = "--";
    profit.textContent = "--";
    note.textContent = "Set your dish prices to see revenue.";
    setTreemapMessage("Set your dish prices to see revenue by dish.");
  } else {
    total.textContent = wholeMoney.format(revenueValue);
    if (revenue.grossProfit === null) {
      profit.textContent = "--";
      note.textContent = "Add each dish’s cost to see gross profit.";
    } else {
      const grossProfit = Number(revenue.grossProfit);
      profit.textContent = wholeMoney.format(grossProfit);
      note.textContent = [revenueValue > 0 ? `${Math.round(grossProfit / revenueValue * 100)}% margin` : null,
        revenue.portionsWithoutCost ? "dishes without a cost left out" : null].filter(Boolean).join(" · ");
    }
    // Each dish's revenue is what the server worked out at the price on each sale day, and they add up to the total.
    renderTreemap(revenue.dishes.filter(dish => dish.revenue !== null && Number(dish.revenue) > 0)
      .map(dish => ({ name: dish.name, value: Number(dish.revenue) })), revenueValue);
  }

  missing.replaceChildren();
  missing.hidden = unpriced.length === 0;
  if (unpriced.length) {
    const names = unpriced.map(dish => dish.name);
    const list = names.length > 3 ? `${names.slice(0, 3).join(", ")} and ${names.length - 3} more` : names.join(", ");
    const link = document.createElement("a");
    link.href = "dishes.html";
    link.textContent = "Set prices";
    missing.append(`Price not set: ${list}, so ${revenue.unpricedPortions} portions aren’t counted. `, link);
  }
}

// ---- The revenue treemap ----
// One rectangle per dish, its area proportional to that dish's revenue, largest first. Drawn as plain
// elements (not a canvas) so every tile can be focused, read by a screen reader and sized to its own text.

const treemap = document.getElementById("revenue-treemap");
const treemapEmpty = document.getElementById("treemap-empty");
const treemapTip = document.getElementById("treemap-tip");
const treemapKey = document.getElementById("treemap-key");
// Revenue figures grow with the dish's share: 16px (--amount-min) at TREEMAP_SHARE_RANGE[0] percent and below, 30px
// (--amount-max) at TREEMAP_SHARE_RANGE[1] and above, in a straight line between. Only the type is clamped; the tile's
// area is always the dish's exact share. The pixel bounds are CSS tokens (css/dashboard.css), the percentage bounds are here.
const TREEMAP_SHARE_RANGE = [4, 35];
let treemapDishes = [];   // [{ name, value, dollars }], most revenue first
let treemapTotal = 0;
let treemapTiles = [];    // what is on screen, in the same order (each with its rank)
let treemapDrawn = "";    // which dishes are on screen, so that resizing only moves them

// Shows a message where the tiles would be: loading, nothing sold yet, or prices missing.
function setTreemapMessage(message) {
  treemapDishes = [];
  treemapTiles = [];
  treemapDrawn = "";
  treemap.replaceChildren();
  treemapKey.replaceChildren();
  treemapKey.hidden = true;
  hideTreemapTip();
  treemapEmpty.textContent = message;
  treemapEmpty.hidden = false;
}

// Whole dollars that add up exactly to the rounded total: the largest remainders are rounded up. Without
// this the tile figures could differ from the total above them by a dollar or two.
function apportionDollars(values, totalDollars) {
  const dollars = values.map(value => Math.floor(value));
  let remainder = totalDollars - sum(dollars);
  values.map((value, index) => [value - dollars[index], index]).sort((a, b) => b[0] - a[0])
    .forEach(([, index]) => { if (remainder > 0) { dollars[index]++; remainder--; } });
  return dollars;
}

function renderTreemap(dishes, total) {
  dishes.sort((a, b) => b.value - a.value || a.name.localeCompare(b.name));
  const dishTotal = sum(dishes.map(dish => dish.value));
  if (Math.abs(dishTotal - total) > 0.005) console.warn(`Dish revenue (${dishTotal}) doesn't add up to the total (${total}).`);
  if (!dishes.length) {
    setTreemapMessage("No revenue in these 7 days.");
    return;
  }
  const dollars = apportionDollars(dishes.map(dish => dish.value), Math.round(dishTotal));
  treemapDishes = dishes.map((dish, index) => ({ ...dish, dollars: dollars[index], rank: index }));
  treemapTotal = dishTotal;
  treemapEmpty.hidden = true;
  treemapDrawn = "";
  layoutTreemap();
}

// ---- Layout ----
// Every layout below gives each dish a rectangle whose area is exactly its share of the box, and the same data always
// gives the same picture. Which one is used depends on how many dishes there are (see layoutMosaic).

// Squarified layout (Bruls, Huizing and van Wijk, 2000): fill the box row by row, keeping each tile as close to square as
// possible. Used for the many small dishes in the middle of a pinwheel, and for a long list of near-equal dishes.
function squarify(items, box) {
  const placed = [];
  let { x, y, w, h } = box;
  const scale = box.w * box.h / sum(items.map(item => item.value));
  let rest = items.slice();
  while (rest.length && w > 0 && h > 0) {
    const side = Math.min(w, h);
    const worst = row => {
      const areas = row.map(item => item.value * scale);
      const area = sum(areas);
      return Math.max(side * side * Math.max(...areas) / (area * area), area * area / (side * side * Math.min(...areas)));
    };
    const row = [rest[0]];
    while (row.length < rest.length && worst([...row, rest[row.length]]) <= worst(row)) row.push(rest[row.length]);
    const rowArea = sum(row.map(item => item.value * scale));
    if (w >= h) {
      const rowWidth = rowArea / h;
      let top = y;
      row.forEach(item => { const height = item.value * scale / rowWidth; placed.push({ item, x, y: top, w: rowWidth, h: height }); top += height; });
      x += rowWidth; w -= rowWidth;
    } else {
      const rowHeight = rowArea / w;
      let left = x;
      row.forEach(item => { const width = item.value * scale / rowHeight; placed.push({ item, x: left, y, w: width, h: rowHeight }); left += width; });
      y += rowHeight; h -= rowHeight;
    }
    rest = rest.slice(row.length);
  }
  return placed;
}

// A binary split (the "binary" layout of d3-hierarchy), for two to four dishes: the dishes, sorted by revenue, are
// divided into two groups at the point that splits the revenue most evenly, the box is cut across its longer side in
// that proportion, and each half is divided again the same way.
function splitTreemap(items, box, placed = []) {
  if (items.length === 1) {
    placed.push({ item: items[0], ...box });
    return placed;
  }
  const total = sum(items.map(item => item.value));
  let running = 0, split = 1, gap = Infinity;
  for (let index = 1; index < items.length; index++) {
    running += items[index - 1].value;
    if (Math.abs(total - 2 * running) < gap) { gap = Math.abs(total - 2 * running); split = index; }
  }
  const first = items.slice(0, split);
  const share = sum(first.map(item => item.value)) / total;
  if (box.w >= box.h) {
    const width = box.w * share;
    splitTreemap(first, { x: box.x, y: box.y, w: width, h: box.h }, placed);
    splitTreemap(items.slice(split), { x: box.x + width, y: box.y, w: box.w - width, h: box.h }, placed);
  } else {
    const height = box.h * share;
    splitTreemap(first, { x: box.x, y: box.y, w: box.w, h: height }, placed);
    splitTreemap(items.slice(split), { x: box.x, y: box.y + height, w: box.w, h: box.h - height }, placed);
  }
  return placed;
}

// A pinwheel: the four biggest dishes wind round a centre, and everything else shares the centre. No cut runs the full
// length of the box, so there is no column or row to see; every boundary is an offset T-junction, with wide and tall
// tiles mixed:
//        a                      A  top left, wide      [0,a] x [0,p]
//   +----------+-----+          B  right, tall         [a,W] x [0,q]
//   |    A     |  B  |  p       C  bottom right, wide  [c,W] x [q,H]
//   +---+------+     |          D  left, tall          [0,c] x [p,H]
//   | D |  E   +-----+  q       E  the centre          [c,a] x [p,q]
//   |   |      |  C  |
//   +---+------+-----+
// The four areas fix the four cuts: p = A/a, q = B/(W-a), c = D/(H-p), and `a` is the value that makes C's area right,
// found by bisection. Returns null when no good pinwheel exists (the leaders are too small or too lopsided to wind
// round a centre without slivers), and the caller falls back to another layout.
function pinwheel(items, box) {
  const { w: W, h: H } = box;
  const area = W * H, total = sum(items.map(item => item.value));
  const [A, B, C, D] = items.slice(0, 4).map(item => item.value / total * area);
  if ((area - A - B - C - D) < area * 0.015) return null;          // nothing left for a centre
  const cuts = a => { const p = A / a, q = B / (W - a); return { p, q, c: D / (H - p) }; };
  const gap = a => { const { q, c } = cuts(a); return (W - c) * (H - q) - C; };
  const sound = a => { const { p, q, c } = cuts(a); return p > 0 && q < H && p < q && c > 0 && c < a; };
  let best = null, previous = A / H + 1e-6;
  const last = W - B / H - 1e-6;
  for (let step = 1; step <= 400; step++) {
    const a = A / H + 1e-6 + (last - (A / H + 1e-6)) * step / 400;
    if (isFinite(gap(previous)) && isFinite(gap(a)) && gap(previous) * gap(a) <= 0) {
      let low = previous, high = a;
      for (let round = 0; round < 60; round++) { const mid = (low + high) / 2; if (gap(low) * gap(mid) <= 0) high = mid; else low = mid; }
      const root = (low + high) / 2;
      if (sound(root)) {
        const { p, q, c } = cuts(root);
        const shapes = [[root, p], [W - root, q], [W - c, H - q], [c, H - p]];
        const worst = Math.max(...shapes.map(([width, height]) => Math.max(width / height, height / width)));
        if (!best || worst < best.worst) best = { a: root, p, q, c, worst };
      }
    }
    previous = a;
  }
  if (!best || best.worst > 4) return null;
  const { a, p, q, c } = best;
  const at = (item, x, y, w, h) => ({ item, x: box.x + x, y: box.y + y, w, h });
  const placed = [
    at(items[0], 0, 0, a, p), at(items[1], a, 0, W - a, q), at(items[2], c, q, W - c, H - q), at(items[3], 0, p, c, H - p)
  ];
  const rest = items.slice(4);
  const centre = { x: box.x + c, y: box.y + p, w: a - c, h: q - p };
  return placed.concat(rest.length === 1 ? [{ item: rest[0], ...centre }] : squarify(rest, centre));
}

// Which layout: two to four dishes are split in two, again and again. Five or more wind into a pinwheel when the
// leaders are big enough for it, and are squarified (rows of near-square tiles) when they are not, as with a long
// list of dishes that all earn about the same.
function layoutMosaic(items, box) {
  if (items.length < 5) return splitTreemap(items, box);
  return pinwheel(items, box) ?? squarify(items, box);
}

// Tile colours by rank: the leader is the darkest teal, then the brand teal, then ever lighter tints of it
// (the text on all of them is at least 4.5:1).
function tileColors(rank) {
  if (rank === 0) return { background: cssVar("--heat-4"), color: "#fff" };
  if (rank === 1) return { background: cssVar("--teal-strong"), color: "#fff" };
  const strength = [0.4, 0.32, 0.26, 0.21, 0.17][rank - 2] ?? 0.14;
  return { background: `color-mix(in srgb, ${cssVar("--teal")} ${strength * 100}%, #fff)`, color: cssVar("--ink") };
}

const percentOf = value => {
  const share = value / treemapTotal * 100;
  return share < 0.5 ? "<1%" : `${Math.round(share)}%`;
};

const treemapSpoken = tile => `${tile.name}: ${wholeMoney.format(tile.dollars)}, ${percentOf(tile.value)} of revenue.`;

// 0 to 1: where this share sits between the smallest and largest sizes of the revenue figure.
function amountScale(value) {
  const [low, high] = TREEMAP_SHARE_RANGE;
  return Math.min(1, Math.max(0, (value / treemapTotal * 100 - low) / (high - low)));
}

function buildTreemap() {
  treemap.replaceChildren(...treemapTiles.map((tile, index) => {
    const item = document.createElement("li");
    item.className = "tile";
    item.tabIndex = 0;
    item.dataset.index = index;
    const body = document.createElement("span");
    body.className = "tile-body";
    body.setAttribute("aria-hidden", "true");
    Object.assign(body.style, tileColors(tile.rank));
    body.style.setProperty("--scale", amountScale(tile.value).toFixed(3));
    const name = document.createElement("span");
    name.className = "tile-name";
    name.textContent = tile.name;
    const value = document.createElement("span");
    value.className = "tile-value";
    value.textContent = wholeMoney.format(tile.dollars);
    const share = document.createElement("span");
    share.className = "tile-share";
    share.textContent = percentOf(tile.value);
    const key = document.createElement("span");
    key.className = "tile-key";
    key.textContent = tile.rank + 1;
    body.append(name, value, share, key);
    const spoken = document.createElement("span");
    spoken.className = "visually-hidden";
    spoken.textContent = treemapSpoken(tile);
    item.append(body, spoken);
    return item;
  }));
}

// A tile whose name, figure and share don't all fit shows just its number, and the dish is listed under the
// treemap with the same three pieces of information, always visible.
function fitTreemapLabels() {
  const compact = [];
  // Height is judged on the tile's own box (a glyph's ink can overhang its line box without being cut); width on each
  // piece, since a long word or the figure can run past the edge.
  const wider = element => element.scrollWidth > element.clientWidth + 1;
  const overflows = body => body.scrollHeight > body.clientHeight + 1 || wider(body) || [...body.children].some(child => child.className !== "tile-key" && wider(child));
  // Each tile is tried as stacked (name, figure, share), then with tighter padding, then side by side (name on the left,
  // figure and share on the right, for wide shallow tiles), then both; only a tile that fits none of them goes to the key.
  const attempts = [[], ["tight"], ["row"], ["tight", "row"]];
  [...treemap.children].forEach((item, index) => {
    ["compact", "tiny", "row", "tight"].forEach(flag => delete item.dataset[flag]);
    const body = item.firstElementChild;
    const fits = attempts.some(flags => {
      ["row", "tight"].forEach(flag => delete item.dataset[flag]);
      flags.forEach(flag => { item.dataset[flag] = ""; });
      return !overflows(body);
    });
    if (!fits) {
      ["row", "tight"].forEach(flag => delete item.dataset[flag]);
      item.dataset.compact = "";
      if (Math.min(item.offsetWidth, item.offsetHeight) < 24) item.dataset.tiny = "";
      compact.push(treemapTiles[index]);
    }
  });
  treemapKey.hidden = compact.length === 0;
  treemapKey.replaceChildren(...compact.map(tile => {
    const row = document.createElement("li");
    const swatch = document.createElement("span");
    swatch.className = "key-swatch";
    swatch.textContent = tile.rank + 1;
    Object.assign(swatch.style, tileColors(tile.rank));
    const name = document.createElement("span");
    name.className = "key-name";
    name.textContent = tile.name;
    const figures = document.createElement("span");
    figures.className = "key-figures";
    figures.textContent = `${wholeMoney.format(tile.dollars)} · ${percentOf(tile.value)}`;
    row.append(swatch, name, figures);
    return row;
  }));
}

function layoutTreemap() {
  if (!treemapDishes.length) return;
  const { width, height } = treemap.getBoundingClientRect();
  if (width < 40 || height < 40) return;

  const signature = treemapDishes.map(dish => `${dish.name}:${dish.value}`).join("|");
  if (signature !== treemapDrawn) {
    treemapDrawn = signature;
    treemapTiles = treemapDishes;
    buildTreemap();
  }
  layoutMosaic(treemapTiles, { x: 0, y: 0, w: width, h: height }).forEach(({ item, x, y, w, h }) => {
    Object.assign(treemap.children[treemapTiles.indexOf(item)].style, { left: `${x}px`, top: `${y}px`, width: `${w}px`, height: `${h}px` });
  });
  fitTreemapLabels();
}

function showTreemapTip(element) {
  const tile = treemapTiles[Number(element.dataset.index)];
  if (!tile) return;
  const title = document.createElement("strong");
  title.textContent = tile.name;
  const figures = document.createElement("span");
  figures.textContent = `${wholeMoney.format(tile.dollars)} · ${percentOf(tile.value)} of revenue`;
  const lines = [title, figures];
  treemapTip.replaceChildren(...lines);
  treemapTip.hidden = false;
  // On the tile itself (its own details replace its label), centred and kept inside the treemap.
  const wrap = treemap.parentElement.getBoundingClientRect();
  const box = element.getBoundingClientRect();
  const clamp = (value, max) => Math.min(Math.max(value, 0), Math.max(0, max));
  treemapTip.style.left = `${clamp(box.left - wrap.left + box.width / 2 - treemapTip.offsetWidth / 2, wrap.width - treemapTip.offsetWidth)}px`;
  treemapTip.style.top = `${clamp(box.top - wrap.top + 6, wrap.height - treemapTip.offsetHeight)}px`;
}

function hideTreemapTip() {
  treemapTip.hidden = true;
}

const tileOf = event => event.target.closest?.(".tile");
treemap.addEventListener("pointerover", event => { if (event.pointerType === "mouse" && tileOf(event)) showTreemapTip(tileOf(event)); });
treemap.addEventListener("pointerout", event => { if (event.pointerType === "mouse" && !treemap.contains(document.activeElement)) hideTreemapTip(); });
treemap.addEventListener("focusin", event => { if (tileOf(event)) showTreemapTip(tileOf(event)); });
treemap.addEventListener("focusout", hideTreemapTip);
// Touch screens: a tap shows the tile's details (and a tap anywhere else hides them).
treemap.addEventListener("click", event => { if (tileOf(event)) showTreemapTip(tileOf(event)); });
document.addEventListener("pointerdown", event => { if (!treemap.contains(event.target)) hideTreemapTip(); });
treemap.addEventListener("keydown", event => { if (event.key === "Escape") hideTreemapTip(); });
new ResizeObserver(() => requestAnimationFrame(layoutTreemap)).observe(treemap);
// Which labels fit depends on the web font's metrics, so check again once it has loaded.
document.fonts?.ready.then(() => requestAnimationFrame(layoutTreemap));

// ---- Leftovers and sell-outs (issue #50) ----

async function loadWaste(from, to) {
  const waste = await apiRequest(`/api/waste?from=${from}&to=${to}`);
  const note = document.getElementById("waste-note");
  document.getElementById("waste-total").textContent = waste.daysRecorded ? count.format(waste.leftoverPortions) : "--";

  if (!waste.daysRecorded) {
    note.textContent = "Record leftovers and sell-outs to see them here.";
  } else {
    const withLeftovers = waste.dishes.filter(dish => dish.leftoverPortions > 0);
    const cost = waste.leftoverCost === null ? null
      : `${money.format(Number(waste.leftoverCost))} of food not sold${waste.leftoversWithoutCost ? " (dishes without a cost left out)" : ""}`;
    const most = withLeftovers.slice(0, 3).map(dish => `${dish.name} (${dish.leftoverPortions})`).join(", ");
    note.textContent = [cost, most].filter(Boolean).join(" · ") || "No leftovers recorded.";
  }

  const soldOut = waste.dishes.filter(dish => dish.soldOutDays > 0).sort((a, b) => b.soldOutDays - a.soldOutDays);
  const list = document.getElementById("sold-out-dishes");
  list.replaceChildren(...soldOut.slice(0, 5).map(dish => {
    const item = document.createElement("li");
    const name = document.createElement("span");
    name.textContent = dish.name;
    const detail = document.createElement("span");
    detail.textContent = `${dish.soldOutDays} ${dish.soldOutDays === 1 ? "day" : "days"} · earliest ${dish.earliestSoldOutAt}`;
    item.append(name, detail);
    return item;
  }));
  document.getElementById("sold-out-note").textContent = soldOut.length
    ? "More people wanted it than you made."
    : "No dish sold out.";
}

// ---- Revenue by day: a GitHub-style heatmap ----

const WEEKS = 53;
const heatmap = document.getElementById("heatmap");
const heatmapTooltip = document.getElementById("heatmap-tooltip");

function singaporeToday() {
  return new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Singapore" });
}

// 0 for Monday … 6 for Sunday.
function weekdayIndex(isoDate) {
  return (new Date(`${isoDate}T00:00:00Z`).getUTCDay() + 6) % 7;
}

async function loadHeatmap() {
  const today = singaporeToday();
  const from = addDays(today, -weekdayIndex(today) - (WEEKS - 1) * 7);
  const daily = await apiRequest(`/api/revenue/daily?from=${from}&to=${today}`);
  renderHeatmap(daily, today);
}

function renderHeatmap(daily, today) {
  // Revenue when prices are set; until then portions, so the map is still useful.
  const byRevenue = daily.anyPrices;
  const valueOf = day => byRevenue ? (day.revenue === null ? 0 : Number(day.revenue)) : day.portions;
  const show = value => byRevenue ? money.format(value) : `${value} portions`;
  const days = daily.days.filter(day => day.date <= today);

  // Four levels by quartile of the stall's own days with sales, as on GitHub.
  const values = days.map(valueOf).filter(value => value > 0).sort((a, b) => a - b);
  const quartile = q => values[Math.min(values.length - 1, Math.floor(q * values.length))];
  const thresholds = values.length ? [quartile(0.25), quartile(0.5), quartile(0.75)] : [];
  const level = value => value <= 0 ? 0 : 1 + thresholds.filter(threshold => value > threshold).length;

  const nodes = [];
  let lastMonth = null;
  for (let week = 0; week < WEEKS; week++) {
    const monday = addDays(daily.from, week * 7);
    const month = monday.slice(0, 7);
    // A month starting in the last two weeks has no room for its label, which would stick out past
    // the grid and make it scroll, cutting off the weekday labels.
    if (month !== lastMonth && Number(monday.slice(8)) <= 7 && week < WEEKS - 2) {
      const label = document.createElement("span");
      label.className = "heatmap-month";
      label.style.gridColumn = String(week + 2);
      label.textContent = formatDay(monday, { month: "short" });
      nodes.push(label);
    }
    lastMonth = month;
  }
  ["Mon", "Wed", "Fri"].forEach((name, index) => {
    const label = document.createElement("span");
    label.className = "heatmap-weekday";
    label.style.gridRow = String(index * 2 + 2);
    label.textContent = name;
    nodes.push(label);
  });

  days.forEach((day, index) => {
    const value = valueOf(day);
    const cell = document.createElement("span");
    cell.className = `heat-cell level-${level(value)}`;
    if (value <= 0 && day.closed) cell.classList.add("is-closed");
    cell.style.gridColumn = String(Math.floor(index / 7) + 2);
    cell.style.gridRow = String((index % 7) + 2);
    cell.dataset.value = value > 0 ? show(value) : day.closed ? "Closed" : "No sales";
    cell.dataset.day = formatDay(day.date, { weekday: "short", day: "numeric", month: "short", year: "numeric" });
    nodes.push(cell);
  });
  heatmap.replaceChildren(...nodes);
  heatmap.style.setProperty("--weeks", WEEKS);

  const best = days.reduce((top, day) => valueOf(day) > valueOf(top) ? day : top, days[0]);
  const total = days.reduce((sum, day) => sum + valueOf(day), 0);
  const note = document.getElementById("heatmap-note");
  if (!values.length) {
    note.textContent = "No sales in the last 53 weeks yet.";
  } else if (byRevenue) {
    note.textContent = `${wholeMoney.format(total)} in 53 weeks · best day ${wholeMoney.format(valueOf(best))} on ${formatDay(best.date, { weekday: "short", day: "numeric", month: "short" })}`;
  } else {
    note.textContent = "Shaded by portions until prices are set on the Dishes page.";
  }
  heatmap.setAttribute("aria-label", values.length
    ? `Revenue by day for the last 53 weeks. ${note.textContent}. The table below lists each month.`
    : "Revenue by day for the last 53 weeks: no sales yet.");
  renderHeatmapTable(days, valueOf, show, byRevenue);

  const scroll = document.getElementById("heatmap-scroll");
  scroll.scrollLeft = scroll.scrollWidth; // phones: start at the latest weeks
}

function renderHeatmapTable(days, valueOf, show, byRevenue) {
  document.getElementById("heatmap-total-heading").textContent = byRevenue ? "Revenue" : "Portions";
  const months = new Map();
  days.forEach(day => {
    const month = months.get(day.date.slice(0, 7)) ?? { total: 0, days: 0, first: day.date };
    month.total += valueOf(day);
    if (valueOf(day) > 0) month.days++;
    months.set(day.date.slice(0, 7), month);
  });
  document.getElementById("heatmap-table-body").replaceChildren(...[...months.values()].reverse().map(month => {
    const row = document.createElement("tr");
    [formatDay(month.first, { month: "long", year: "numeric" }), month.total ? show(month.total) : "–", String(month.days)]
      .forEach(text => {
        const cell = document.createElement("td");
        cell.textContent = text;
        row.append(cell);
      });
    return row;
  }));
}

// One tooltip for the whole grid, value first: "$1,080 · Sat 24 Oct 2026".
heatmap.addEventListener("pointerover", event => {
  const cell = event.target.closest(".heat-cell");
  if (!cell) return;
  const value = document.createElement("strong");
  value.textContent = cell.dataset.value;
  heatmapTooltip.replaceChildren(value, ` · ${cell.dataset.day}`);
  heatmapTooltip.hidden = false;
  const card = heatmap.closest(".heatmap-card").getBoundingClientRect();
  const box = cell.getBoundingClientRect();
  const left = Math.min(Math.max(box.left - card.left + box.width / 2 - heatmapTooltip.offsetWidth / 2, 8),
    card.width - heatmapTooltip.offsetWidth - 8);
  heatmapTooltip.style.left = `${left}px`;
  heatmapTooltip.style.top = `${box.top - card.top - heatmapTooltip.offsetHeight - 6}px`;
});
heatmap.addEventListener("pointerleave", () => { heatmapTooltip.hidden = true; });

// ---- The week in a few sentences, played aloud (issue #56) ----

const weekText = document.getElementById("week-summary-text");
const weekButtons = document.getElementById("week-summary-buttons");
const weekStatus = document.getElementById("week-summary-status");
let weekSummary = null;

async function loadWeekSummary() {
  try {
    weekSummary = await apiRequest("/api/summary/weekly");
    showWeekSummary("en");
    // Play needs the server's voice (an OpenAI key); without it the summary is still there to read.
    weekButtons.hidden = !weekSummary.from || !await window.chickyAssistant?.voiceAvailable();
  } catch (error) {
    if (error.status !== 401) weekText.textContent = "The summary could not be loaded.";
  }
}

// The dashboard's short version of the week, built from the summary's own figures (the server's longer
// text, which the assistant also reads out, is used only when there is no week to describe). Revenue and
// the best seller are left out: the cards below already show them.
function weekSentence(summary, language) {
  if (!summary.from) return summary.text[language];
  const { portions, changePercent, wasteRecorded, leftoverPortions, mostSoldOut } = summary;
  const day = new Date(`${summary.to}T00:00:00Z`);

  if (language === "zh") {
    const date = day.toLocaleDateString("zh-CN", { month: "long", day: "numeric", weekday: "long", timeZone: "UTC" });
    const change = changePercent === null ? "" : changePercent > 0 ? `，比前一周多${changePercent}%`
      : changePercent < 0 ? `，比前一周少${-changePercent}%` : "，和前一周一样";
    return [`截至${date}这一周，你卖了${count.format(portions)}份${change}。`,
      wasteRecorded ? (leftoverPortions === 0 ? "没有剩下的。" : `剩下${count.format(leftoverPortions)}份。`) : "",
      mostSoldOut ? `${mostSoldOut.name}有${mostSoldOut.count}天卖完。` : ""].join("");
  }

  const date = formatDay(summary.to, { weekday: "long", day: "numeric", month: "short" });
  const change = changePercent === null ? "" : changePercent > 0 ? `, up ${changePercent}% from the previous week`
    : changePercent < 0 ? `, down ${-changePercent}% from the previous week` : ", the same as the previous week";
  return [`In the week up to ${date}, you sold ${plural(portions, "portion")}${change}.`,
    wasteRecorded ? (leftoverPortions === 0 ? "Nothing was left over." : `${plural(leftoverPortions, "portion")} left over.`) : "",
    mostSoldOut ? `${mostSoldOut.name} sold out on ${plural(mostSoldOut.count, "day")}.` : ""].filter(Boolean).join(" ");
}

function showWeekSummary(language) {
  weekText.textContent = weekSentence(weekSummary, language);
  weekText.lang = language;
}

weekButtons.addEventListener("click", async event => {
  const button = event.target.closest("button[data-language]");
  if (!button || !weekSummary) return;
  const language = button.dataset.language;
  showWeekSummary(language);
  weekStatus.textContent = language === "zh" ? "正在准备语音…" : "Getting the audio…";
  const played = await window.chickyAssistant?.play(weekSentence(weekSummary, language));
  weekStatus.textContent = played ? "" : "It couldn’t be played just now, but the summary is above.";
});

// ---- Actions needed (issue #53) ----

const actionList = document.getElementById("action-list");
const actionsNote = document.getElementById("actions-note");

async function loadActions() {
  try {
    renderActions(await apiRequest("/api/actions"));
  } catch (error) {
    if (error.status !== 401) actionsNote.textContent = "Actions could not be loaded.";
  }
}

function renderActions(cards) {
  actionList.replaceChildren(...cards.map(actionItem));
  actionsNote.textContent = cards.length ? "" : "Nothing needs your attention right now.";
}

function actionItem(card) {
  const item = document.createElement("li");
  item.className = "action-item";

  const text = document.createElement("div");
  text.className = "action-text";
  const title = document.createElement("strong");
  title.textContent = card.title;
  const detail = document.createElement("span");
  detail.textContent = card.detail;
  text.append(title, detail);

  const buttons = document.createElement("div");
  buttons.className = "action-buttons";
  const act = document.createElement("button");
  act.type = "button";
  act.className = "secondary-button";
  act.textContent = card.actionLabel;
  act.addEventListener("click", () => {
    if (card.recordDate) openRecordDialog(card.recordDate);
    else window.location.href = card.href;
  });
  const dismiss = document.createElement("button");
  dismiss.type = "button";
  dismiss.className = "text-button";
  dismiss.textContent = "Dismiss";
  dismiss.setAttribute("aria-label", `Dismiss: ${card.title}`);
  dismiss.addEventListener("click", async () => {
    dismiss.disabled = true;
    try {
      await apiRequest("/api/actions/dismiss", { method: "POST", body: JSON.stringify({ key: card.key }) });
      item.remove();
      if (!actionList.children.length) actionsNote.textContent = "Nothing needs your attention right now.";
    } catch (error) {
      dismiss.disabled = false;
      actionsNote.textContent = error.message || "That card could not be dismissed.";
    }
  });
  buttons.append(act, dismiss);

  item.append(text, buttons);
  return item;
}

// ---- Recording a day's sales, sell-outs and leftovers ----

const recordDialog = document.getElementById("record-dialog");
const recordForm = document.getElementById("record-form");
const recordDateInput = document.getElementById("record-date");
const recordList = document.getElementById("record-list");
const recordStatus = document.getElementById("record-status");
const recordSave = document.getElementById("record-save");

function showRecordStatus(message) {
  recordStatus.textContent = message;
  recordStatus.className = message ? "status-text is-error" : "status-text";
}

function recordInput(attributes, label) {
  const input = document.createElement("input");
  input.className = "input";
  Object.entries(attributes).forEach(([name, value]) => input.setAttribute(name, value));
  input.setAttribute("aria-label", label);
  return input;
}

function recordField(text, input) {
  const field = document.createElement("label");
  field.className = "record-field";
  const caption = document.createElement("span");
  caption.textContent = text;
  field.append(caption, input);
  return field;
}

async function openRecordDialog(date = singaporeToday()) {
  recordDateInput.value = date;
  recordDateInput.max = singaporeToday();
  showRecordStatus("");
  recordList.replaceChildren();
  recordSave.disabled = false;
  recordDialog.showModal();
  await fillRecordList();
  recordList.querySelector("input")?.focus();
}

// One row per dish, filled with what's already recorded for the chosen day.
async function fillRecordList() {
  const date = recordDateInput.value;
  const dishes = await apiRequest("/api/dishes");
  const sales = await Promise.all(dishes.map(dish => apiRequest(`/api/dishes/${dish.id}/sales`)));
  if (date !== recordDateInput.value) return; // the date changed while loading

  recordList.replaceChildren(...dishes.map((dish, index) => {
    const recorded = sales[index].find(sale => sale.date === date);
    const row = document.createElement("fieldset");
    row.className = "record-row";
    row.dataset.dishId = dish.id;
    const name = document.createElement("legend");
    name.textContent = dish.name;

    const portions = recordInput({ type: "number", min: "0", max: "9999", step: "1", inputmode: "numeric", placeholder: "0" },
      `Portions of ${dish.name} sold`);
    portions.dataset.field = "quantity";
    portions.value = recorded ? recorded.quantitySold : "";
    const soldOut = recordInput({ type: "time" }, `Time ${dish.name} sold out`);
    soldOut.dataset.field = "soldOutAt";
    soldOut.value = recorded?.soldOutAt ?? "";
    const leftover = recordInput({ type: "number", min: "0", max: "9999", step: "1", inputmode: "numeric" },
      `Portions of ${dish.name} left over`);
    leftover.dataset.field = "leftover";
    leftover.value = recorded?.leftoverQuantity ?? "";

    row.append(name, recordField("Sold", portions), recordField("Sold out at", soldOut), recordField("Left over", leftover));
    return row;
  }));
}

recordDateInput.addEventListener("change", () => {
  showRecordStatus("");
  if (recordDateInput.value) fillRecordList();
});

recordForm.addEventListener("submit", async event => {
  event.preventDefault();
  const rows = [...recordList.querySelectorAll(".record-row")].map(row => {
    const value = field => row.querySelector(`[data-field="${field}"]`).value;
    return { row, dishId: row.dataset.dishId, name: row.querySelector("legend").textContent,
      quantity: value("quantity"), soldOutAt: value("soldOutAt"), leftover: value("leftover") };
  });
  const filled = rows.filter(row => row.quantity !== "" || row.soldOutAt !== "" || row.leftover !== "");
  if (!filled.length) {
    showRecordStatus("Enter the portions of at least one dish.");
    return;
  }
  const missing = filled.find(row => row.quantity === "");
  if (missing) {
    showRecordStatus(`Enter the portions of ${missing.name} sold too.`);
    return;
  }
  const both = filled.find(row => row.soldOutAt !== "" && Number(row.leftover) > 0);
  if (both) {
    showRecordStatus(`${both.name} can’t have sold out and have leftovers. Clear one of them.`);
    return;
  }
  if (!recordForm.reportValidity()) return;

  recordSave.disabled = true;
  try {
    for (const row of filled) {
      await apiRequest(`/api/dishes/${row.dishId}/sales/${recordDateInput.value}`, {
        method: "PUT",
        body: JSON.stringify({
          quantitySold: Number(row.quantity),
          soldOutAt: row.soldOutAt || null,
          leftoverQuantity: row.leftover === "" ? null : Number(row.leftover)
        })
      });
    }
    recordDialog.close();
    refreshDashboard();
  } catch (error) {
    showRecordStatus(error.message || "The sales could not be saved.");
    recordSave.disabled = false;
  }
});

document.getElementById("record-open").addEventListener("click", () => openRecordDialog());
document.getElementById("record-cancel").addEventListener("click", () => recordDialog.close());

function refreshDashboard() {
  loadDashboard().catch(error => {
    console.error(error);
    showEmpty("Could not load sales. Is the backend running?");
  });
  loadActions();
  loadWeekSummary();
  loadHeatmap().catch(error => {
    console.error(error);
    document.getElementById("heatmap-note").textContent = "Revenue by day could not be loaded.";
  });
}

refreshDashboard();

// Sent by js/assistant.js when the user confirms sales.
window.addEventListener("chicky:sales-saved", refreshDashboard);
window.addEventListener("chicky:prices-saved", refreshDashboard);
