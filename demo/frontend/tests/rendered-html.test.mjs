import assert from "node:assert/strict";
import test from "node:test";

async function render(path = "/") {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);
  return worker.fetch(
    new Request(`http://localhost${path}`, { headers: { accept: "text/html" } }),
    { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } },
    { waitUntil() {}, passThroughOnException() {} },
  );
}

test("server-renders the forecast page", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);
  const html = await response.text();
  assert.match(html, /<title>Chicken rice demo<\/title>/i);
  assert.match(html, /AH HUAT CHICKEN RICE/);
  assert.match(html, />Tomorrow</);
  assert.match(html, /Expected gross sales/);
  assert.match(html, /Item forecast/);
  assert.match(html, /aria-label="Sat, 19 Sep: S\$1,428"/);
  assert.match(html, /aria-pressed="false"/);
  assert.doesNotMatch(html, /Training runs/);
  assert.doesNotMatch(html, /hawker_sales\.xlsx/);
  assert.doesNotMatch(html, /codex-preview|Your site is taking shape/);
});

test("server-renders data sources on a separate page", async () => {
  const response = await render("/sources");
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /<title>Chicken rice demo<\/title>/i);
  assert.match(html, /Data sources/);
  assert.match(html, /hawker_sales\.xlsx/);
  assert.match(html, /Add a source/);
  assert.doesNotMatch(html, /Expected gross sales/);
  assert.doesNotMatch(html, /Training runs/);
});
