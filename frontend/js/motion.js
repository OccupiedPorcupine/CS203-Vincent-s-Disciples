// Small motion helpers shared by every page (see DESIGN.md, "Motion"). Load after base.css,
// before the page's own script. leave() skips its animation with prefers-reduced-motion.
//
//  - "Loading…" / "Checking…" text gets .is-loading, which base.css shows in the muted colour.
//  - Figures are written as they are: no count-up, so a number never shows a value it isn't.
//  - chickyMotion.leave(element) fades and folds an element away, then removes it.
const chickyMotion = (() => {
  const reduced = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  // ---- Loading text ----

  const LOADING = /^\s*(Loading|Checking)\b/;
  function markLoading(root = document) {
    root.querySelectorAll(".note, .status-text, .empty-state, .lede").forEach(element => {
      element.classList.toggle("is-loading", LOADING.test(element.textContent));
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

    const observer = new MutationObserver(() => markLoading(page));
    observer.observe(page, { subtree: true, childList: true, characterData: true });
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", watch);
  else watch();

  return { leave };
})();
