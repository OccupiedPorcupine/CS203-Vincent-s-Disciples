// Phone check for issue #47: signs in, records sales through the assistant and from an Actions
// needed card, gets a forecast, prices a dish, opens the prep list and follows a guided tour, in
// WebKit (Safari's engine) at phone sizes from 360px to 414px, with a mocked API (no backend or login
// needed). On every page it fails if the home-screen app tags are missing, the page scrolls
// sideways, a field would make iOS zoom in (text under 16px), a control is smaller than 44px, or
// the floating assistant button covers a control.
//
// Start the frontend (node frontend/server.mjs), then from the repo root:
//   npm install --prefix /tmp/chicky-pw playwright && npx --prefix /tmp/chicky-pw playwright install webkit
//   PW=/tmp/chicky-pw/node_modules/playwright node frontend/tests/phone-check.cjs
// BASE overrides the frontend address (default http://127.0.0.1:5500).
const { webkit } = require(process.env.PW || "playwright");

const BASE = process.env.BASE || "http://127.0.0.1:5500";
// 360px and 414px are the narrowest and widest common phones (#47 asks for 360–414px).
const PHONES = [
  { name: "small Android (360px)", width: 360, height: 780 },
  { name: "iPhone SE / mini (375px)", width: 375, height: 667 },
  { name: "iPhone 16 (393px)", width: 393, height: 852 },
  { name: "iPhone Plus (414px)", width: 414, height: 896 }
];
const SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";

// Checks the page as it is now; returns a list of problems.
function checkPage() {
  const problems = [];
  const width = innerWidth;
  const visible = el => {
    const rect = el.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0 && getComputedStyle(el).visibility !== "hidden"
      && !el.closest(".visually-hidden") && !el.closest("details:not([open]) > :not(summary)");
  };
  const label = el => (el.getAttribute("aria-label") || el.textContent || el.id || el.tagName).trim().replace(/\s+/g, " ").slice(0, 32);

  // The home-screen app tags and notch-aware viewport every page needs (DESIGN.md, "Phones").
  if (!document.querySelector('meta[name=viewport][content*="viewport-fit=cover"]')) problems.push("viewport tag lacks viewport-fit=cover");
  if (!document.querySelector("link[rel=manifest]") || !document.querySelector("link[rel=apple-touch-icon]")) problems.push("no home-screen app tags");

  const overflow = document.scrollingElement.scrollWidth - width;
  if (overflow > 0) problems.push(`scrolls sideways by ${overflow}px`);

  document.querySelectorAll("input:not([type=checkbox]):not([type=radio]):not([type=file]):not([type=hidden]), select, textarea").forEach(el => {
    const size = parseFloat(getComputedStyle(el).fontSize);
    if (visible(el) && size < 16) problems.push(`"${label(el)}" text is ${size}px, so iOS zooms in when it's tapped`);
  });

  // A checkbox or file input inside its <label> is tapped through the label, so the label is measured.
  document.querySelectorAll("a[href], button, summary, select, textarea, input:not([type=hidden]), label:has(> input)").forEach(el => {
    if (!visible(el) || (el.matches("input") && el.closest("label"))) return;
    const rect = el.getBoundingClientRect();
    if (rect.width < 44 || rect.height < 44) problems.push(`"${label(el)}" is ${Math.round(rect.width)}×${Math.round(rect.height)}px (under 44px)`);
  });
  return problems;
}

// A guided tour's bubble must fit on screen with its buttons, and its spotlight must be on screen.
function checkGuide() {
  const problems = [];
  const inside = rect => rect.left >= 0 && rect.top >= 0 && rect.right <= innerWidth && rect.bottom <= innerHeight;
  const bubble = document.querySelector(".guide-bubble");
  const ring = document.querySelector(".guide-ring");
  if (!bubble || !inside(bubble.getBoundingClientRect())) problems.push("the tour's bubble doesn't fit on screen");
  if (!ring || !inside(ring.getBoundingClientRect())) problems.push("the tour's spotlight is off screen");
  return problems;
}

