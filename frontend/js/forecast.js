// Demand forecast page.
//
// Stage 1: everything runs on MOCK DATA so the page can be designed and demoed
// without the backend. Each data function has its real API call written below
// the mock branch. When the backend endpoints exist, set USE_MOCK_DATA = false.
//
// NOTE on forecast periods (1, 2 or 4 days, set in forecast.html): the real
// backend (NeaWeatherForecastService) currently only accepts dates inside NEA's
// 24-hour forecast. "2 days" and "4 days" need it switched to NEA's 4-day
// outlook before they will work with real data.

const USE_MOCK_DATA = true;

// Enough history for the "last 7 days" view AND the last 4 same weekdays
// (the model's same-weekday features look back up to 4 weeks).
const HISTORY_DAYS = 35;

// Typical error of the model on held-out test data (ml/README.md: ~5 portions/day).
const TYPICAL_ERROR = 5;

const API_BASE_URL =
  document.querySelector('meta[name="api-base-url"]')?.content ?? "http://localhost:8080";

/* ------------------------------------------------------------------ */
/* Data layer                                                          */
/* ------------------------------------------------------------------ */

// Returns [{ id, name }]
// Needs a NEW backend endpoint: GET /api/dishes
async function getDishes() {
  if (USE_MOCK_DATA) {
    return [
      { id: 1, name: "Chicken Rice" },
      { id: 2, name: "Roasted Chicken Rice" },
      { id: 3, name: "Chicken Curry" },
      { id: 4, name: "Braised Egg" },
    ];
  }
  return apiRequest("/api/dishes");
}

// Returns [{ date: "YYYY-MM-DD", quantity }] for the last `days` days, oldest first
// Needs a NEW backend endpoint, e.g. GET /api/dishes/{id}/sales?days=35
async function getRecentSales(dishId, days) {
  if (USE_MOCK_DATA) {
    const rows = [];
    for (let i = days; i >= 1; i--) {
      const date = addDays(today(), -i);
      rows.push({ date: isoDate(date), quantity: mockDemand(dishId, date) });
    }
    return rows;
  }
  return apiRequest(`/api/dishes/${dishId}/sales?days=${days}`);
}

// Returns { date, predicted } for one date.
// Uses the EXISTING backend endpoint: POST /api/forecast { dishId, forecastDate }
async function getForecast(dishId, date) {
  if (USE_MOCK_DATA) {
    await wait(100);
    return { date, predicted: mockDemand(dishId, parseIso(date)) };
  }
  const result = await apiRequest("/api/forecast", {
    method: "POST",
    body: JSON.stringify({ dishId, forecastDate: date }),
  });
  return { date: result.forecast_date, predicted: result.predicted_demand };
}

// Returns { sky, tempC, rainLikely, holiday, event }
// The backend already fetches NEA weather + public holidays for each forecast;
// it would need to include them in the /api/forecast response.
function getFactors(date) {
  if (USE_MOCK_DATA) {
    const day = parseIso(date).getDate();
    const rainy = day % 3 === 0;
    return {
      sky: rainy ? "Showers" : day % 2 ? "Partly cloudy" : "Fair",
      tempC: rainy ? 28 : 31 + (day % 2),
      rainLikely: rainy,
      holiday: null,
      event: null,
    };
  }
  return { sky: "Not available", tempC: null, rainLikely: false, holiday: null, event: null };
}

// Returns an answer string.
// Real version needs a NEW backend endpoint, e.g.
//   POST /api/forecast/ask { dishId, forecastDate, question } -> { answer }
// The backend could answer with an LLM given the forecast inputs, or with
// rules like the mock below. Ask the ML side whether CatBoost feature
// importances (or SHAP values) can be returned to make answers more exact.
async function askAboutForecast(question, ctx) {
  if (USE_MOCK_DATA) {
    await wait(450);
    return mockAnswer(question, ctx);
  }
  const result = await apiRequest("/api/forecast/ask", {
    method: "POST",
    body: JSON.stringify({ dishId: ctx.dishId, forecastDate: ctx.date, question }),
  });
  return result.answer;
}

