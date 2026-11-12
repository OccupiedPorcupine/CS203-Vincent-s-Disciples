// Small motion helpers shared by every page (see DESIGN.md, "Motion"). Load after base.css,
// before the page's own script.
//
// Nothing here animates arriving on a page or moving between pages: content is in its final place on the first
// frame, and figures are written as they are (no count-up, so a number never shows a value it isn't). What remains
// is feedback to something the user does, and scrolling:
//  - "Loading…" / "Checking…" text gets .is-loading, which base.css shows in the muted colour.
//  - chickyMotion.leave(element) fades and folds a row away when it is removed; it skips the animation with
//    prefers-reduced-motion.
//  - Primary buttons get the fill that grows from the pointer (the look is .primary-button in css/base.css).
//  - Pages that load Lenis (everything signed-in) get smooth scrolling for a mouse wheel, from the one shared set-up
//    below, and cards marked [data-spotlight] get a pointer-following light.
// The library is optional: without it, or with reduced motion, scrolling is the browser's own.
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

  // ---- Cursor-origin fill on primary buttons (the look is .primary-button in css/base.css) ----
  // A dark circle grows from where the pointer entered the button until it covers it, and shrinks back to that
  // same spot when the pointer leaves; the origin does not follow the pointer. Keyboard focus (focus-visible) and a
  // touch press grow it from the centre. It only toggles `is-filled` and sets --fill-x, --fill-y and --fill-d (a
  // circle whose radius reaches the farthest corner from that origin, so it always covers the whole button). It never
  // clicks anything: hover, focus and press are only a preview, and the button's own handler does the work.
  // The listeners are on the document, so buttons that a page script adds later get it too.
  const FILL = ".primary-button, [data-cursor-fill]";
  const fillState = new WeakMap();   // button -> which of hover, focus and press are holding it filled
  let fillControl = null;

  function startFillButtons() {
    if (fillControl) return;
    fillControl = new AbortController();
    const { signal } = fillControl;

    const stateOf = button => {
      if (!fillState.has(button)) fillState.set(button, { hover: false, focus: false, press: false });
      return fillState.get(button);
    };
    const sync = button => {
      const state = stateOf(button);
      button.classList.toggle("is-filled", state.hover || state.focus || state.press);
    };
    // The first of hover, focus and press to arrive sets the origin; the others join it where it is.
    const begin = (button, kind, x, y) => {
      const state = stateOf(button);
      if (!(state.hover || state.focus || state.press)) {
        const box = button.getBoundingClientRect();
        const ox = x ?? box.width / 2, oy = y ?? box.height / 2;
        const reach = Math.max(Math.hypot(ox, oy), Math.hypot(box.width - ox, oy), Math.hypot(ox, box.height - oy), Math.hypot(box.width - ox, box.height - oy));
        button.style.setProperty("--fill-x", `${ox}px`);
        button.style.setProperty("--fill-y", `${oy}px`);
        button.style.setProperty("--fill-d", `${reach * 2 + 2}px`);
      }
      state[kind] = true;
      sync(button);
    };
    const end = (button, kind) => {
      stateOf(button)[kind] = false;
      sync(button);
    };
    const buttonOf = target => (target instanceof Element ? target.closest(FILL) : null);
    const hovers = event => event.pointerType === "mouse" || event.pointerType === "pen";
    const on = (type, handler) => document.addEventListener(type, handler, { signal, capture: true });

    on("pointerover", event => {
      const button = buttonOf(event.target);
      if (!button || !hovers(event) || button.contains(event.relatedTarget)) return;
      const box = button.getBoundingClientRect();
      begin(button, "hover", event.clientX - box.left, event.clientY - box.top);
    });
    on("pointerout", event => {
      const button = buttonOf(event.target);
      if (!button || !hovers(event) || button.contains(event.relatedTarget)) return;
      end(button, "hover");
    });
    on("focusin", event => {
      const button = buttonOf(event.target);
      if (button && button.matches(":focus-visible")) begin(button, "focus");
    });
    on("focusout", event => {
      const button = buttonOf(event.target);
      if (button) end(button, "focus");
    });
    on("pointerdown", event => {
      const button = buttonOf(event.target);
      if (button && event.pointerType === "touch") begin(button, "press");
    });
    // A press ends when the finger lifts or the gesture is taken over (a scroll, a call); neither may leave it stuck.
    ["pointerup", "pointercancel"].forEach(type => on(type, event => {
      const button = buttonOf(event.target);
      if (!button) return;
      end(button, "press");
      if (type === "pointercancel") end(button, "hover");
    }));
  }

  function stopFillButtons() {
    fillControl?.abort();
    fillControl = null;
    document.querySelectorAll(`${FILL}.is-filled`).forEach(button => {
      fillState.delete(button);
      button.classList.remove("is-filled");
    });
  }

  // ---- Smooth scrolling (Lenis) ----
  // One shared set-up for every page that loads the library, with one instance and one frame loop (autoRaf). It is only
  // for a mouse wheel on a desktop: touch screens keep their own (better) momentum, reduced motion and a running guided
  // tour keep native scrolling, and so does a page where the library failed to load. Scrollable things inside the page
  // (dialogs, the assistant panel, tables, text areas, lists) keep native scrolling too. Anchor links, keyboard scrolling
  // and the browser's scroll restoration go through the browser's own scroll position, which Lenis follows.
  // The sign-in page doesn't load it: Lenis switches off pointer events on iframes, and Google's button is one.
  const tourRunning = () => document.documentElement.classList.contains("guide-active") || Boolean(safeSession("chicky-guide-tour"));
  function safeSession(key) { try { return sessionStorage.getItem(key); } catch (error) { return null; } }

  let smooth = null;

  function startSmoothScroll() {
    const { Lenis } = window;
    if (smooth || !Lenis || reduced() || tourRunning()) return;
    if (!window.matchMedia("(hover: hover) and (pointer: fine)").matches) return;

    const lenis = new Lenis({
      autoRaf: true,
      allowNestedScroll: true,
      prevent: node => Boolean(node.closest?.("dialog, .assistant-panel, .guide-layer")),
      anchors: true,
      stopInertiaOnNavigate: true,
      lerp: 0.12,
      wheelMultiplier: 0.9
    });

    // A guided tour scrolls by itself, and an open modal dialog must not scroll the page behind it.
    const pause = () => (tourRunning() || document.querySelector("dialog[open]")) ? lenis.stop() : lenis.start();
    const watcher = new MutationObserver(pause);
    watcher.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });
    watcher.observe(document.body, { attributes: true, attributeFilter: ["open"], subtree: true });
    smooth = { lenis, watcher };
  }

  function stopSmoothScroll() {
    if (!smooth) return;
    smooth.watcher.disconnect();
    smooth.lenis.destroy();
    smooth = null;
  }

  // ---- Pointer light on [data-spotlight] cards ----
  // Adapted from React Bits' SpotlightCard: a soft teal glow under the pointer and a brighter rim on the nearest edge.
  // The original runs a requestAnimationFrame loop per card; here one delegated pointermove listener writes two CSS
  // variables (batched per frame) and CSS draws it. What is under the pointer is looked up when drawing, not remembered
  // from the last move: after a scroll the pointer has not moved but a different card is beneath it.
  let light = null;

  function startPointerLight() {
    if (light || reduced() || !window.matchMedia("(hover: hover) and (pointer: fine)").matches) return;
    if (!document.querySelector("[data-spotlight]")) return;
    let frame = 0;
    let lit = null;
    let last = null;
    const paint = () => {
      frame = 0;
      if (!last) return;
      const card = document.elementFromPoint(last.clientX, last.clientY)?.closest?.("[data-spotlight]") ?? null;
      if (lit && lit !== card) lit.classList.remove("is-lit");
      lit = card;
      if (!card) return;
      const box = card.getBoundingClientRect();
      card.style.setProperty("--spot-x", `${last.clientX - box.left}px`);
      card.style.setProperty("--spot-y", `${last.clientY - box.top}px`);
      card.classList.add("is-lit");
    };
    const schedule = () => { if (!frame) frame = requestAnimationFrame(paint); };
    const move = event => { last = event; schedule(); };
    const leaveWindow = () => { last = null; lit?.classList.remove("is-lit"); lit = null; };
    document.addEventListener("pointermove", move, { passive: true });
    window.addEventListener("scroll", schedule, { capture: true, passive: true });
    document.documentElement.addEventListener("pointerleave", leaveWindow);
    light = () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("pointermove", move);
      window.removeEventListener("scroll", schedule, { capture: true });
      document.documentElement.removeEventListener("pointerleave", leaveWindow);
      leaveWindow();
      light = null;
    };
  }

  // ---- Wiring ----

  let watching = false;
  function watch() {
    const page = document.querySelector(".page") || document.body;
    markLoading(page);
    if (watching) return;
    watching = true;
    // Text is written as data arrives; keep the "Loading…" marker in step with it.
    new MutationObserver(() => markLoading(page)).observe(page, { subtree: true, childList: true, characterData: true });
  }

  const start = () => { watch(); startFillButtons(); startSmoothScroll(); startPointerLight(); };
  const stop = () => { stopFillButtons(); stopSmoothScroll(); light?.(); };

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start);
  else start();

  // The page can come back from the back/forward cache with its timers gone; tear down and start again.
  window.addEventListener("pagehide", stop);
  window.addEventListener("pageshow", event => { if (event.persisted) start(); });

  return { leave };
})();
