// Regenerates the screenshots in frontend/design/screenshots with a mocked API (no backend or
// login needed). Start the frontend first (node frontend/server.mjs), then from the repo root:
//   npm install --prefix /tmp/chicky-pw playwright
//   PW=/tmp/chicky-pw/node_modules/playwright node frontend/design/capture-screenshots.cjs
// Uses the installed Google Chrome, or Playwright's own Chromium when Chrome is not installed.
// BASE overrides the frontend address (default http://127.0.0.1:5500).
const { chromium } = require(process.env.PW || "playwright");
const path = require("path");

const capture = async (page, out) => {
  const OUT = out;
  const BASE = process.env.BASE || "http://127.0.0.1:5500";
  const dishes = [
    { id: 1, name: "Chicken rice" },
    { id: 2, name: "Char siew rice" },
    { id: 3, name: "Laksa" },
    { id: 4, name: "Nasi lemak" }
  ];
  const day = (offset) => {
    const d = new Date(new Date().toISOString().slice(0, 10) + "T00:00:00Z");   // sample sales end yesterday, so "last 7 days" always has data
    d.setUTCDate(d.getUTCDate() + offset - 1);
    return d.toISOString().slice(0, 10);
  };
  const sales = (id) => Array.from({ length: 35 }, (_, i) => ({
    date: day(i - 34),
    quantitySold: Math.round([42, 30, 24, 18][id - 1] + 8 * Math.sin(i * 0.9 + id) + (i % 7 === 5 ? 10 : 0))
  }));
  const money = { 1: { price: 4.5, unitCost: 1.7 }, 2: { price: 5, unitCost: 2.1 }, 3: { price: 5.5, unitCost: 2.4 }, 4: { price: 4, unitCost: 1.5 } };
  const json = (route, body) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

  let signedIn = false;
  const context = page.context();
  await context.route("http://localhost:8080/**", async (route) => {
    const url = new URL(route.request().url());
    const p = url.pathname;
    if (p === "/api/csrf") return json(route, { headerName: "X-XSRF-TOKEN", token: "t" });
    if (p === "/api/profile") return signedIn
      ? json(route, { name: "Mei Ling", email: "mei@example.com" })
      : route.fulfill({ status: 401, contentType: "application/json", body: "{}" });
    if (p === "/api/config") return json(route, { googleClientId: "" });
    if (p === "/api/dishes") return json(route, dishes.map(d => ({ ...d, ...money[d.id] })));
    // Pages that show money, leftovers and prep: figures are derived from the sample sales, over whatever range is asked for.
    if (p === "/api/revenue") {
      const rows = dishes.map(d => {
        const portions = sales(d.id).slice(-7).reduce((sum, e) => sum + e.quantitySold, 0);
        return { dishId: d.id, name: d.name, portions, ...money[d.id], marginPerPortion: +(money[d.id].price - money[d.id].unitCost).toFixed(2),
          revenue: +(portions * money[d.id].price).toFixed(2), grossProfit: +(portions * (money[d.id].price - money[d.id].unitCost)).toFixed(2) };
      }).sort((a, b) => b.revenue - a.revenue);
      return json(route, { from: url.searchParams.get("from"), to: url.searchParams.get("to"), portions: rows.reduce((n, r) => n + r.portions, 0),
        revenue: +rows.reduce((n, r) => n + r.revenue, 0).toFixed(2), grossProfit: +rows.reduce((n, r) => n + r.grossProfit, 0).toFixed(2),
        unpricedPortions: 0, portionsWithoutCost: 0, dishes: rows });
    }
    if (p === "/api/revenue/daily") {
      const from = new Date(url.searchParams.get("from") + "T00:00:00Z"), to = new Date(url.searchParams.get("to") + "T00:00:00Z");
      const days = []; for (let d = new Date(from), i = 0; d <= to; d.setUTCDate(d.getUTCDate() + 1), i++) {
        const portions = dishes.reduce((n, x) => n + sales(x.id).slice(-7)[i % 7].quantitySold, 0);
        days.push({ date: d.toISOString().slice(0, 10), portions, revenue: +dishes.reduce((n, x) => n + sales(x.id).slice(-7)[i % 7].quantitySold * money[x.id].price, 0).toFixed(2), closed: false });
      }
      return json(route, { from: url.searchParams.get("from"), to: url.searchParams.get("to"), anyPrices: true, days });
    }
    if (p === "/api/waste") return json(route, { from: url.searchParams.get("from"), to: url.searchParams.get("to"), leftoverPortions: 9, leftoverCost: 17.4,
      leftoversWithoutCost: 0, soldOutDays: 3, daysRecorded: 2, dishes: [
        { dishId: 4, name: "Nasi lemak", leftoverPortions: 6, leftoverCost: 9, soldOutDays: 0, earliestSoldOutAt: null },
        { dishId: 3, name: "Laksa", leftoverPortions: 3, leftoverCost: 7.2, soldOutDays: 0, earliestSoldOutAt: null },
        { dishId: 1, name: "Chicken rice", leftoverPortions: 0, leftoverCost: null, soldOutDays: 2, earliestSoldOutAt: "13:10" },
        { dishId: 2, name: "Char siew rice", leftoverPortions: 0, leftoverCost: null, soldOutDays: 1, earliestSoldOutAt: "13:40" }] });
    if (p === "/api/summary/weekly") return json(route, { from: day(-6), to: day(0), portions: 1336, previousPortions: 1378, changePercent: -3, revenue: 5590.5,
      topDish: { name: "Chicken rice", count: 401 }, wasteRecorded: true, leftoverPortions: 9, leftoverCost: 17.4, mostSoldOut: { name: "Chicken rice", count: 2 },
      text: { en: `In the 7 days to ${new Date(day(0) + "T00:00:00Z").toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long", timeZone: "UTC" })}, you sold 1,336 portions, down 3% on the week before. Revenue was $5,591. Chicken rice sold the most, 401 portions. You had 9 portions left over, about $17.40 of food. Chicken rice sold out on 2 days.`, zh: "" } });
    if (p === "/api/actions") return json(route, []);
    if (p === "/api/recipes") return json(route, dishes.map(d => ({ dishId: d.id, dish: d.name, items: d.id === 1 ? [{ ingredient: "Rice", quantity: 150, unit: "g" }, { ingredient: "Chicken", quantity: 120, unit: "g" }] : [] })));
    if (p === "/api/ingredients") return json(route, []);
    if (p === "/api/prep-list") return json(route, { date: url.searchParams.get("date"), closed: null, bufferPercent: null, ingredients: [], skipped: [],
      dishes: dishes.map(d => ({ dishId: d.id, dish: d.name, portions: [45, 38, 24, 18][d.id - 1], expected: [42, 35, 22, 16][d.id - 1], low: [36, 29, 18, 12][d.id - 1],
        high: [52, 44, 28, 21][d.id - 1], method: "BASELINE", basis: "Average of your last 4 Fridays", hasRecipe: d.id === 1, rule: "MARGIN",
        why: "Running out costs you a plate, a leftover costs less, so this is enough on about 60% of days like it." })) });
    if (p === "/api/documents") return json(route, [{ id: 1, title: "Ah Huat Poultry price list", source: "TEXT", fileName: null, characters: 842, indexStatus: "INDEXED", updatedAt: "2026-10-20T08:00:00Z", body: "Whole chicken $4.20 per kg" }]);
    if (p === "/api/chat-links") return json(route, { channels: [] });
    if (p === "/api/account/session-settings") return json(route, { idleMinutes: 60, absoluteMinutes: 480, ordered: true });
    const m = p.match(/^\/api\/dishes\/(\d+)\/sales$/);
    if (m) return json(route, sales(Number(m[1])));
    if (p === "/api/forecast") return json(route, {
      dish: "Chicken rice", forecastDate: "2026-11-01", predictedDemand: 47.2, low: 41, high: 53, roughRange: false,
      method: "MODEL", fallbackReason: null, basis: "Forecast model (Chronos-2), from your daily sales and public holidays",
      note: null
    });
    if (p === "/api/data-sources") return json(route, [
      { filename: "pos-export-2026-q3.csv", sha256: "9f2c1ab4e7d0c3aa", uploadedAt: "2026-10-28T09:12:00Z", sizeBytes: 482113, usedByModel: true },
      { filename: "accounting-sept.xlsx", sha256: "41be09c2d3f1aa77", uploadedAt: "2026-10-02T14:40:00Z", sizeBytes: 1830211, usedByModel: false }
    ]);
    // Closed days older than 30 days aren't listed, so the example sits a fortnight from today.
    if (p === "/api/closures") return json(route, {
      restDays: ["MONDAY"],
      closedDates: [{ date: new Date(Date.now() + 14 * 86400000).toISOString().slice(0, 10), reason: "Family wedding", source: "OWNER" }],
      hours: null,
      hawkerCentre: { id: 1, name: "Adam Road Food Centre" },
      neaClosures: [{ startDate: "2026-12-07", endDate: "2026-12-08", reason: "Centre cleaning (NEA)", remarks: null, openDays: [] }]
    });
    if (p === "/api/closures/hawker-centres") return json(route, [{ id: 1, name: "Adam Road Food Centre", address: "2, Adam Road, Singapore 289876" }]);
    if (p === "/api/assistant/status") return json(route, { available: true, voice: true });
    if (p === "/api/assistant/messages") return json(route, {
      conversationId: "c1",
      reply: "Here’s what I heard. Check it and press Confirm to save.",
      proposal: {
        id: "p1",
        entries: [
          { date: day(0), dishId: 1, dishName: "Chicken rice", quantity: 42, spokenName: "chicken rice", previousQuantity: null },
          { date: day(0), dishId: 2, dishName: "Char siew rice", quantity: 30, spokenName: "char siu", previousQuantity: 28 },
          { date: day(0), dishId: null, newDish: true, newDishName: "Mee goreng", dishName: "Mee goreng", quantity: 12, spokenName: "mee goreng", previousQuantity: null }
        ]
      }
    });
    return route.fulfill({ status: 404, body: "" });
  });

  const shots = [];
  const shoot = async (name, opts = {}) => {
    await page.waitForTimeout(1400);
    if (opts.fullPage) {
      // The sidebar is as tall as the window, so a full-page shot is taken in a window as tall as the page.
      const size = page.viewportSize();
      await page.setViewportSize({ width: size.width, height: await page.evaluate(() => Math.ceil(document.documentElement.scrollHeight)) });
      await page.waitForTimeout(500);
      await page.screenshot({ path: `${OUT}/${name}.png` });
      await page.setViewportSize(size);
    } else {
      await page.screenshot({ path: `${OUT}/${name}.png`, ...opts });
    }
    shots.push(name);
  };

  await page.setViewportSize({ width: 1280, height: 860 });

  await page.goto(`${BASE}/design/styleguide.html`);
  await shoot("styleguide", { fullPage: true });

  await page.goto(`${BASE}/index.html`);
  await shoot("login");
  signedIn = true;

  await page.goto(`${BASE}/dashboard.html`);
  await shoot("dashboard", { fullPage: true });

  await page.goto(`${BASE}/forecast.html`);
  await page.fill("#forecast-date", new Date(Date.now() + 86400000).toISOString().slice(0, 10));
  await page.click("#forecast-button");
  await shoot("forecast", { fullPage: true });

  await page.goto(`${BASE}/data.html`);
  await shoot("data", { fullPage: true });

  for (const name of ["prep", "dishes", "documents", "account"]) {
    await page.goto(`${BASE}/${name}.html`);
    await shoot(name, { fullPage: true });
  }

  // The sidebar (wide screens): the menu is always open, and the account menu opens from its foot.
  await page.goto(`${BASE}/dashboard.html`);
  await page.waitForTimeout(600);
  await page.click(".account-menu summary");
  await shoot("navigation-menu", { clip: { x: 0, y: 0, width: 420, height: 860 } });

  // Assistant with a confirm card
  await page.goto(`${BASE}/forecast.html`);
  await page.click(".assistant-bubble");
  await page.fill(".assistant-input textarea", "today chicken rice 42, char siu 30, mee goreng 12");
  await page.press(".assistant-input textarea", "Enter");
  await page.waitForSelector(".assistant-card");
  const panel = await page.locator(".assistant-panel").boundingBox();
  await shoot("assistant", { clip: { x: panel.x - 24, y: panel.y - 24, width: panel.width + 48, height: panel.height + 120 } });

  // Guided tour
  await page.goto(`${BASE}/data.html`);
  await page.evaluate(() => window.chickyGuide?.start([
    { page: "data", url: "data.html", guideId: "upload-dropzone", message: "Drop your CSV or Excel file here, or click to choose it." },
    { page: "data", url: "data.html", guideId: "upload-button", message: "Then press Upload source." }
  ]));
  await shoot("guide");
  await page.keyboard.press("Escape");

  // Guided tour in motion, frame by frame, across a page change (see "Motion" in DESIGN.md).
  // Timings are approximate: each screenshot takes a little time itself.
  const frame = async (name, wait) => {
    await page.waitForTimeout(wait);
    await page.screenshot({ path: `${OUT}/${name}.png` });
    shots.push(name);
  };
  await page.setViewportSize({ width: 960, height: 600 });
  await page.goto(`${BASE}/dashboard.html`);
  await page.waitForTimeout(1400);
  await page.evaluate(() => window.chickyGuide.start([
    { page: "dashboard", url: "dashboard.html", guideId: "nav-data", message: "Open Data ingestion from here." },
    { page: "data", url: "data.html", guideId: "upload-dropzone", message: "Drop your CSV or Excel file here, or click to choose it." },
    { page: "data", url: "data.html", guideId: "upload-button", message: "Then press Upload source." }
  ]));
  await frame("tour-1-closing-in", 60);
  await frame("tour-2-landed", 1000);
  await page.click(".guide-next");
  await frame("tour-3-leaving", 150);
  await page.waitForURL("**/data.html");
  // A still screenshot taken while the page starts its smooth scroll can tear, so this frame comes
  // from the live screencast: the first one painted on the new page.
  const cdp = await page.context().newCDPSession(page);
  const arriving = new Promise(resolve => cdp.once("Page.screencastFrame", resolve));
  await cdp.send("Page.startScreencast", { format: "png" });
  const firstFrame = await arriving;
  await cdp.send("Page.stopScreencast");
  require("fs").writeFileSync(`${OUT}/tour-4-arriving.png`, Buffer.from(firstFrame.data, "base64"));
  shots.push("tour-4-arriving");
  await frame("tour-5-morphing", 150);
  await frame("tour-6-arrived", 1000);
  await page.click(".guide-next");
  await frame("tour-7-gliding", 120);
  await page.click(".guide-exit");
  await page.setViewportSize({ width: 1280, height: 860 });

  // Phone width
  await page.setViewportSize({ width: 390, height: 844 });
  for (const name of ["dashboard", "forecast", "data", "prep", "dishes", "documents", "account"]) {
    await page.goto(`${BASE}/${name}.html`);
    await shoot(`${name}-mobile`);
  }
  // The phone's menu sheet
  await page.goto(`${BASE}/dashboard.html`);
  await page.click(".navigation summary");
  await shoot("phone-menu", { clip: { x: 0, y: 0, width: 390, height: 520 } });
  await page.setViewportSize({ width: 1280, height: 860 });
  return shots;
};

(async () => {
  const out = path.join(__dirname, "screenshots");
  const browser = await chromium.launch({ channel: "chrome", headless: true })
    .catch(() => chromium.launch({ headless: true }));   // no installed Chrome: use Playwright's Chromium
  const page = await (await browser.newContext({ deviceScaleFactor: 1 })).newPage();
  try {
    console.log(await capture(page, out));
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
