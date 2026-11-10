// Records the animated README demos (docs/readme/demo-*.gif) against a mocked API: no backend,
// database or login needed. Each clip is a Playwright video of the real pages, with a fake mouse
// pointer drawn into the page, then turned into a GIF by docs/readme/make-gifs.sh (ffmpeg).
//
// From the repo root:
//   FRONTEND_PORT=5510 node frontend/server.mjs &          # serve the pages
//   npm install --prefix /tmp/chicky-pw playwright         # once
//   PW=/tmp/chicky-pw/node_modules/playwright node docs/readme/record-demos.cjs
//
// Needs Google Chrome (launched with channel "chrome"), ffmpeg and ffprobe on the PATH, and internet
// access for Chart.js, Google Fonts and Google's sign-in button. Options, as environment variables:
//   BASE   frontend address (default http://127.0.0.1:5510)
//   WORK   folder for the raw videos and frames (default: a chicky-demos folder in the OS temp dir)
//   ONLY   comma-separated clips to record, e.g. ONLY=prep,assistant (default: all four)
//   NO_GIF set to 1 to record only; run docs/readme/make-gifs.sh "$WORK" later
// Dates are relative to today in Singapore, so the data always looks current. Any /api request the
// mocks don't answer is listed at the end, and the script fails if there is one.
const { chromium } = require(process.env.PW || "playwright");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawnSync } = require("child_process");

const BASE = process.env.BASE || "http://127.0.0.1:5510";
const OUT = __dirname;
const WORK = process.env.WORK || path.join(os.tmpdir(), "chicky-demos");
const CLIPS = ["dashboard", "forecast", "prep", "assistant"];
const ONLY = process.env.ONLY ? process.env.ONLY.split(",").map(name => name.trim()) : CLIPS;
const VIEWPORT = { width: 1280, height: 800 };
const TITLES = { dashboard: "Dashboard", forecast: "Demand forecast", prep: "Prep list", assistant: "Chicky assistant" };

// ---------------------------------------------------------------------------------------------
// Mock data: Mei Ling's hawker stall, four dishes, a year of believable sales.
// ---------------------------------------------------------------------------------------------