// Same request pattern as js/auth.js: cookies + CSRF header on non-GET calls.
let csrf = null;
async function apiRequest(path, options = {}) {
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  if (options.body) headers.set("Content-Type", "application/json");

  if (options.method && options.method !== "GET") {
    if (!csrf) {
      const res = await fetch(`${API_BASE_URL}/api/csrf`, { credentials: "include" });
      csrf = await res.json();
    }
    headers.set(csrf.headerName, csrf.token);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
    credentials: "include",
  });
  if (!response.ok) throw new Error(`Request to ${path} failed (${response.status}).`);
  return response.json();
}

/* ------------------------------------------------------------------ */
/* Mock helpers                                                        */
/* ------------------------------------------------------------------ */

// Believable fake demand: per-dish base level, busier Fri–Sun, a little noise.
function mockDemand(dishId, date) {
  const base = { 1: 62, 2: 38, 3: 24, 4: 45 }[dishId] ?? 30;
  const weekday = [1.25, 0.85, 0.9, 0.95, 1.0, 1.2, 1.35][date.getDay()]; // Sun..Sat
  const seed = dishId * 1000 + date.getDate() * 31 + date.getMonth() * 7;
  const noise = (seededRandom(seed) - 0.5) * 0.18;
  return Math.max(0, Math.round(base * weekday * (1 + noise)));
}
function seededRandom(seed) {
  const x = Math.sin(seed) * 10000;
  return x - Math.floor(x);
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

// Rule-based sample answers built from the numbers on screen.
function mockAnswer(question, ctx) {
  const q = question.toLowerCase();
  const has = (...words) => words.some((w) => q.includes(w));
  const { dish, predicted, weekdayPlural, sameDays, sameAvg, recentAvg, factors } = ctx;
  const when = longDate(ctx.date);
  const range = sameDays.length
    ? `${Math.min(...sameDays.map((d) => d.quantity))}–${Math.max(...sameDays.map((d) => d.quantity))}`
    : "";

  const vsPattern = () => {
    const diff = predicted - sameAvg;
    if (Math.abs(diff) <= 2) return `right in line with that`;
    return `${Math.abs(Math.round(diff))} ${diff > 0 ? "above" : "below"} that average`;
  };
  const weatherLine = () =>
    factors.rainLikely
      ? `Showers are expected (${factors.tempC}°C), and rain tends to pull walk-in numbers down a little.`
      : `The weather looks ${factors.sky.toLowerCase()} at around ${factors.tempC}°C, so there's no weather drag expected.`;

  if (has("accura", "trust", "sure", "confiden", "wrong", "error", "reliable")) {
    return `In the team's tests, the model was typically off by about ${TYPICAL_ERROR} portions a day. ` +
      `So for ${when}, treat ${predicted} as roughly ${Math.max(0, predicted - TYPICAL_ERROR)}–${predicted + TYPICAL_ERROR}. ` +
      `It's least reliable after unusual days (events, closures), since it learns from normal patterns.`;
  }
  if (has("prepare", "cook", "order", "stock", "buy", "how many should", "make")) {
    return `The forecast is ${predicted} portions for ${when}. Since the model is usually within about ${TYPICAL_ERROR} portions, ` +
      `preparing around ${predicted + TYPICAL_ERROR} covers most days without much waste. If you'd rather risk selling out than waste food, stick closer to ${predicted}.`;
  }
  if (has("weather", "rain", "hot", "sun", "cloud", "temperature")) {
    return `${weatherLine()} Weather is one of the model's inputs (temperature, rain, cloud, sunshine and wind), ` +
      `but for ${dish} it matters less than which day of the week it is.`;
  }
  if (has("holiday", "event", "festival", "concert")) {
    return `${when} ${factors.holiday ? `is ${factors.holiday}, which the model accounts for` : "isn't a public holiday"}. ` +
      `The model doesn't know about local events yet, so if something is happening nearby, it's worth adjusting the number yourself.`;
  }
  if (has("weekday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "last week", "past")) {
    const qs = sameDays.map((d) => d.quantity);
    const list = qs.length > 1 ? `${qs.slice(0, -1).join(", ")} and ${qs[qs.length - 1]}` : `${qs[0] ?? 0}`;
    return `Over the last 4 ${weekdayPlural} you sold ${list} portions (average ${Math.round(sameAvg)}), ` +
      `and the forecast for ${when} is ${predicted}. ` +
      `The model uses the average of the last 2, 3 and 4 ${weekdayPlural} as inputs, which is why the "Same weekday" chart is a good way to sanity-check the forecast.`;
  }
  if (has("why", "explain", "reason", "how come", "higher", "lower", "more", "less", "fewer", "increase", "drop", "number")) {
    return `The forecast of ${predicted} for ${when} leans mostly on past ${weekdayPlural}. ` +
      `Over the last 4 ${weekdayPlural} you sold ${range} (average ${Math.round(sameAvg)}), so ${predicted} is ${vsPattern()}. ` +
      `Your last 7 days averaged ${Math.round(recentAvg)}, which also feeds in. ${weatherLine()}`;
  }
  return `I can explain why the forecast for ${when} is ${predicted}, how weather or holidays affect it, how accurate it is, or how much to prepare. Try one of the suggestions below.`;
}

/* ------------------------------------------------------------------ */
/* Date helpers                                                        */
/* ------------------------------------------------------------------ */

function today() {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d;
}
function addDays(date, n) {
  const d = new Date(date);
  d.setDate(d.getDate() + n);
  return d;
}
function isoDate(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}
function parseIso(s) {
  const [y, m, d] = s.split("-").map(Number);
  return new Date(y, m - 1, d);
}
const shortDate = (s) => parseIso(s).toLocaleDateString("en-SG", { day: "numeric", month: "short" });
const longDate = (s) =>
  parseIso(s).toLocaleDateString("en-SG", { weekday: "short", day: "numeric", month: "short" });
const weekdayName = (s) => parseIso(s).toLocaleDateString("en-SG", { weekday: "long" });

/* ------------------------------------------------------------------ */
/* Page state + wiring                                                 */
/* ------------------------------------------------------------------ */

const els = {
  dish: document.getElementById("dish-select"),
  periods: document.querySelectorAll(".period-button"),
  views: document.querySelectorAll(".view-button"),
  weekdayViewButton: document.getElementById("weekday-view-button"),
  legend: document.getElementById("legend"),
  chart: document.getElementById("chart"),
  chartNote: document.getElementById("chart-note"),
  headline: document.getElementById("headline"),
  status: document.getElementById("status"),
  weather: document.getElementById("weather-list"),
  events: document.getElementById("event-list"),
  messages: document.getElementById("messages"),
  suggestions: document.getElementById("suggestions"),
  chatForm: document.getElementById("chat-form"),
  chatInput: document.getElementById("chat-input"),
  sidebar: document.getElementById("sidebar"),
  scrim: document.getElementById("scrim"),
  toggle: document.getElementById("sidebar-toggle"),
};

const state = {
  dishId: null,
  days: 1,
  view: "recent",     // "recent" | "weekday"
  focusDate: null,    // which forecast day the weekday chart, factors and chat are about
  past: [],
  forecast: [],       // today + next N days (today is only drawn on the chart)
  requestId: 0,
};

async function init() {
  setupSidebar();
  setupChat();

  try {
    const dishes = await getDishes();
    if (dishes.length === 0) {
      setStatus("Add a dish first to see its forecast.");
      return;
    }
    els.dish.innerHTML = dishes
      .map((d) => `<option value="${d.id}">${escapeHtml(d.name)}</option>`)
      .join("");
    state.dishId = Number(els.dish.value);
  } catch (err) {
    setStatus(`Could not load your dishes. ${err.message}`, true);
    return;
  }

  els.dish.addEventListener("change", () => {
    state.dishId = Number(els.dish.value);
    refresh();
  });

  els.periods.forEach((btn) =>
    btn.addEventListener("click", () => {
      els.periods.forEach((b) => b.setAttribute("aria-pressed", String(b === btn)));
      state.days = Number(btn.dataset.days);
      refresh();
    })
  );

  els.views.forEach((btn) =>
    btn.addEventListener("click", () => {
      els.views.forEach((b) => b.setAttribute("aria-pressed", String(b === btn)));
      state.view = btn.dataset.view;
      renderChart();
    })
  );

  // Redraw whenever the chart area changes size (window resize, chat growing, etc.)
  let lastSize = "";
  new ResizeObserver(() => {
    const size = `${els.chart.clientWidth}x${els.chart.clientHeight}`;
    if (size !== lastSize) { lastSize = size; renderChart(); }
  }).observe(els.chart);
  refresh();
}

function setupSidebar() {
  const open = (isOpen) => {
    els.sidebar.hidden = !isOpen;
    els.scrim.hidden = !isOpen;
    els.toggle.setAttribute("aria-expanded", String(isOpen));
    els.toggle.setAttribute("aria-label", isOpen ? "Close menu" : "Open menu");
    if (isOpen) els.sidebar.querySelector("a")?.focus();
  };
  els.toggle.addEventListener("click", () => open(els.sidebar.hidden));
  els.scrim.addEventListener("click", () => open(false));
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !els.sidebar.hidden) {
      open(false);
      els.toggle.focus();
    }
  });
}

