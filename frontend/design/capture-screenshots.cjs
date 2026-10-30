// Regenerates the screenshots in frontend/design/screenshots with a mocked API (no backend or
// login needed). Start the frontend first (node frontend/server.mjs), then from the repo root:
//   npm install --prefix /tmp/chicky-pw playwright
//   PW=/tmp/chicky-pw/node_modules/playwright node frontend/design/capture-screenshots.cjs
// Uses the installed Google Chrome. BASE overrides the frontend address (default http://127.0.0.1:5500).
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
    const d = new Date("2026-10-30T00:00:00Z");
    d.setUTCDate(d.getUTCDate() + offset);
    return d.toISOString().slice(0, 10);
  };
  const sales = (id) => Array.from({ length: 35 }, (_, i) => ({
    date: day(i - 34),
    quantitySold: Math.round([42, 30, 24, 18][id - 1] + 8 * Math.sin(i * 0.9 + id) + (i % 7 === 5 ? 10 : 0))
  }));
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
    if (p === "/api/dishes") return json(route, dishes);
    const m = p.match(/^\/api\/dishes\/(\d+)\/sales$/);
    if (m) return json(route, sales(Number(m[1])));
    if (p === "/api/forecast") return json(route, { predicted_demand: 47.2 });
    if (p === "/api/data-sources") return json(route, [
      { filename: "pos-export-2026-q3.csv", sha256: "9f2c1ab4e7d0c3aa", uploadedAt: "2026-10-28T09:12:00Z", sizeBytes: 482113, usedByModel: true },
      { filename: "accounting-sept.xlsx", sha256: "41be09c2d3f1aa77", uploadedAt: "2026-10-02T14:40:00Z", sizeBytes: 1830211, usedByModel: false }
    ]);
    if (p === "/api/assistant/status") return json(route, { available: true, voice: true });
    if (p === "/api/assistant/messages") return json(route, {
      conversationId: "c1",
      reply: "Here’s what I heard. Check it and press Confirm to save.",
      proposal: {
        id: "p1",
        entries: [
          { date: "2026-10-30", dishId: 1, dishName: "Chicken rice", quantity: 42, spokenName: "chicken rice", previousQuantity: null },
          { date: "2026-10-30", dishId: 2, dishName: "Char siew rice", quantity: 30, spokenName: "char siu", previousQuantity: 28 },
          { date: "2026-10-30", dishId: null, newDish: true, newDishName: "Mee goreng", dishName: "Mee goreng", quantity: 12, spokenName: "mee goreng", previousQuantity: null }
        ]
      }
    });
    return route.fulfill({ status: 404, body: "" });
  });

  const shots = [];
  const shoot = async (name, opts = {}) => {
    await page.waitForTimeout(1400);
    await page.screenshot({ path: `${OUT}/${name}.png`, ...opts });
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
  await page.fill("#forecast-date", "2026-10-31");
  await page.click("#forecast-button");
  await shoot("forecast", { fullPage: true });

  await page.goto(`${BASE}/data.html`);
  await shoot("data", { fullPage: true });

  // Navigation menu open
  await page.goto(`${BASE}/dashboard.html`);
  await page.click(".navigation summary");
  await shoot("navigation-menu", { clip: { x: 0, y: 0, width: 520, height: 300 } });

  // Account menu open
  await page.click(".account-menu summary");
  await shoot("account-menu", { clip: { x: 1280 - 420, y: 0, width: 420, height: 260 } });

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
  for (const name of ["dashboard", "forecast", "data"]) {
    await page.goto(`${BASE}/${name}.html`);
    await shoot(`${name}-mobile`);
  }
  await page.setViewportSize({ width: 1280, height: 860 });
  return shots;
};

(async () => {
  const out = path.join(__dirname, "screenshots");
  const browser = await chromium.launch({ channel: "chrome", headless: true });
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