const addDays = (iso, days) => {
  const date = new Date(`${iso}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
};
const weekday = iso => new Date(`${iso}T00:00:00Z`).getUTCDay(); // 0 Sunday … 6 Saturday
const dayName = (iso, style = "short") => new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-SG", { weekday: style, timeZone: "UTC" });
const longDay = iso => new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long", timeZone: "UTC" });
const shortDay = iso => new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });
const dayDiff = (a, b) => Math.round((Date.parse(`${a}T00:00:00Z`) - Date.parse(`${b}T00:00:00Z`)) / 86400000);
const round2 = value => Math.round(value * 100) / 100;
const round3 = value => Math.round(value * 1000) / 1000;

const TODAY = new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Singapore" });
const TOMORROW = addDays(TODAY, 1);
const LATEST = TODAY; // the last day with sales recorded: today's, entered after closing
const WEEK = Array.from({ length: 7 }, (_, index) => addDays(LATEST, index - 6));
// A wet weekday in the latest week: footfall dips, though laksa holds up.
const RAIN_DAY = WEEK.find(day => weekday(day) === 4 && day !== LATEST)
  || WEEK.find(day => weekday(day) === 3 && day !== LATEST) || WEEK[3];
const LAST_SATURDAY = WEEK.find(day => weekday(day) === 6);
const LAST_SUNDAY = WEEK.find(day => weekday(day) === 0);
const NEXT_SATURDAY = addDays(TODAY, ((6 - weekday(TODAY) + 7) % 7) || 7);
// Chinese New Year: the stall closed for two days.
const CLOSED = new Set(["2026-02-17", "2026-02-18"]);

const DISHES = [
  { id: 1, name: "Chicken rice", base: 58, price: 4.5, cost: 1.8 },
  { id: 2, name: "Char siew rice", base: 40, price: 5.0, cost: 2.1 },
  { id: 3, name: "Laksa", base: 31, price: 6.0, cost: 2.6 },
  { id: 4, name: "Nasi lemak", base: 25, price: 4.5, cost: 1.7 }
];
// Sunday first. Quieter early week, a Friday lift and a big weekend.
const WEEKDAY_FACTOR = [1.27, 0.88, 0.93, 0.98, 1.0, 1.12, 1.38];

function noise(seed) {
  let h = 2166136261;
  for (const char of seed) h = Math.imul(h ^ char.charCodeAt(0), 16777619);
  h ^= h >>> 13; h = Math.imul(h, 0x5bd1e995); h ^= h >>> 15;
  return (h >>> 0) / 4294967296;
}

// Slow seasonal swing and a stall that has grown over the year.
const trend = iso => {
  const ago = dayDiff(TODAY, iso);
  const busyLately = ago <= 7 ? 1.06 : 1; // a good fortnight, so the week is up on the last
  return (1 + 0.05 * Math.sin(ago / 47)) * (1 - Math.max(0, ago) * 0.00032) * busyLately;
};

function portions(dish, iso) {
  if (iso > LATEST || CLOSED.has(iso)) return 0;
  const rain = iso === RAIN_DAY ? (dish.id === 3 ? 0.95 : 0.74) : 1;
  return Math.round(dish.base * WEEKDAY_FACTOR[weekday(iso)] * trend(iso) * rain * (0.93 + 0.14 * noise(`${dish.id}${iso}`)));
}

function expected(dish, iso) {
  const value = dish.base * WEEKDAY_FACTOR[weekday(iso)] * trend(iso) * 1.02;
  return { predicted: round2(value), low: Math.floor(value * 0.87), high: Math.ceil(value * 1.12) };
}

// Sell-outs and leftovers recorded in the latest week.
function extras(dish, iso) {
  if (dish.id === 1 && iso === LAST_SATURDAY) return { soldOutAt: "13:10", leftoverQuantity: null };
  if (dish.id === 1 && iso === LAST_SUNDAY) return { soldOutAt: "13:45", leftoverQuantity: null };
  if (dish.id === 2 && iso === RAIN_DAY) return { soldOutAt: null, leftoverQuantity: 7 };
  if (dish.id === 4 && iso === RAIN_DAY) return { soldOutAt: null, leftoverQuantity: 4 };
  if (dish.id === 3 && iso === WEEK[1]) return { soldOutAt: null, leftoverQuantity: 3 };
  return { soldOutAt: null, leftoverQuantity: null };
}

function salesOf(dish) {
  return Array.from({ length: 63 }, (_, index) => addDays(LATEST, index - 62))
    .map(date => ({ date, quantitySold: portions(dish, date), ...extras(dish, date) }));
}

function revenue(from, to) {
  const rows = DISHES.map(dish => {
    let count = 0;
    for (let day = from; day <= to; day = addDays(day, 1)) count += portions(dish, day);
    const margin = round2(dish.price - dish.cost);
    return {
      dishId: dish.id, name: dish.name, portions: count, price: dish.price, unitCost: dish.cost,
      marginPerPortion: margin, revenue: round2(count * dish.price), grossProfit: round2(count * margin)
    };
  }).sort((a, b) => b.grossProfit - a.grossProfit);
  const sum = key => round2(rows.reduce((total, row) => total + row[key], 0));
  return { from, to, portions: sum("portions"), revenue: sum("revenue"), grossProfit: sum("grossProfit"),
    unpricedPortions: 0, portionsWithoutCost: 0, dishes: rows };
}

function waste(from, to) {
  const days = [];
  for (let day = from; day <= to; day = addDays(day, 1)) days.push(day);
  const dishes = DISHES.map(dish => {
    const recorded = days.map(day => extras(dish, day));
    const leftovers = recorded.reduce((total, row) => total + (row.leftoverQuantity || 0), 0);
    const soldOut = recorded.filter(row => row.soldOutAt).map(row => row.soldOutAt).sort();
    return { dishId: dish.id, name: dish.name, leftoverPortions: leftovers, leftoverCost: round2(leftovers * dish.cost),
      soldOutDays: soldOut.length, earliestSoldOutAt: soldOut[0] || null };
  }).filter(dish => dish.leftoverPortions || dish.soldOutDays).sort((a, b) => b.leftoverCost - a.leftoverCost);
  const daysRecorded = days.filter(day => DISHES.some(dish => {
    const row = extras(dish, day);
    return row.soldOutAt || row.leftoverQuantity != null;
  })).length;
  return {
    from, to,
    leftoverPortions: dishes.reduce((total, dish) => total + dish.leftoverPortions, 0),
    leftoverCost: round2(dishes.reduce((total, dish) => total + dish.leftoverCost, 0)),
    leftoversWithoutCost: 0,
    soldOutDays: dishes.reduce((total, dish) => total + dish.soldOutDays, 0),
    daysRecorded, dishes
  };
}

function dailyRevenue(from, to) {
  const days = [];
  for (let day = from; day <= to; day = addDays(day, 1)) {
    const count = DISHES.reduce((total, dish) => total + portions(dish, day), 0);
    const money = DISHES.reduce((total, dish) => total + portions(dish, day) * dish.price, 0);
    days.push({ date: day, portions: count, revenue: count ? round2(money) : null, closed: CLOSED.has(day) });
  }
  return { from, to, anyPrices: true, days };
}

function weeklySummary() {
  const total = days => days.reduce((sum, day) => sum + DISHES.reduce((t, dish) => t + portions(dish, day), 0), 0);
  const count = total(WEEK);
  const before = total(WEEK.map(day => addDays(day, -7)));
  const change = Math.round((count - before) / before * 100);
  const money = revenue(WEEK[0], LATEST);
  const top = [...money.dishes].sort((a, b) => b.portions - a.portions)[0];
  const leftovers = waste(WEEK[0], LATEST);
  const fmt = value => value.toLocaleString("en-SG");
  const zhDay = new Date(`${LATEST}T00:00:00Z`).toLocaleDateString("zh-CN", { month: "numeric", day: "numeric", weekday: "long", timeZone: "UTC" });
  const changeEn = change > 0 ? `, up ${change}% on the week before` : change < 0 ? `, down ${-change}% on the week before` : "";
  const changeZh = change > 0 ? `，比前一周多${change}%` : change < 0 ? `，比前一周少${-change}%` : "";
  return {
    from: WEEK[0], to: LATEST, portions: count, previousPortions: before, changePercent: change,
    revenue: money.revenue, topDish: { name: top.name, count: top.portions }, wasteRecorded: true,
    leftoverPortions: leftovers.leftoverPortions, leftoverCost: leftovers.leftoverCost,
    mostSoldOut: { name: "Chicken rice", count: 2 },
    text: {
      en: `In the 7 days to ${longDay(LATEST)}, you sold ${fmt(count)} portions${changeEn}. Revenue was $${fmt(Math.round(money.revenue))}. `
        + `${top.name} sold the most, ${fmt(top.portions)} portions. You had ${leftovers.leftoverPortions} portions left over, about $${leftovers.leftoverCost.toFixed(2)} of food. `
        + "Chicken rice sold out on 2 days.",
      zh: `截至${zhDay}的7天，你卖了${fmt(count)}份${changeZh}。营业额是${fmt(Math.round(money.revenue))}元。卖得最多的是${top.name}，${fmt(top.portions)}份。`
        + `剩下${leftovers.leftoverPortions}份，大约${leftovers.leftoverCost.toFixed(2)}元的食物。Chicken rice有2天卖完。`
    }
  };
}

function actions() {
  return [
    { key: `rain:${TOMORROW}`, kind: "rain", title: `Thundery showers forecast tomorrow (${shortDay(TOMORROW)})`,
      detail: `On your last 4 rainy ${dayName(TOMORROW, "long")}s you sold 18% less than on dry ones.`,
      actionLabel: "See the forecast", href: "forecast.html", recordDate: null },
    { key: `sold-out:1:${LAST_SUNDAY}`, kind: "sold-out", title: "Chicken rice sold out 2 times this week",
      detail: "Earliest at 1:10 pm. You'd likely have sold about 11 more on those days, so consider preparing extra.",
      actionLabel: "See prep list", href: "prep.html", recordDate: null }
  ];
}

// Ingredients: cooked amount per portion in the recipes, raw yield, and tonight's stock count.
const INGREDIENTS = [
  { id: 1, name: "Whole chicken", unit: "g", yieldPercent: 72, perishable: true, basis: "USDA cooking yield for whole chicken with skin, simmered", stock: [6000, TOMORROW] },
  { id: 2, name: "Pork shoulder", unit: "g", yieldPercent: 68, perishable: true, basis: "USDA cooking yield for pork shoulder, roasted", stock: null },
  { id: 3, name: "Laksa noodles", unit: "g", yieldPercent: 100, perishable: true, basis: null, stock: null },
  { id: 4, name: "Prawns", unit: "g", yieldPercent: 85, perishable: true, basis: "USDA cooking yield for shrimp, boiled", stock: [1200, TODAY] },
  { id: 5, name: "Rice", unit: "g", yieldPercent: 250, perishable: false, basis: null, customised: true, stock: [25000, null] },
  { id: 6, name: "Coconut milk", unit: "ml", yieldPercent: 100, perishable: false, basis: null, stock: [6500, TOMORROW] },
  { id: 7, name: "Eggs", unit: "pcs", yieldPercent: 100, perishable: false, basis: null, stock: [30, addDays(TODAY, 9)] },
  { id: 8, name: "Sambal", unit: "g", yieldPercent: 100, perishable: false, basis: null, stock: [2400, addDays(TODAY, 5)] }
];
const RECIPES = {
  1: [["Whole chicken", 150], ["Rice", 230]],
  2: [["Pork shoulder", 120], ["Rice", 230]],
  3: [["Laksa noodles", 160], ["Prawns", 55], ["Coconut milk", 120]],
  4: [["Rice", 200], ["Eggs", 1], ["Sambal", 35], ["Coconut milk", 30]]
};
const ingredient = name => INGREDIENTS.find(item => item.name === name);

function ingredientsResponse() {
  return INGREDIENTS.map(item => ({
    id: item.id, name: item.name, unit: item.unit, yieldPercent: item.yieldPercent,
    defaultYieldPercent: item.customised ? 100 : item.yieldPercent, defaultYieldBasis: item.basis,
    perishable: item.perishable, customised: Boolean(item.customised),
    stockQuantity: item.stock ? item.stock[0] : null, stockCountedOn: item.stock ? TODAY : null,
    stockExpiresOn: item.stock ? item.stock[1] : null
  }));
}

function recipesResponse() {
  return DISHES.map(dish => ({
    dishId: dish.id, dish: dish.name,
    items: RECIPES[dish.id].map(([name, quantity]) => ({ ingredient: name, unit: ingredient(name).unit, quantity }))
  }));
}

function prepList(date, bufferPercent, top) {
  const dishes = DISHES.map(dish => {
    const forecast = expected(dish, date);
    let count;
    let rule;
    let why;
    if (bufferPercent != null) {
      count = Math.ceil(forecast.predicted * (100 + bufferPercent) / 100);
      rule = "BUFFER";
      why = `The forecast plus ${bufferPercent}%.`;
    } else if (top) {
      count = forecast.high;
      rule = "TOP_OF_RANGE";
      why = "The top of the likely range.";
    } else {
      const share = Math.min(0.8, Math.max(0.5, (dish.price - dish.cost) / dish.price));
      const spread = (forecast.high - forecast.low) / 2.56;
      count = Math.ceil(forecast.predicted + (share - 0.5) * 2.5 * spread);
      rule = "MARGIN";
      why = `Running out costs you $${(dish.price - dish.cost).toFixed(2)} a plate and a leftover $${dish.cost.toFixed(2)}, so this is enough on about ${Math.round(share * 100)}% of days like it.`;
    }
    return { dishId: dish.id, dish: dish.name, portions: count, expected: Math.round(forecast.predicted * 10) / 10,
      low: forecast.low, high: forecast.high, method: "MODEL",
      basis: "Forecast model (Chronos-2), from your daily sales and public holidays", hasRecipe: true, rule, why };
  });
  const totals = new Map();
  dishes.forEach(dish => RECIPES[dish.dishId].forEach(([name, perPortion]) => {
    const entry = totals.get(name) || { quantity: 0, expectedCooked: 0, dishes: [] };
    entry.quantity += perPortion * dish.portions;
    entry.expectedCooked += perPortion * dish.expected;
    entry.dishes.push(dish.dish);
    totals.set(name, entry);
  }));
  const ingredients = INGREDIENTS.filter(item => totals.has(item.name)).map(item => {
    const entry = totals.get(item.name);
    return {
      ingredient: item.name, unit: item.unit, quantity: round3(entry.quantity), dishes: entry.dishes,
      yieldPercent: item.yieldPercent, toBuy: round3(Math.ceil(entry.quantity / (item.yieldPercent / 100) * 1000) / 1000),
      perishable: item.perishable, expectedRawUse: round3(entry.expectedCooked / (item.yieldPercent / 100))
    };
  });
  return { date, closed: null, bufferPercent: bufferPercent ?? null, dishes, ingredients, skipped: [] };
}

// A tenth of a second of silence, for the assistant's spoken replies.
function silentWav() {
  const samples = 800;
  const buffer = Buffer.alloc(44 + samples * 2);
  buffer.write("RIFF", 0); buffer.writeUInt32LE(36 + samples * 2, 4); buffer.write("WAVE", 8);
  buffer.write("fmt ", 12); buffer.writeUInt32LE(16, 16); buffer.writeUInt16LE(1, 20); buffer.writeUInt16LE(1, 22);
  buffer.writeUInt32LE(8000, 24); buffer.writeUInt32LE(16000, 28); buffer.writeUInt16LE(2, 32); buffer.writeUInt16LE(16, 34);
  buffer.write("data", 36); buffer.writeUInt32LE(samples * 2, 40);
  return buffer;
}

function assistantReply() {
  const chicken = DISHES[0];
  const forecast = expected(chicken, NEXT_SATURDAY);
  const plan = prepList(NEXT_SATURDAY).dishes[0];
  return {
    reply: `For Saturday ${new Date(`${NEXT_SATURDAY}T00:00:00Z`).toLocaleDateString("en-GB", { day: "numeric", month: "short", timeZone: "UTC" })}, prepare about ${plan.portions} plates of chicken rice. `
      + `The forecast is ${Math.round(forecast.predicted)} (likely ${forecast.low}–${forecast.high}), as Saturdays run about a third busier than your weekdays. `
      + "It sold out by 1:10 pm last Saturday, so a few extra plates should cover the lunch rush."
  };
}

// ---------------------------------------------------------------------------------------------
// The mocked API. Requests go to the api-base-url meta tag (http://localhost:8080) on localhost;
// /api on the page's own address is caught too, in case that ever changes.
// ---------------------------------------------------------------------------------------------

async function installMocks(context, state) {
  const json = (route, body, delay = 90) => new Promise(resolve => setTimeout(resolve, delay))
    .then(() => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) }));
  const profile = { userId: 7, email: "meiling@example.com", name: "Mei Ling", pictureUrl: null, role: "USER", emailVerified: true };

  const handler = async route => {
    const request = route.request();
    const url = new URL(request.url());
    const p = url.pathname;
    const method = request.method();
    const query = key => url.searchParams.get(key);
    let m;

    if (method === "OPTIONS") return route.fulfill({ status: 204, body: "" });
    if (p === "/api/csrf") return json(route, { headerName: "X-XSRF-TOKEN", token: "demo" }, 20);
    if (p === "/api/config") return json(route, { googleClientId: "704664738768-t2iot0sqoe810ks8m65fr8rkvu6071gh.apps.googleusercontent.com" });
    if (p === "/api/profile") return state.signedIn
      ? json(route, profile, 40)
      : route.fulfill({ status: 401, contentType: "application/json", body: JSON.stringify({ message: "Not signed in" }) });
    if (p === "/api/auth/login" && method === "POST") {
      state.signedIn = true;
      return json(route, { userId: 7, email: profile.email, name: profile.name, pictureUrl: null, role: "USER" }, 650);
    }
    if (p === "/api/dishes") return json(route, DISHES.map(({ id, name }) => ({ id, name })));
    if ((m = p.match(/^\/api\/dishes\/(\d+)\/sales$/))) return json(route, salesOf(DISHES[Number(m[1]) - 1]));
    if (p === "/api/closures") return json(route, {
      restDays: [], closedDates: [], hours: { opensAt: "07:00", closesAt: "15:00" },
      hawkerCentre: { id: 1, name: "Adam Road Food Centre" }, neaClosures: []
    });
    if (p === "/api/revenue") return json(route, revenue(query("from"), query("to")));
    if (p === "/api/revenue/daily") return json(route, dailyRevenue(query("from"), query("to")));
    if (p === "/api/waste") return json(route, waste(query("from"), query("to")));
    if (p === "/api/summary/weekly") return json(route, weeklySummary());
    if (p === "/api/actions") return json(route, actions());
    if (p === "/api/forecast" && method === "POST") {
      const body = JSON.parse(request.postData() || "{}");
      const dish = DISHES.find(item => item.id === Number(body.dishId)) || DISHES[0];
      const forecast = expected(dish, body.forecastDate);
      return json(route, {
        dish: dish.name, forecastDate: body.forecastDate, predictedDemand: forecast.predicted, low: forecast.low, high: forecast.high,
        roughRange: false, method: "MODEL", fallbackReason: null,
        basis: "Forecast model (Chronos-2), from your daily sales and public holidays", note: null
      }, 900);
    }
    if (p === "/api/prep-list") {
      const buffer = query("bufferPercent");
      return json(route, prepList(query("date") || TOMORROW, buffer == null ? null : Number(buffer), query("rule") === "top"), 450);
    }
    if (p === "/api/recipes") return json(route, recipesResponse());
    if (p === "/api/ingredients") return json(route, ingredientsResponse());
    if (p === "/api/assistant/status") return json(route, { available: true, voice: true, model: "demo", usage: null }, 40);
    if (p === "/api/assistant/messages" && method === "POST") {
      return json(route, { conversationId: "demo-conversation", reply: assistantReply().reply, proposal: null, guide: null,
        sources: [], retrieved: [], usage: null }, 1000);
    }
    if (p === "/api/assistant/speech") return route.fulfill({ status: 200, contentType: "audio/wav", body: silentWav() });

    state.unmocked.push(`${method} ${p}${url.search}`);
    return route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ message: "Not mocked" }) });
  };

  await context.route("http://localhost:8080/**", handler);
  // Google's real sign-in button, in English whatever the machine's region.
  await context.route("https://accounts.google.com/gsi/**", route => {
    const url = route.request().url();
    return route.continue({ url: `${url}${url.includes("?") ? "&" : "?"}hl=en`,
      headers: { ...route.request().headers(), "accept-language": "en-GB,en;q=0.9" } });
  });
  await context.route(`${BASE}/api/**`, handler);
}

// ---------------------------------------------------------------------------------------------
// The fake pointer: an arrow that glides to each target and a teal ripple on click. It keeps its
// place across page loads (sessionStorage), so it doesn't jump when a click navigates.
// ---------------------------------------------------------------------------------------------

const CURSOR_SCRIPT = `(() => {
  if (window.top !== window) return;
  const KEY = "demo-cursor";
  let pos = { x: 1010, y: 610 };
  try { pos = JSON.parse(sessionStorage.getItem(KEY)) || pos; } catch (e) {}
  const cursor = document.createElement("div");
  cursor.id = "demo-cursor";
  cursor.innerHTML = '<svg width="26" height="30" viewBox="0 0 26 30" xmlns="http://www.w3.org/2000/svg">'
    + '<path d="M3 2 L3 23.5 L8.6 18.3 L12.4 26.8 L16.3 25.1 L12.6 16.8 L19.8 16.8 Z" fill="#1f2a30" stroke="#ffffff" stroke-width="1.8" stroke-linejoin="round"/></svg>';
  const style = document.createElement("style");
  style.textContent = "#demo-cursor{position:fixed;left:0;top:0;z-index:2147483647;pointer-events:none;will-change:transform;transform-origin:3px 2px}"
    + "#demo-cursor svg{display:block;filter:drop-shadow(0 1px 1.5px rgba(0,0,0,.28))}"
    + ".demo-ripple{position:fixed;z-index:2147483646;pointer-events:none;width:44px;height:44px;margin:-22px 0 0 -22px;border-radius:50%;"
    + "border:2.5px solid #1ba098;background:rgba(27,160,152,.18);animation:demo-ripple .55s cubic-bezier(.2,.8,.2,1) forwards}"
    + "@keyframes demo-ripple{from{transform:scale(.2);opacity:1}to{transform:scale(1.15);opacity:0}}";
  const place = (scale = 1) => {
    cursor.style.transform = "translate(" + (pos.x - 3) + "px," + (pos.y - 2) + "px) scale(" + scale + ")";
    try { sessionStorage.setItem(KEY, JSON.stringify(pos)); } catch (e) {}
  };
  const mount = () => {
    if (!document.documentElement) return setTimeout(mount, 0);
    document.documentElement.append(style, cursor);
    place();
    // Keeps the pointer on top of anything appended to <html> later.
    new MutationObserver(() => {
      if (cursor.parentNode && cursor.parentNode.lastElementChild !== cursor) cursor.parentNode.append(cursor);
    }).observe(document.documentElement, { childList: true });
  };
  const ease = t => t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
  window.__demo = {
    move(x, y, ms) {
      const from = { ...pos };
      const start = performance.now();
      return new Promise(resolve => {
        const step = now => {
          const t = Math.min(1, (now - start) / ms);
          const k = ease(t);
          pos = { x: from.x + (x - from.x) * k, y: from.y + (y - from.y) * k };
          place();
          if (t < 1) requestAnimationFrame(step); else resolve();
        };
        requestAnimationFrame(step);
      });
    },
    ripple() {
      const ring = document.createElement("div");
      ring.className = "demo-ripple";
      ring.style.left = pos.x + "px";
      ring.style.top = pos.y + "px";
      document.documentElement.append(ring);
      place(0.88);
      setTimeout(() => place(1), 140);
      setTimeout(() => ring.remove(), 650);
    },
    scrollTo(y, ms) {
      const from = window.scrollY;
      const target = Math.max(0, Math.min(y, document.documentElement.scrollHeight - innerHeight));
      const start = performance.now();
      return new Promise(resolve => {
        const step = now => {
          const t = Math.min(1, (now - start) / ms);
          window.scrollTo(0, from + (target - from) * ease(t));
          if (t < 1) requestAnimationFrame(step); else resolve();
        };
        requestAnimationFrame(step);
      });
    }
  };
  mount();
})();`;

const wait = (page, ms) => page.waitForTimeout(ms);
const REST = { x: 1252, y: 430 };

async function pointAt(page, target, { ms = 600, fx = 0.5, fy = 0.5 } = {}) {
  let x;
  let y;
  if (typeof target === "object" && "x" in target && typeof target.boundingBox !== "function") {
    ({ x, y } = target);
  } else {
    const locator = typeof target === "string" ? page.locator(target).first() : target;
    const box = await locator.boundingBox();
    if (!box) throw new Error(`Nothing to point at: ${target}`);
    x = box.x + box.width * fx;
    y = box.y + box.height * fy;
  }
  await page.evaluate(([px, py, duration]) => window.__demo.move(px, py, duration), [x, y, ms]);
  await page.mouse.move(x, y);
  return { x, y };
}

async function click(page, target, options = {}) {
  const { x, y } = await pointAt(page, target, options);
  await wait(page, 120);
  await page.evaluate(() => window.__demo.ripple());
  if (!options.fake) await page.mouse.click(x, y);
  await wait(page, options.after ?? 250);
}

async function type(page, text, { min = 45, max = 110 } = {}) {
  for (const char of text) {
    await page.keyboard.type(char);
    await wait(page, min + noise(text + char + Math.random()) * (max - min) + (char === " " ? 40 : 0));
  }
}

const scrollTo = (page, y, ms = 1200) => page.evaluate(([to, duration]) => window.__demo.scrollTo(to, duration), [y, ms]);
const scrollToElement = async (page, selector, offset = 90, ms = 1200) => {
  const top = await page.locator(selector).first().evaluate(el => el.getBoundingClientRect().top + window.scrollY);
  await scrollTo(page, top - offset, ms);
};

async function settle(page) {
  await page.waitForLoadState("networkidle").catch(() => {});
  await page.evaluate(() => document.fonts.ready);
  await wait(page, 350);
}

// ---------------------------------------------------------------------------------------------
// The four clips. Each returns after marking where the GIF starts (mark("start")) and ends.
// ---------------------------------------------------------------------------------------------

const scenes = {
  async dashboard(page, mark) {
    await page.goto(`${BASE}/index.html`);
    await page.waitForSelector("#login-form");
    // The sign-in hero drifts and floats forever, which would change every GIF frame; this demo
    // holds it still at its final look (the page itself is unchanged).
    await page.addStyleTag({ content: ".hero-blob-teal, .hero-blob-sand, .hero-float-forecast, .hero-float-prep, .hero-ring"
      + " { animation: none !important; }" });
    await settle(page);
    await wait(page, 2600); // the hero's entrance finishes
    mark("start");
    await wait(page, 300);
    await click(page, "#email", { ms: 600, fx: 0.4 });
    await type(page, "meiling@example.com", { min: 35, max: 75 });
    await click(page, "#password", { ms: 400, fx: 0.4 });
    await type(page, "chickenrice88", { min: 30, max: 65 });
    await wait(page, 200);
    await click(page, ".login-button", { ms: 450, after: 0 });
    await page.waitForURL("**/dashboard.html");
    await page.waitForFunction(() => document.querySelector("#demand-total")?.textContent !== "--"
      && document.querySelectorAll("#heatmap .heat-cell").length > 300);
    await page.evaluate(() => document.fonts.ready);
    // Parked in the empty right-hand margin while the page scrolls under it.
    await pointAt(page, REST, { ms: 600 });
    await wait(page, 900);
    await scrollToElement(page, ".week-summary", 70, 480);
    await wait(page, 1500);
    await scrollToElement(page, ".heatmap-card", 70, 480);
    const cell = page.locator("#heatmap .heat-cell").nth(-10);
    const box = await cell.boundingBox();
    await pointAt(page, { x: box.x + box.width / 2, y: box.y + box.height / 2 }, { ms: 550 });
    await wait(page, 1100);
    await pointAt(page, REST, { ms: 400 });
    await scrollTo(page, 0, 520);
    await wait(page, 800);
    mark("end");
  },

  async forecast(page, mark) {
    await page.goto(`${BASE}/forecast.html`);
    await page.waitForFunction(() => !document.querySelector("#dish-select").disabled);
    await settle(page);
    mark("start");
    await wait(page, 600);
    // Native drop-downs and date pickers aren't drawn in a recording, so the pointer clicks and the
    // choice is made from the keyboard.
    await click(page, "#dish-select", { ms: 650, fake: true, after: 150 });
    await page.selectOption("#dish-select", "3");
    await wait(page, 700);
    // en-GB date field: day, month, year. The click lands on the day; the year is filled with the
    // up arrow (this year) in one step, since typing it digit by digit passes through dates like
    // 0202 that would briefly empty the chart.
    await click(page, "#forecast-date", { ms: 550, fx: 0.05, after: 150 });
    const [, mo, d] = TOMORROW.split("-");
    await type(page, `${d}${mo}`, { min: 80, max: 130 });
    await wait(page, 120);
    await page.keyboard.press("ArrowUp");
    if (await page.inputValue("#forecast-date") !== TOMORROW) await page.fill("#forecast-date", TOMORROW);
    await page.locator("#forecast-date").dispatchEvent("change");
    await wait(page, 350);
    await click(page, "#forecast-button", { ms: 500, after: 0 });
    await page.waitForFunction(() => document.querySelector("#predicted-demand").textContent !== "--");
    await wait(page, 250);
    await scrollToElement(page, ".chart-card", 24, 800);
    await pointAt(page, { x: 1010, y: 200 }, { ms: 500 });
    await wait(page, 1500);
    await click(page, '.toggle-button[data-view="same-weekday"]', { ms: 600 });
    await wait(page, 1900);
    await pointAt(page, REST, { ms: 450 });
    await scrollTo(page, 0, 800);
    await wait(page, 600);
    mark("end");
  },

  async prep(page, mark) {
    await page.goto(`${BASE}/prep.html`);
    await page.waitForSelector("#portion-list .prep-row");
    await page.waitForSelector("#inventory-list details");
    await settle(page);
    mark("start");
    await wait(page, 600);
    await pointAt(page, "#portion-list .prep-row:nth-child(1) .prep-amount", { ms: 650 });
    await wait(page, 1000);
    await click(page, "#prep-buffer", { ms: 550, fake: true, after: 150 });
    await page.selectOption("#prep-buffer", "20");
    await page.waitForSelector("#portion-list .prep-row");
    await wait(page, 150);
    await pointAt(page, "#portion-list .prep-row:nth-child(1) .prep-amount", { ms: 550 });
    await wait(page, 1200);
    await pointAt(page, REST, { ms: 400 });
    await scrollToElement(page, "#inventory-heading", 70, 550);
    const coconut = page.locator("#inventory-list details summary", { hasText: "Coconut milk" });
    await click(page, coconut, { ms: 550, fx: 0.15 });
    await wait(page, 2000);
    await click(page, coconut, { ms: 250, fx: 0.15, after: 150 });
    await pointAt(page, REST, { ms: 350 });
    await scrollTo(page, 0, 600);
    await click(page, "#prep-buffer", { ms: 500, fake: true, after: 150 });
    await page.selectOption("#prep-buffer", "");
    await page.waitForSelector("#portion-list .prep-row");
    await pointAt(page, { x: 1010, y: 610 }, { ms: 500 });
    await wait(page, 500);
    mark("end");
  },

  async assistant(page, mark) {
    await page.goto(`${BASE}/dashboard.html`);
    await page.waitForSelector(".assistant-bubble");
    await page.waitForFunction(() => document.querySelectorAll("#heatmap .heat-cell").length > 300);
    await settle(page);
    mark("start");
    await wait(page, 500);
    await click(page, ".assistant-bubble", { ms: 700, after: 400 });
    await type(page, "How many chicken rice should I prep for Saturday?", { min: 22, max: 50 });
    await wait(page, 200);
    await click(page, ".assistant-send", { ms: 450, after: 0 });
    await pointAt(page, { x: 560, y: 470 }, { ms: 700 });
    await page.waitForSelector(".assistant-message.assistant:nth-of-type(3)");
    await wait(page, 3600);
    await click(page, ".assistant-close", { ms: 650, after: 300 });
    await pointAt(page, { x: 1010, y: 610 }, { ms: 550 });
    await wait(page, 500);
    mark("end");
  }
};