async function refresh() {
  const requestId = ++state.requestId; // ignore stale responses if the user changes things quickly
  setStatus("Generating forecast…");

  try {
    const dates = [];
    for (let i = 0; i <= state.days; i++) dates.push(isoDate(addDays(today(), i)));

    const [past, forecast] = await Promise.all([
      getRecentSales(state.dishId, HISTORY_DAYS),
      Promise.all(dates.map((date) => getForecast(state.dishId, date))),
    ]);
    if (requestId !== state.requestId) return;

    state.past = past;
    state.forecast = forecast;
    state.focusDate = futureDays()[0].date;

    renderAll();
    setStatus(USE_MOCK_DATA ? "Showing sample data. Not connected to the forecasting model yet." : "");
  } catch (err) {
    if (requestId !== state.requestId) return;
    setStatus(`Could not generate the forecast. ${err.message}`, true);
  }
}

const futureDays = () => state.forecast.slice(1);
const focusDay = () => futureDays().find((d) => d.date === state.focusDate) ?? futureDays()[0];

// Last 4 same-weekday sales before the focus day (oldest first).
function sameWeekdaySales(date) {
  const out = [];
  for (let w = 4; w >= 1; w--) {
    const d = isoDate(addDays(parseIso(date), -7 * w));
    const row = state.past.find((r) => r.date === d);
    if (row) out.push(row);
  }
  return out;
}