// The floating assistant button must not sit on a control at the bottom of the page, where scrolling
// can't move it out from under the button. (Anywhere else the user can scroll past it.)
async function micCovers(page) {
  return page.evaluate(async () => {
    const bubble = document.querySelector(".assistant-bubble");
    if (!bubble) return [];
    const covered = new Set();
    scrollTo(0, document.scrollingElement.scrollHeight);
    await new Promise(resolve => setTimeout(resolve, 100));
    const b = bubble.getBoundingClientRect();
    document.querySelectorAll("a[href], button, input, select, textarea, summary").forEach(el => {
      if (el === bubble || el.closest(".assistant-panel, .guide-layer, details:not([open]) > :not(summary)")) return;
      const r = el.getBoundingClientRect();
      if (r.width && r.right > b.left && r.left < b.right && r.bottom > b.top && r.top < b.bottom) {
        covered.add(`assistant button covers "${(el.getAttribute("aria-label") || el.textContent).trim().slice(0, 32)}"`);
      }
    });
    scrollTo(0, 0);
    return [...covered];
  });
}

// Lets entrance animations (cards and rows rising in, see DESIGN.md "Motion") finish, so sizes
// are measured where things end up rather than mid-flight. Endless ones (shimmer, header) are skipped.
async function settle(page) {
  await page.evaluate(() => Promise.all(document.getAnimations()
    .filter(animation => animation.effect?.getComputedTiming().iterations !== Infinity)
    .map(animation => animation.finished.catch(() => {}))));
}