// ---------------------------------------------------------------------------------------------
// Browser-window frame for each GIF: rendered here, laid over the video by make-gifs.sh. The
// content area is transparent, with rounded corners, so the video shows through.
// ---------------------------------------------------------------------------------------------

const FRAME = { width: 960, margin: 16, bar: 34, contentWidth: 928, contentHeight: 580 };

async function renderFrame(browser, clip) {
  const height = FRAME.margin * 2 + FRAME.bar + FRAME.contentHeight;
  const page = await browser.newPage({ viewport: { width: FRAME.width, height }, deviceScaleFactor: 1 });
  await page.setContent(`<!doctype html><html><head>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@500;600&display=swap" rel="stylesheet">
    <style>
      html, body { margin: 0; background: transparent; }
      .window { position: absolute; left: ${FRAME.margin}px; top: ${FRAME.margin}px; width: ${FRAME.contentWidth}px;
        height: ${FRAME.bar + FRAME.contentHeight}px; border-radius: 10px; box-shadow: 0 0 0 ${FRAME.margin * 4}px #fffcf5; overflow: hidden; }
      .bar { height: ${FRAME.bar}px; background: #f1efe9; border-bottom: 1px solid #e2ded5; box-sizing: border-box;
        display: flex; align-items: center; justify-content: center; position: relative;
        font: 600 13px Inter, Arial, sans-serif; color: #425664; }
      .dots { position: absolute; left: 14px; top: 50%; transform: translateY(-50%); display: flex; gap: 7px; }
      .dots span { width: 11px; height: 11px; border-radius: 50%; background: #d4d0c7; }
      .dots span:first-child { background: #1ba098; }
      .title { display: flex; align-items: center; gap: 7px; }
      .title img { width: 16px; height: 16px; }
      .title em { font-style: normal; font-weight: 500; color: #66767f; }
      .edge { position: absolute; inset: 0; border: 1px solid #d3d0c8; border-radius: 10px; }
    </style></head><body>
    <div class="window">
      <div class="bar"><div class="dots"><span></span><span></span><span></span></div>
        <div class="title"><img src="${BASE}/images/icons/icon.svg" alt="">Chicky <em>· ${TITLES[clip]}</em></div></div>
      <div class="edge"></div>
    </div></body></html>`);
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(300);
  const file = path.join(WORK, `frame-${clip}.png`);
  await page.screenshot({ path: file, omitBackground: true });
  await page.close();
  return file;
}