function renderAll() {
  renderHeadline();
  renderChart();
  renderFactors();
  resetChat();
}

function setFocus(date) {
  state.focusDate = date;
  renderHeadline();
  renderChart();
  renderFactors();
  addDivider(`Now asking about ${longDate(date)}`);
  renderSuggestions();
}

function setStatus(text, isError = false) {
  els.status.textContent = text;
  els.status.classList.toggle("error", isError);
}

/* ------------------------------------------------------------------ */
/* Headline, factors                                                   */
/* ------------------------------------------------------------------ */

function currentDishName() {
  return els.dish.options[els.dish.selectedIndex]?.text ?? "";
}

function renderHeadline() {
  const days = futureDays();
  const dishName = escapeHtml(currentDishName());
  const total = Math.round(days.reduce((sum, d) => sum + d.predicted, 0));

  if (days.length === 1) {
    els.headline.innerHTML =
      `<span class="number">${total}</span>` +
      `${dishName} portions expected tomorrow (${longDate(days[0].date)}).`;
    return;
  }

  const chips = days
    .map(
      (d) =>
        `<li><button type="button" class="day-chip" data-date="${d.date}" aria-pressed="${d.date === state.focusDate}">` +
        `${longDate(d.date)}: <strong>${Math.round(d.predicted)}</strong></button></li>`
    )
    .join("");
  els.headline.innerHTML =
    `<span class="number">${total}</span>` +
    `${dishName} portions expected over the next ${days.length} days. Pick a day to look at it closely.` +
    `<ul class="day-breakdown">${chips}</ul>`;
  els.headline.querySelectorAll(".day-chip").forEach((chip) =>
    chip.addEventListener("click", () => setFocus(chip.dataset.date))
  );
}

