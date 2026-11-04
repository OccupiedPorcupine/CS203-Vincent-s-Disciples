// Small motion helpers shared by every page (see DESIGN.md, "Motion"). Load after base.css,
// before the page's own script. Everything here does nothing with prefers-reduced-motion,
// except marking "Loading…" text, which base.css then leaves still.
//
//  - Key figures (.headline .number) count up to their value when a page script fills them in.
//  - "Loading…" / "Checking…" text gets .is-loading, which base.css turns into a slow shimmer.
//  - Heatmap squares get --n (their week), so base.css can fill the grid left to right.
//  - chickyMotion.leave(element) fades and folds an element away, then removes it.
const chickyMotion = (() => {
  const reduced = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const easeOut = t => 1 - Math.pow(1 - t, 3);

  // ---- Count-up figures ----

  // What we last wrote into each text node, so our own writes don't restart the count.
  const written = new WeakMap();
  const running = new WeakMap();
  const NUMBER = /^(.*?)(\d[\d,]*(?:\.\d+)?)(.*)$/s;

  function firstNumberNode(root) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (/\d/.test(node.data)) return node;
    }
    return null;
  }

  function countUp(figure) {
    const node = firstNumberNode(figure);
    if (!node || written.get(node) === node.data) return;
    const match = node.data.match(NUMBER);
    if (!match || reduced()) return;

    const [, prefix, digits, suffix] = match;
    const target = Number(digits.replace(/,/g, ""));
    const decimals = (digits.split(".")[1] || "").length;
    const grouped = digits.includes(",");
    if (!Number.isFinite(target) || target === 0) return;

    const format = value => {
      const fixed = value.toFixed(decimals);
      if (!grouped) return fixed;
      const [whole, fraction] = fixed.split(".");
      return whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",") + (fraction ? `.${fraction}` : "");
    };
    const finalText = node.data;
    const duration = Math.min(1100, 600 + Math.log10(target + 1) * 120);
    const token = {};
    running.set(figure, token);
    const start = performance.now();

    const frame = now => {
      if (running.get(figure) !== token || !node.isConnected) return;
      const t = Math.min(1, (now - start) / duration);
      const text = t < 1 ? prefix + format(target * easeOut(t)) + suffix : finalText;
      written.set(node, text);
      node.data = text;
      if (t < 1) requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
  }

  // ---- Loading text ----

  const LOADING = /^\s*(Loading|Checking)\b/;
  function markLoading(root = document) {
    root.querySelectorAll(".note, .status-text, .empty-state, .lede").forEach(element => {
      element.classList.toggle("is-loading", LOADING.test(element.textContent));
    });
  }

  // ---- Heatmap squares: one step per week (column) ----

  function numberHeatmap(heatmap) {
    if (reduced()) return;
    let index = 0;
    heatmap.querySelectorAll(".heat-cell").forEach(cell => {
      cell.style.setProperty("--n", String(Math.floor(index++ / 7)));
    });
  }

  // ---- Leaving ----

  function leave(element) {
    if (reduced() || !element.animate) {
      element.remove();
      return Promise.resolve();
    }
    const height = element.getBoundingClientRect().height;
    element.classList.add("is-leaving");
    const animation = element.animate([
      { opacity: 1, transform: "translateX(0)", height: `${height}px` },
      { opacity: 0, transform: "translateX(12px)", height: `${height}px`, offset: 0.5 },
      { opacity: 0, transform: "translateX(12px)", height: "0px", paddingTop: "0px", paddingBottom: "0px", borderBottomWidth: "0px" }
    ], { duration: 420, easing: "cubic-bezier(0.2, 0.8, 0.2, 1)" });
    return animation.finished.catch(() => {}).then(() => element.remove());
  }

  // ---- Wiring ----

  function watch() {
    const page = document.querySelector(".page") || document.body;
    markLoading(page);
    page.querySelectorAll(".heatmap").forEach(numberHeatmap);

    const pending = new Set();
    const observer = new MutationObserver(records => {
      let loadingChanged = false;
      for (const record of records) {
        const target = record.target.nodeType === Node.TEXT_NODE ? record.target.parentElement : record.target;
        if (!target) continue;
        const figure = target.closest(".headline .number");
        if (figure) pending.add(figure);
        if (target.classList.contains("heatmap") && record.addedNodes.length) numberHeatmap(target);
        loadingChanged = true;
      }
      pending.forEach(countUp);
      pending.clear();
      if (loadingChanged) markLoading(page);
    });
    observer.observe(page, { subtree: true, childList: true, characterData: true });
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", watch);
  else watch();

  return { leave };
})();