async function mockApi(context) {
  let signedIn = false;
  const json = (route, body, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
  const day = offset => {
    const date = new Date("2026-10-30T00:00:00Z");
    date.setUTCDate(date.getUTCDate() + offset);
    return date.toISOString().slice(0, 10);
  };
  await context.route("http://localhost:8080/**", route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/csrf") return json(route, { headerName: "X-XSRF-TOKEN", token: "t" });
    if (path === "/api/config") return json(route, { googleClientId: "" });
    if (path === "/api/auth/login") { signedIn = true; return json(route, { name: "Mei Ling", email: "mei@example.com" }); }
    if (path === "/api/profile") return signedIn ? json(route, { name: "Mei Ling", email: "mei@example.com" }) : json(route, {}, 401);
    if (path === "/api/dishes") return json(route, [
      { id: 1, name: "Chicken rice", price: 4.5, unitCost: 1.7 },
      { id: 2, name: "Char siew rice", price: null, unitCost: null }
    ]);
    if (path.endsWith("/price")) {
      const { price, unitCost } = route.request().postDataJSON();
      return json(route, { price, unitCost });
    }
    if (path.endsWith("/sales")) return json(route, Array.from({ length: 35 }, (_, i) => ({ date: day(i - 34), quantitySold: 30 + (i * 7) % 15 })));
    if (path === "/api/data-sources") return json(route, []);
    if (path === "/api/assistant/status") return json(route, { available: true, voice: false });
    if (path === "/api/assistant/messages") return json(route, {
      conversationId: "c1",
      reply: "Here’s what I heard. Check it and press Confirm to save.",
      proposal: { id: "p1", entries: [
        { date: "2026-10-30", dishId: 1, dishName: "Chicken rice", quantity: 42, spokenName: "chicken rice", previousQuantity: null },
        { date: "2026-10-30", dishId: 2, dishName: "Char siew rice", quantity: 30, spokenName: "char siew", previousQuantity: null }
      ] }
    });
    if (path === "/api/assistant/proposals/p1/confirm") return json(route, { saved: [{}, {}] });
    if (path === "/api/forecast") return json(route, {
      dish: "Chicken rice", forecastDate: "2026-10-31", predictedDemand: 47.2, low: 41, high: 53,
      roughRange: false, method: "MODEL", fallbackReason: null, basis: "the forecast model", note: null
    });
    if (path === "/api/actions") return json(route, [
      { key: "missing:" + day(-2), kind: "MISSING_SALES", title: "You haven't logged sales for yesterday", detail: "Add them so the forecast stays accurate.", actionLabel: "Add sales", href: null, recordDate: day(-2) },
      { key: "unpriced:2", kind: "UNPRICED", title: "Set prices for Char siew rice", detail: "Revenue leaves out dishes without a price.", actionLabel: "Set prices", href: "dishes.html", recordDate: null }
    ]);
    if (path === "/api/revenue") return json(route, {
      from: day(-6), to: day(0), portions: 420, revenue: 1890, grossProfit: 1176, unpricedPortions: 0, portionsWithoutCost: 0,
      dishes: [{ dishId: 1, name: "Chicken rice", portions: 420, price: 4.5, unitCost: 1.7, marginPerPortion: 2.8, revenue: 1890, grossProfit: 1176 }]
    });
    if (path === "/api/revenue/daily") return json(route, {
      from: day(-363), to: day(0), anyPrices: true,
      days: Array.from({ length: 364 }, (_, i) => ({ date: day(i - 363), portions: 60, revenue: 270 - (i * 13) % 90, closed: i % 7 === 3 }))
    });
    if (path.endsWith("/sales/" + day(-2))) return json(route, { date: day(-2), quantitySold: 40 });
    if (path === "/api/prep-list") return json(route, {
      date: day(1), closed: null, bufferPercent: null,
      dishes: [
        { dishId: 1, dish: "Chicken rice", portions: 53, expected: 47.2, low: 41, high: 53, method: "MODEL", basis: "the forecast model", hasRecipe: true },
        { dishId: 2, dish: "Char siew rice", portions: 36, expected: 31, low: 26, high: 36, method: "BASELINE", basis: "Average of your last 4 Saturdays", hasRecipe: false }
      ],
      ingredients: [
        { ingredient: "Rice", unit: "g", quantity: 7950, dishes: ["Chicken rice"] },
        { ingredient: "Roast chicken", unit: "g", quantity: 6360, dishes: ["Chicken rice"] }
      ],
      skipped: []
    });
    if (path === "/api/recipes") return json(route, [
      { dishId: 1, dish: "Chicken rice", items: [{ ingredient: "Rice", unit: "g", quantity: 150 }, { ingredient: "Roast chicken", unit: "g", quantity: 120 }] },
      { dishId: 2, dish: "Char siew rice", items: [] }
    ]);
    if (path === "/api/ingredients") return json(route, [{ name: "Rice", unit: "g" }, { name: "Roast chicken", unit: "g" }]);
    if (path === "/api/closures") return json(route, {
      restDays: ["TUESDAY"],
      closedDates: [{ date: day(14), reason: "Family wedding", source: "OWNER" }, { date: day(21), reason: "Centre cleaning (NEA)", source: "NEA" }],
      hours: { opensAt: "10:00", closesAt: "15:00" },
      hawkerCentre: { id: 1, name: "Adam Road Food Centre" },
      neaClosures: [{ startDate: day(21), endDate: day(22), reason: "Centre cleaning (NEA)", remarks: "Only Cooked Food Section is closed.", openDays: [] }]
    });
    if (path === "/api/closures/hawker-centres") return json(route, [
      { id: 1, name: "Adam Road Food Centre", address: "2, Adam Road, Singapore 289876" },
      { id: 2, name: "Old Airport Road Blk 51 (51 Old Airport Road Food Centre and Shopping Mall)", address: "51 Old Airport Road" }
    ]);
    if (path === "/api/waste") return json(route, { leftoverPortions: 12, leftoverCost: "20.40", leftoversWithoutCost: 0, soldOutDays: 2, daysRecorded: 3,
      dishes: [{ dishId: 1, name: "Chicken rice", leftoverPortions: 12, leftoverCost: "20.40", soldOutDays: 2, earliestSoldOutAt: "12:40" }] });
    return route.fulfill({ status: 404, body: "" });
  });
}