function renderFactors() {
  const f = getFactors(state.focusDate);
  const item = (label, value) => `<li>${label}: <strong>${escapeHtml(value)}</strong></li>`;
  els.weather.innerHTML =
    item("Sky", f.sky) +
    (f.tempC != null ? item("Temp", `${f.tempC}°C`) : "") +
    item("Rain", f.rainLikely ? "Likely" : "Low");
  els.events.innerHTML =
    item("Nearby", f.event ?? "None listed") + item("Public holiday", f.holiday ?? "No");
}

/* ------------------------------------------------------------------ */
/* Charts (plain SVG, no library)                                      */
/* ------------------------------------------------------------------ */

function renderChart() {
  if (!state.forecast.length) return;
  const plural = `${weekdayName(state.focusDate)}s`;
  els.weekdayViewButton.textContent = `Past ${plural}`;

  if (state.view === "weekday") {
    els.legend.innerHTML =
      `<span class="legend-item"><span class="swatch swatch-bar"></span>Sold</span>` +
      `<span class="legend-item"><span class="swatch swatch-bar swatch-bar-forecast"></span>Forecast</span>` +
      `<span class="legend-item"><span class="swatch swatch-avg"></span>4-week average (${Math.round(avgOf(sameWeekdaySales(focusDay().date)))})</span>`;
    drawWeekdayChart();
  } else {
    els.legend.innerHTML =
      `<span class="legend-item"><span class="swatch swatch-actual"></span>Sold</span>` +
      `<span class="legend-item"><span class="swatch swatch-forecast"></span>Forecast</span>`;
    drawRecentChart();
  }
}

function chartSize() {
  const width = Math.max(280, els.chart.clientWidth);
  const height = Math.min(Math.max(240, els.chart.clientHeight), 520);
  return { width, height, narrow: width < 500 };
}

// View 1: last 7 days of sales, then the forecast line.
function drawRecentChart() {
  const { width, height, narrow } = chartSize();
  const pad = { top: 22, right: 18, bottom: 28, left: 50 };
  const innerW = width - pad.left - pad.right;
  const innerH = height - pad.top - pad.bottom;
  const todayIso = isoDate(today());

  const points = [
    ...state.past.slice(-7).map((r) => ({ date: r.date, value: r.quantity, kind: "actual" })),
    ...state.forecast.map((r) => ({ date: r.date, value: Math.round(r.predicted), kind: "forecast" })),
  ].map((p, i) => ({ ...p, i }));
  const n = points.length;
  const yMax = niceCeil(Math.max(...points.map((p) => p.value)) * 1.25);

  const x = (i) => pad.left + (i / (n - 1)) * innerW;
  const y = (v) => pad.top + innerH - (v / yMax) * innerH;
  const pathFor = (pts) =>
    pts.map((p, j) => `${j ? "L" : "M"}${x(p.i).toFixed(1)},${y(p.value).toFixed(1)}`).join("");

  const actualPts = points.filter((p) => p.kind === "actual");
  const forecastPts = [actualPts[actualPts.length - 1], ...points.filter((p) => p.kind === "forecast")];

  const band = forecastPts.map((p, j) => ({ ...p, spread: j === 0 ? 0 : p.value * (0.08 + 0.04 * j) }));
  const bandPath =
    band.map((p, j) => `${j ? "L" : "M"}${x(p.i).toFixed(1)},${y(p.value + p.spread).toFixed(1)}`).join("") +
    band.slice().reverse().map((p) => `L${x(p.i).toFixed(1)},${y(Math.max(0, p.value - p.spread)).toFixed(1)}`).join("") +
    "Z";

  let grid = "", yLabels = "", xLabels = "", values = "", dots = "";
  for (let t = 0; t <= 4; t++) {
    const v = (yMax / 4) * t;
    grid += `<line x1="28" x2="${width - pad.right}" y1="${y(v)}" y2="${y(v)}"/>`;
    yLabels += `<text x="22" y="${y(v) + 4}" text-anchor="end">${Math.round(v)}</text>`;
  }
  const every = narrow ? 2 : 1;
  points.forEach((p) => {
    const isFuture = p.kind === "forecast" && p.date > todayIso;
    if (p.i % every === 0 || isFuture) {
      const label = p.date === todayIso ? "Today" : shortDate(p.date);
      const anchor = p.i === n - 1 ? "end" : p.i === 0 ? "start" : "middle";
      xLabels += `<text x="${x(p.i)}" y="${height - 8}" text-anchor="${anchor}"${isFuture ? ' style="fill: var(--teal)"' : ""}>${label}</text>`;
    }
    if (p.date !== todayIso) {
      values += `<text class="value-label${p.kind === "forecast" ? " forecast-label" : ""}" x="${x(p.i)}" y="${y(p.value) - 10}" text-anchor="middle">${p.value}</text>`;
    }
    if (isFuture) {
      dots += `<circle class="target-dot" cx="${x(p.i)}" cy="${y(p.value)}" r="${p.date === state.focusDate ? 6.5 : 4.5}"/>`;
    }
  });

  const todayPt = points.find((p) => p.date === todayIso);
  els.chart.innerHTML = `
    <svg viewBox="0 0 ${width} ${height}" width="${width}" height="${height}">
      <g class="grid">${grid}</g>
      <g class="axis">${yLabels}${xLabels}</g>
      <line class="today-line" x1="${x(todayPt.i)}" x2="${x(todayPt.i)}" y1="${pad.top}" y2="${pad.top + innerH}"/>
      <path class="band" d="${bandPath}"/>
      <path class="actual" d="${pathFor(actualPts)}"/>
      <path class="forecast" d="${pathFor(forecastPts)}"/>
      ${dots}
      ${values}
    </svg>`;

  els.chartNote.textContent =
    "Sales over the last week, followed by the forecast. The shaded area shows the likely range.";
}

