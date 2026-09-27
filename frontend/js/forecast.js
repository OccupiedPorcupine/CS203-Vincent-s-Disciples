// Demand forecast page.
//
// Stage 1: everything runs on MOCK DATA so the page can be designed and demoed
// without the backend. Each data function has its real API call written below
// the mock branch. When the backend endpoints exist, set USE_MOCK_DATA = false.

const USE_MOCK_DATA = true;

// Forecast periods offered: 1, 2 or 4 days ahead (set in forecast.html).
// NOTE: the real backend (NeaWeatherForecastService) currently only accepts
// dates inside NEA's 24-hour forecast. "2 days" and "4 days" need it switched
// to NEA's 4-day outlook before they will work with real data.

const PAST_DAYS_SHOWN = 14;

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
// Needs a NEW backend endpoint, e.g. GET /api/dishes/{id}/sales?days=14
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
    await wait(120);
    return { date, predicted: mockDemand(dishId, parseIso(date)) };
  }
  const result = await apiRequest("/api/forecast", {
    method: "POST",
    body: JSON.stringify({ dishId, forecastDate: date }),
  });
  return { date: result.forecast_date, predicted: result.predicted_demand };
}

// Returns { weather: [{label, value}], events: [{label, value}] }
// The backend already fetches NEA weather + public holidays for each forecast;
// it would need to include them in the /api/forecast response.
async function getFactors(dishId, date) {
  if (USE_MOCK_DATA) {
    const day = parseIso(date).getDay();
    return {
      weather: [
        { label: "Sky", value: day % 2 ? "Partly cloudy" : "Showers" },
        { label: "Temp", value: day % 2 ? "31°C" : "28°C" },
        { label: "Rain", value: day % 2 ? "Low" : "Likely" },
      ],
      events: [
        { label: "Nearby", value: "None listed" },
        { label: "Public holiday", value: "No" },
      ],
    };
  }
  return { weather: [], events: [] };
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

/* ------------------------------------------------------------------ */
/* Page wiring                                                         */
/* ------------------------------------------------------------------ */

const els = {
  dish: document.getElementById("dish-select"),
  periods: document.querySelectorAll(".period-button"),
  chart: document.getElementById("chart"),
  headline: document.getElementById("headline"),
  status: document.getElementById("status"),
  weather: document.getElementById("weather-list"),
  events: document.getElementById("event-list"),
  history: document.getElementById("history-list"),
  sidebar: document.getElementById("sidebar"),
  scrim: document.getElementById("scrim"),
  toggle: document.getElementById("sidebar-toggle"),
};

const state = { dishId: null, days: 1, requestId: 0 };
let lastRender = null;

async function init() {
  setupSidebar();

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

  window.addEventListener("resize", debounce(() => lastRender && drawChart(...lastRender), 150));
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
    // Forecast today (so the chart line joins up) plus the next N days.
    const forecastDates = [];
    for (let i = 0; i <= state.days; i++) forecastDates.push(isoDate(addDays(today(), i)));
    const firstDay = forecastDates[1];

    const [past, forecast, factors] = await Promise.all([
      getRecentSales(state.dishId, PAST_DAYS_SHOWN),
      Promise.all(forecastDates.map((date) => getForecast(state.dishId, date))),
      getFactors(state.dishId, firstDay),
    ]);

    if (requestId !== state.requestId) return;

    lastRender = [past, forecast];
    drawChart(past, forecast);
    renderHeadline(forecast.slice(1), past);
    renderFactors(factors);
    renderHistory(past.slice(-7));
    setStatus(USE_MOCK_DATA ? "Showing sample data. Not connected to the forecasting model yet." : "");
  } catch (err) {
    if (requestId !== state.requestId) return;
    setStatus(`Could not generate the forecast. ${err.message}`, true);
  }
}

function setStatus(text, isError = false) {
  els.status.textContent = text;
  els.status.classList.toggle("error", isError);
}

