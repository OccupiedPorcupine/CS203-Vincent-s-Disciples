// Phone check for issue #47: signs in, records sales through the assistant and gets a forecast in
// WebKit (Safari's engine) at iPhone sizes, with a mocked API (no backend or login needed).
// On every page it fails if the page scrolls sideways, a field would make iOS zoom in (text under
// 16px), a control is smaller than 44px, or the floating assistant button covers a control.
//
// Start the frontend (node frontend/server.mjs), then from the repo root:
//   npm install --prefix /tmp/chicky-pw playwright && npx --prefix /tmp/chicky-pw playwright install webkit
//   PW=/tmp/chicky-pw/node_modules/playwright node frontend/tests/phone-check.cjs
// BASE overrides the frontend address (default http://127.0.0.1:5500).
const { webkit } = require(process.env.PW || "playwright");

const BASE = process.env.BASE || "http://127.0.0.1:5500";
const PHONES = [
  { name: "iPhone SE / mini (375px)", width: 375, height: 667 },
  { name: "iPhone 16 (393px)", width: 393, height: 852 }
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
    if (path === "/api/dishes") return json(route, [{ id: 1, name: "Chicken rice" }, { id: 2, name: "Char siew rice" }]);
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
    if (path === "/api/forecast") return json(route, { predicted_demand: 47.2 });
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
    await page.fill("#forecast-date", "2026-10-31");
    await page.tap("#forecast-button");
    await page.locator("#predicted-demand", { hasText: "47" }).waitFor();
    await check("forecast page");

    await page.tap(".navigation summary");
    await page.tap('.navigation a[href="data.html"]');
    await page.waitForURL("**/data.html");
    await check("data ingestion page");

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