// View 2: the last 4 same weekdays next to the forecast for the focus day.
function drawWeekdayChart() {
  const { width, height, narrow } = chartSize();
  const pad = { top: 24, right: 16, bottom: 30, left: 36 };
  const innerW = width - pad.left - pad.right;
  const innerH = height - pad.top - pad.bottom;

  const target = focusDay();
  const history = sameWeekdaySales(target.date);
  const avg = avgOf(history);
  const bars = [
    ...history.map((r) => ({ date: r.date, value: r.quantity, kind: "actual" })),
    { date: target.date, value: Math.round(target.predicted), kind: "forecast" },
  ];

  const yMax = niceCeil(Math.max(avg, ...bars.map((b) => b.value)) * 1.25);
  const y = (v) => pad.top + innerH - (v / yMax) * innerH;
  const slot = innerW / bars.length;
  const barW = Math.min(56, slot * 0.55);
  const cx = (i) => pad.left + slot * i + slot / 2;

  const avgEnd = pad.left + slot * history.length;
  let grid = "", yLabels = "";
  let body = `<line class="avg-line" x1="${pad.left}" x2="${avgEnd}" y1="${y(avg)}" y2="${y(avg)}"/>`;
  for (let t = 0; t <= 4; t++) {
    const v = (yMax / 4) * t;
    grid += `<line x1="${pad.left}" x2="${width - pad.right}" y1="${y(v)}" y2="${y(v)}"/>`;
    yLabels += `<text x="${pad.left - 8}" y="${y(v) + 4}" text-anchor="end">${Math.round(v)}</text>`;
  }
  bars.forEach((b, i) => {
    const isForecast = b.kind === "forecast";
    body +=
      `<rect class="${isForecast ? "bar-forecast" : "bar"}" x="${cx(i) - barW / 2}" y="${y(b.value)}" width="${barW}" height="${y(0) - y(b.value)}" rx="3"/>` +
      `<text class="value-label${isForecast ? " forecast-label" : ""}" x="${cx(i)}" y="${y(b.value) - 7}" text-anchor="middle">${b.value}</text>` +
      `<text x="${cx(i)}" y="${height - 10}" text-anchor="middle"${isForecast ? ' style="fill: var(--teal)"' : ""}>${isForecast && !narrow ? `${shortDate(b.date)} (forecast)` : shortDate(b.date)}</text>`;
  });

  els.chart.innerHTML = `
    <svg viewBox="0 0 ${width} ${height}" width="${width}" height="${height}">
      <g class="grid">${grid}</g>
      <g class="axis">${yLabels}${body}</g>
    </svg>`;

  const diff = Math.round(target.predicted - avg);
  const plural = `${weekdayName(target.date)}s`;
  els.chartNote.textContent =
    `The model leans heavily on past ${plural}. ` +
    (Math.abs(diff) <= 2
      ? `This forecast is in line with the last 4 ${plural}.`
      : `This forecast is ${Math.abs(diff)} ${diff > 0 ? "above" : "below"} their average.`);
}