// `days` = the forecast for tomorrow onwards (today is only drawn on the chart).
function renderHeadline(days, past) {
  const dishName = escapeHtml(els.dish.options[els.dish.selectedIndex]?.text ?? "");
  const total = Math.round(days.reduce((sum, d) => sum + d.predicted, 0));

  if (days.length === 1) {
    const day = days[0];
    const lastWeek = past.find((r) => r.date === isoDate(addDays(parseIso(day.date), -7)));
    let comparison = "";
    if (lastWeek && lastWeek.quantity > 0) {
      const change = Math.round(((day.predicted - lastWeek.quantity) / lastWeek.quantity) * 100);
      comparison =
        change === 0 ? ", about the same as last week"
        : `, ${Math.abs(change)}% ${change > 0 ? "more" : "fewer"} than the same day last week`;
    }
    els.headline.innerHTML =
      `<span class="number">${total}</span>` +
      `${dishName} portions expected tomorrow (${longDate(day.date)})${comparison}.`;
    return;
  }

  const breakdown = days
    .map((d) => `<li>${longDate(d.date)}: <strong>${Math.round(d.predicted)}</strong></li>`)
    .join("");
  els.headline.innerHTML =
    `<span class="number">${total}</span>` +
    `${dishName} portions expected over the next ${days.length} days.` +
    `<ul class="day-breakdown">${breakdown}</ul>`;
}

function renderFactors({ weather, events }) {
  const toItems = (rows) =>
    rows.length
      ? rows.map((r) => `<li>${escapeHtml(r.label)}: <strong>${escapeHtml(r.value)}</strong></li>`).join("")
      : "<li>Not available</li>";
  els.weather.innerHTML = toItems(weather);
  els.events.innerHTML = toItems(events);
}

function renderHistory(rows) {
  els.history.innerHTML = rows
    .slice()
    .reverse() // newest first
    .map((r) => `<li><span>${longDate(r.date)}</span><span>${r.quantity} sold</span></li>`)
    .join("");
}

/* ------------------------------------------------------------------ */
/* Chart (plain SVG, no library)                                       */
/* ------------------------------------------------------------------ */