// ---------------------------------------------------------------------------------------------

async function record(browser, clip) {
  const videoDir = path.join(WORK, `video-${clip}`);
  fs.rmSync(videoDir, { recursive: true, force: true });
  const state = { signedIn: clip !== "dashboard", unmocked: [] };
  const context = await browser.newContext({
    viewport: VIEWPORT, deviceScaleFactor: 1, locale: "en-GB", timezoneId: "Asia/Singapore",
    recordVideo: { dir: videoDir, size: VIEWPORT }
  });
  await context.addInitScript(CURSOR_SCRIPT);
  // A fresh visit each time: no remembered prep buffer, assistant conversation or mute setting.
  await installMocks(context, state);
  const page = await context.newPage();
  const started = Date.now();
  const marks = {};
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("console", message => { if (message.type() === "error") errors.push(message.text()); });
  try {
    await scenes[clip](page, name => { marks[name] = (Date.now() - started) / 1000; });
  } catch (error) {
    await page.screenshot({ path: path.join(WORK, `failed-${clip}.png`) }).catch(() => {});
    error.message += ` (screenshot: ${path.join(WORK, `failed-${clip}.png`)}; page errors: ${errors.join(" | ") || "none"})`;
    await context.close();
    throw error;
  }
  await page.waitForTimeout(400);
  const video = page.video();
  const closing = (Date.now() - started) / 1000;
  await context.close();
  const webm = path.join(WORK, `${clip}.webm`);
  fs.renameSync(await video.path(), webm);
  fs.rmSync(videoDir, { recursive: true, force: true });
  // The video starts a little after the page is created (how long varies) but ends when the context
  // closes, so the clip's start is counted back from the end of the video.
  const probe = spawnSync("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", webm], { encoding: "utf8" });
  const length = Number(probe.stdout.trim());
  if (!(length > 0)) throw new Error(`ffprobe couldn't read ${webm}`);
  const start = Math.max(0, length - (closing - marks.start));
  const duration = marks.end - marks.start;
  fs.writeFileSync(path.join(WORK, `${clip}.trim`), `${start.toFixed(2)} ${duration.toFixed(2)}\n`);
  return { clip, start, duration, unmocked: state.unmocked, errors };
}

(async () => {
  fs.mkdirSync(WORK, { recursive: true });
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  const results = [];
  try {
    for (const clip of ONLY) {
      if (!scenes[clip]) throw new Error(`Unknown clip: ${clip}`);
      await renderFrame(browser, clip);
      const result = await record(browser, clip);
      results.push(result);
      console.log(`${clip}: ${result.duration.toFixed(1)} s from ${result.start.toFixed(2)} s`
        + ` · unmocked requests: ${result.unmocked.length ? result.unmocked.join(", ") : "none"}`
        + (result.errors.length ? ` · page errors: ${result.errors.join(" | ")}` : ""));
    }
  } finally {
    await browser.close();
  }
  if (results.some(result => result.unmocked.length)) {
    throw new Error("Some API requests weren't mocked (listed above).");
  }
  if (process.env.NO_GIF !== "1") {
    const run = spawnSync("bash", [path.join(OUT, "make-gifs.sh"), WORK, ...ONLY], { stdio: "inherit" });
    if (run.status !== 0) throw new Error("make-gifs.sh failed");
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