function avgOf(rows) {
  return rows.reduce((s, r) => s + r.quantity, 0) / Math.max(1, rows.length);
}

// Rounds the chart's top value up so the 4 gridlines land on round numbers.
function niceCeil(v) {
  const steps = [5, 10, 15, 20, 25, 30, 40, 50, 75, 100, 150, 200, 250, 500];
  const step = steps.find((st) => st * 4 >= v) ?? Math.ceil(v / 4 / 100) * 100;
  return step * 4;
}

/* ------------------------------------------------------------------ */
/* Chat                                                                */
/* ------------------------------------------------------------------ */

const SUGGESTIONS = [
  "Why this number?",
  "How do past WEEKDAYs compare?",
  "How accurate is this?",
  "How much should I prepare?",
];

function setupChat() {
  els.chatForm.addEventListener("submit", (e) => {
    e.preventDefault();
    const question = els.chatInput.value.trim();
    if (!question) return;
    els.chatInput.value = "";
    ask(question);
  });
}

function chatContext() {
  const day = focusDay();
  const sameDays = sameWeekdaySales(day.date);
  const recent = state.past.slice(-7);
  return {
    dishId: state.dishId,
    dish: currentDishName(),
    date: day.date,
    predicted: Math.round(day.predicted),
    weekdayPlural: `${weekdayName(day.date)}s`,
    sameDays,
    sameAvg: sameDays.reduce((s, r) => s + r.quantity, 0) / Math.max(1, sameDays.length),
    recentAvg: recent.reduce((s, r) => s + r.quantity, 0) / Math.max(1, recent.length),
    factors: getFactors(day.date),
  };
}

function resetChat() {
  els.messages.innerHTML = "";
  const day = focusDay();
  addMessage(
    "bot",
    `Ask me about the ${currentDishName()} forecast for ${longDate(day.date)}, like why it's ${Math.round(day.predicted)} or how much to prepare.`
  );
  renderSuggestions();
}

function renderSuggestions() {
  const weekday = weekdayName(focusDay().date);
  els.suggestions.innerHTML = SUGGESTIONS.map(
    (s) => `<button type="button" class="suggestion">${escapeHtml(s.replace("WEEKDAY", weekday))}</button>`
  ).join("");
  els.suggestions.querySelectorAll(".suggestion").forEach((btn) =>
    btn.addEventListener("click", () => ask(btn.textContent))
  );
}

async function ask(question) {
  addMessage("user", question);
  const typing = addMessage("bot", "Thinking…");
  typing.classList.add("msg-typing");
  try {
    const answer = await askAboutForecast(question, chatContext());
    typing.classList.remove("msg-typing");
    typing.textContent = answer;
  } catch (err) {
    typing.classList.remove("msg-typing");
    typing.textContent = `Couldn't get an answer. ${err.message}`;
  }
  els.messages.scrollTop = els.messages.scrollHeight;
}

function addMessage(who, text) {
  const li = document.createElement("li");
  li.className = `msg msg-${who}`;
  li.textContent = text;
  els.messages.appendChild(li);
  els.messages.scrollTop = els.messages.scrollHeight;
  return li;
}

function addDivider(text) {
  const li = document.createElement("li");
  li.className = "msg-divider";
  li.textContent = text;
  els.messages.appendChild(li);
  els.messages.scrollTop = els.messages.scrollHeight;
}

/* ------------------------------------------------------------------ */
/* Utilities                                                           */
/* ------------------------------------------------------------------ */

function debounce(fn, ms) {
  let t;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

init();