function drawChart(past, forecast) {
  const width = Math.max(300, els.chart.clientWidth);
  const height = width < 500 ? 220 : 250;
  const pad = { top: 18, right: 16, bottom: 28, left: 32 };
  const innerW = width - pad.left - pad.right;
  const innerH = height - pad.top - pad.bottom;

  const points = [
    ...past.map((r) => ({ date: r.date, value: r.quantity, kind: "actual" })),
    ...forecast.map((r) => ({ date: r.date, value: Math.round(r.predicted), kind: "forecast" })),
  ].map((p, i) => ({ ...p, i }));
  const n = points.length;
  const yMax = niceCeil(Math.max(...points.map((p) => p.value)) * 1.2);

  const x = (i) => pad.left + (n === 1 ? innerW / 2 : (i / (n - 1)) * innerW);
  const y = (v) => pad.top + innerH - (v / yMax) * innerH;
  const pathFor = (pts) =>
    pts.map((p, j) => `${j ? "L" : "M"}${x(p.i).toFixed(1)},${y(p.value).toFixed(1)}`).join("");

  const actualPts = points.filter((p) => p.kind === "actual");
  const lastActual = actualPts[actualPts.length - 1];
  const forecastPts = [lastActual, ...points.filter((p) => p.kind === "forecast")].filter(Boolean);
  const target = forecastPts[forecastPts.length - 1];

  // Uncertainty band that widens further into the future (illustrative only).
  const band = forecastPts.map((p, j) => ({ ...p, spread: j === 0 ? 0 : p.value * (0.08 + 0.04 * j) }));
  const bandPath =
    band.map((p, j) => `${j ? "L" : "M"}${x(p.i).toFixed(1)},${y(p.value + p.spread).toFixed(1)}`).join("") +
    band.slice().reverse().map((p) => `L${x(p.i).toFixed(1)},${y(Math.max(0, p.value - p.spread)).toFixed(1)}`).join("") +
    "Z";

  let grid = "", yLabels = "", xLabels = "";
  const ticks = 4;
  for (let t = 0; t <= ticks; t++) {
    const v = (yMax / ticks) * t;
    grid += `<line x1="${pad.left}" x2="${width - pad.right}" y1="${y(v)}" y2="${y(v)}"/>`;
    yLabels += `<text x="${pad.left - 8}" y="${y(v) + 4}" text-anchor="end">${Math.round(v)}</text>`;
  }
  const every = Math.max(1, Math.ceil(n / (width < 500 ? 4 : 6)));
  points.forEach((p) => {
    if (p.i % every === 0 && x(target.i) - x(p.i) > 55) {
      xLabels += `<text x="${x(p.i)}" y="${height - 8}" text-anchor="middle">${shortDate(p.date)}</text>`;
    }
  });
  xLabels += `<text x="${x(target.i)}" y="${height - 8}" text-anchor="end" style="fill: var(--teal)">${shortDate(target.date)}</text>`;

  const todayPt = points.find((p) => p.date === isoDate(today()));
  const todayX = todayPt ? x(todayPt.i) : lastActual ? x(lastActual.i) : pad.left;

  els.chart.innerHTML = `
    <svg viewBox="0 0 ${width} ${height}" width="${width}" height="${height}">
      <g class="grid">${grid}</g>
      <g class="axis">${yLabels}${xLabels}</g>
      <line class="today-line" x1="${todayX}" x2="${todayX}" y1="${pad.top}" y2="${pad.top + innerH}"/>
      <text class="marker-label" x="${todayX - 6}" y="${pad.top + 10}" text-anchor="end">Today</text>
      <path class="band" d="${bandPath}"/>
      <path class="actual" d="${pathFor(actualPts)}"/>
      <path class="forecast" d="${pathFor(forecastPts)}"/>
      ${forecastPts.filter((p) => p.date > isoDate(today()))
        .map((p) => `<circle class="target-dot" cx="${x(p.i)}" cy="${y(p.value)}" r="5"/>`).join("")}
      <line class="hover-line" y1="${pad.top}" y2="${pad.top + innerH}" visibility="hidden"/>
      <circle class="hover-dot" r="5" visibility="hidden"/>
      <rect class="hit-area" x="${pad.left}" y="${pad.top}" width="${innerW}" height="${innerH}" fill="transparent"/>
    </svg>
    <div class="tooltip" hidden></div>`;

  // Hover / tap tooltip
  const svg = els.chart.querySelector("svg");
  const line = svg.querySelector(".hover-line");
  const dot = svg.querySelector(".hover-dot");
  const tip = els.chart.querySelector(".tooltip");
  const hit = svg.querySelector(".hit-area");

  const show = (clientX) => {
    const rect = svg.getBoundingClientRect();
    const scale = width / rect.width;
    const i = Math.round((((clientX - rect.left) * scale - pad.left) / innerW) * (n - 1));
    const p = points[Math.min(n - 1, Math.max(0, i))];
    const cx = x(p.i), cy = y(p.value);
    line.setAttribute("x1", cx); line.setAttribute("x2", cx); line.setAttribute("visibility", "visible");
    dot.setAttribute("cx", cx); dot.setAttribute("cy", cy); dot.setAttribute("visibility", "visible");
    dot.setAttribute("fill", p.kind === "actual" ? "var(--ink)" : "var(--teal)");
    tip.hidden = false;
    tip.textContent = `${longDate(p.date)} · ${p.value} ${p.kind === "actual" ? "sold" : "predicted"}`;
    const left = Math.min(Math.max(cx / scale, 70), rect.width - 70);
    tip.style.left = `${left}px`;
    tip.style.top = `${Math.max(0, cy / scale - 40)}px`;
  };
  const hide = () => {
    line.setAttribute("visibility", "hidden");
    dot.setAttribute("visibility", "hidden");
    tip.hidden = true;
  };
  hit.addEventListener("pointermove", (e) => show(e.clientX));
  hit.addEventListener("pointerdown", (e) => show(e.clientX));
  hit.addEventListener("pointerleave", hide);
}

function niceCeil(v) {
  const step = v > 200 ? 50 : v > 80 ? 20 : 10;
  return Math.max(step, Math.ceil(v / step) * step);
}
function debounce(fn, ms) {
  let t;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

init();
