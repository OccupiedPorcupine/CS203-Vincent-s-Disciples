// Renders frontend/images/icons/icon.svg to the PNG sizes the home-screen app needs. Run from the
// repo root after changing the SVG (Playwright as in capture-screenshots.cjs):
//   PW=/tmp/chicky-pw/node_modules/playwright node frontend/design/render-icons.cjs
const { chromium } = require(process.env.PW || "playwright");
const fs = require("fs");
const path = require("path");

const icons = path.join(__dirname, "..", "images", "icons");
const svg = fs.readFileSync(path.join(icons, "icon.svg"), "utf8");
const sizes = { "apple-touch-icon.png": 180, "icon-192.png": 192, "icon-512.png": 512 };

(async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  const page = await browser.newPage();
  for (const [file, size] of Object.entries(sizes)) {
    await page.setViewportSize({ width: size, height: size });
    await page.setContent(`<style>html,body{margin:0}svg{display:block;width:${size}px;height:${size}px}</style>${svg}`);
    await page.screenshot({ path: path.join(icons, file), omitBackground: false });
    console.log(file, size);
  }
  await browser.close();
})().catch(error => {
  console.error(error);
  process.exit(1);
});
