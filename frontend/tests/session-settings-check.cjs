// Start frontend/server.mjs, then PW=<playwright package path> node frontend/tests/session-settings-check.cjs.
const { chromium } = require(process.env.PW || "playwright");
const assert = require("node:assert/strict");
const path = require("node:path");

(async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    for (const width of [360, 375, 393, 414, 1280]) {
      const context = await browser.newContext({ viewport: { width, height: 900 }, hasTouch: width < 500 });
      let settings = { idleMinutes: 60, absoluteMinutes: 480 };
      let failSave = false;
      const errors = [];
      await context.route("**/api/**", route => {
        const url = new URL(route.request().url());
        const json = (body, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
        if (url.pathname === "/api/account/session-settings") {
          if (route.request().method() === "PUT") {
            if (failSave) return json({ message: "Couldn’t save settings. Try again." }, 500);
            settings = route.request().postDataJSON();
          }
          return json(settings);
        }
        if (url.pathname === "/api/profile") return json({ name: "Test owner", email: "owner@example.test", emailVerified: true });
        if (url.pathname === "/api/chat-links") return json({ channels: [] });
        if (url.pathname === "/api/csrf") return json({ headerName: "X-XSRF-TOKEN", token: "test" });
        if (url.pathname === "/api/assistant/status") return json({ available: false, voice: false });
        return json({});
      });
      const page = await context.newPage();
      page.on("pageerror", error => errors.push(error.message));
      await page.goto(`${process.env.BASE || "http://localhost:5500"}/account.html`);
      await page.waitForFunction(() => !document.getElementById("session-idle").disabled);
      assert.equal(await page.inputValue("#session-idle"), "60");
      assert.equal(await page.inputValue("#session-absolute"), "8");
      assert.equal(await page.isDisabled("#session-save"), true);
      await page.fill("#session-idle", "120");
      await page.fill("#session-absolute", "1");
      assert.equal(await page.isDisabled("#session-save"), true);
      await page.fill("#session-idle", "30");
      await page.fill("#session-absolute", "2");
      await page.click("#session-save");
      await page.getByText("Saved. These durations apply next time you sign in.").waitFor();
      assert.deepEqual(settings, { idleMinutes: 30, absoluteMinutes: 120 });
      await page.reload();
      await page.waitForFunction(() => document.getElementById("session-idle").value === "30");
      await page.click("#session-defaults");
      failSave = true;
      await page.click("#session-save");
      await page.locator("#session-status.is-error").waitFor();
      assert.deepEqual(settings, { idleMinutes: 30, absoluteMinutes: 120 });
      failSave = false;
      await page.click("#session-save");
      await page.locator("#session-status.is-success").waitFor();
      assert.deepEqual(settings, { idleMinutes: 60, absoluteMinutes: 480 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      if (width < 500) {
        assert.equal(await page.$eval("#session-idle", el => parseFloat(getComputedStyle(el).fontSize) >= 16), true);
        for (const selector of ["#session-idle", "#session-absolute", "#session-defaults", "#session-save"]) {
          const box = await page.locator(selector).boundingBox();
          assert.ok(box.width >= 44 && box.height >= 44, selector);
        }
      }
      await page.screenshot({ path: path.resolve(`backend/target/session-settings-${width}.png`), fullPage: true });
      assert.deepEqual(errors, []);
      console.log(`Passed session settings: ${width}px`);
      await context.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