(async () => {
  const browser = await webkit.launch();
  const failures = [];

  for (const phone of PHONES) {
    const context = await browser.newContext({
      viewport: { width: phone.width, height: phone.height }, deviceScaleFactor: 3, isMobile: true, hasTouch: true, userAgent: SAFARI
    });
    await mockApi(context);
    const page = await context.newPage();
    page.on("pageerror", error => failures.push(`${phone.name}: page error: ${error.message}`));
    const check = async step => {
      await page.waitForTimeout(500);
      await settle(page);
      const problems = [...await page.evaluate(checkPage), ...await micCovers(page)];
      problems.forEach(problem => failures.push(`${phone.name}, ${step}: ${problem}`));
      console.log(`${problems.length ? "✗" : "✓"} ${phone.name}: ${step}`);
    };

    // Sign in
    await page.goto(`${BASE}/index.html`);
    await check("sign-in page");
    await page.fill("#email", "mei@example.com");
    await page.fill("#password", "correct horse battery");
    await page.tap("#login-form button[type=submit]");
    await page.waitForURL("**/dashboard.html");
    await check("dashboard");

    // Log a missed day from its Actions needed card
    await page.getByRole("button", { name: "Add sales" }).tap();
    await page.locator("#record-dialog[open]").waitFor();
    await check("record missed sales form");
    await page.locator("#record-dialog .record-row input").first().fill("40");
    await page.locator("#record-form button[type=submit]").tap();
    await page.locator("#record-dialog").waitFor({ state: "hidden" });

    // Record sales through the assistant
    await page.tap(".assistant-bubble");
    await page.fill(".assistant-input textarea", "today chicken rice 42, char siew 30");
    await page.tap(".assistant-send");
    await page.waitForSelector(".assistant-card");
    await check("assistant confirm card");
    await page.tap(".assistant-primary");
    await page.getByText("Saved 2 entries.").waitFor();
    await page.tap(".assistant-close");

    // Forecast, reached from the menu
    await page.tap(".navigation summary");
    await page.tap('.navigation a[href="forecast.html"]');
    await page.waitForURL("**/forecast.html");
    // Tomorrow in Singapore: the picker allows today to 7 days ahead of the real date, and the form
    // refuses anything else, so the mock's fixed dates don't work here.
    const tomorrow = new Date(Date.now() + 86400000).toLocaleDateString("en-CA", { timeZone: "Asia/Singapore" });
    await page.fill("#forecast-date", tomorrow);
    await page.tap("#forecast-button");
    await page.locator("#predicted-demand", { hasText: "47" }).waitFor();
    await check("forecast page");

    await page.tap(".navigation summary");
    await page.tap('.navigation a[href="data.html"]');
    await page.waitForURL("**/data.html");
    await check("data ingestion page");

    // Dishes: price a dish
    await page.tap(".navigation summary");
    await page.tap('.navigation a[href="dishes.html"]');
    await page.waitForURL("**/dishes.html");
    await page.getByLabel("Price of Char siew rice in S$").fill("5.00");
    await page.getByLabel("Cost of one Char siew rice in S$").fill("2.00");
    await page.tap('[aria-label="Save the price of Char siew rice"]');
    await page.getByText("Saved Char siew rice").waitFor();
    await check("dishes page");

    // Prep list, then a guided tour on it
    await page.tap(".navigation summary");
    await page.tap('.navigation a[href="prep.html"]');
    await page.waitForURL("**/prep.html");
    await page.getByText("Roast chicken").first().waitFor();
    await check("prep list page");
    await page.evaluate(() => window.chickyGuide.start([
      { page: "prep", url: "prep.html", guideId: "prep-date", message: "Pick the day you're preparing for." },
      { page: "prep", url: "prep.html", guideId: "recipe-list", message: "Say what one plate of each dish uses, and the prep list adds up the ingredients for you." }
    ]));
    for (const step of ["first step", "second step"]) {
      if (step !== "first step") await page.tap(".guide-next");
      await page.waitForTimeout(900);
      const problems = await page.evaluate(checkGuide);
      const guideButtons = await page.$$eval(".guide-actions button:not([hidden])", buttons => buttons.map(b => b.getBoundingClientRect()).filter(r => r.width < 44 || r.height < 44).length);
      if (guideButtons) problems.push(`${guideButtons} tour button(s) under 44px`);
      problems.forEach(problem => failures.push(`${phone.name}, guided tour ${step}: ${problem}`));
      console.log(`${problems.length ? "✗" : "✓"} ${phone.name}: guided tour, ${step}`);
    }
    await page.tap(".guide-exit");

    await context.close();
  }

  await browser.close();
  if (failures.length) {
    console.error(`\n${failures.length} problem(s):\n- ${failures.join("\n- ")}`);
    process.exit(1);
  }
  console.log("\nAll phone checks passed.");
})().catch(error => {
  console.error(error);
  process.exit(1);
});
