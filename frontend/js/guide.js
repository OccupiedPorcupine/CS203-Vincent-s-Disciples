// Guided tours from the assistant: dims everything except one element, explains it, and steps
// through the tour, moving between pages when a step is on another page. Steps come from the
// backend as [{page, url, guideId, message}]; guideId matches a data-guide-id attribute (or a
// temporary data-live-guide-id set by js/assistant.js). Load before js/assistant.js.
window.chickyGuide = (() => {
  const PAGE = location.pathname.split("/").pop().replace(/\.html$/, "") || "dashboard";
  const STATE_KEY = "chicky-guide-tour";
  const PADDING = 8;
  const GAP = 12;
  const EDGE = 16;

  let steps = [];
  let index = 0;
  let target = null;
  let layer = null;
  let frame = 0;

  function start(newSteps) {
    if (!newSteps?.length) return;
    steps = newSteps;
    index = 0;
    showCurrent();
  }

  // Continues a tour after moving to another page. If the user went somewhere the tour doesn't
  // go, the tour ends.
  function resume() {
    const state = readState();
    if (!state) return;

    steps = state.steps;
    const next = steps.findIndex((step, position) => position >= state.index && step.page === PAGE);
    if (next < 0) {
      clearState();
      return;
    }
    index = next;
    showCurrent();
  }

  function showCurrent() {
    const step = steps[index];
    writeState();

    if (step.page !== PAGE) {
      location.href = step.url;
      return;
    }

    target = findTarget(step.guideId);
    if (target) {
      // Links in the collapsed top-left menu need the menu open to be seen.
      target.closest("details:not([open])")?.setAttribute("open", "");
      target.scrollIntoView({ block: "center", inline: "nearest" });
    }
    render(target ? step.message : "I couldn’t find that part of the page, so I’ll skip it.");
  }

  function move(delta) {
    const nextIndex = index + delta;
    if (nextIndex < 0) return;
    if (nextIndex >= steps.length) {
      end();
      return;
    }
    index = nextIndex;
    showCurrent();
  }

  function end() {
    clearState();
    window.chickyAssistant?.stopSpeaking();
    cancelAnimationFrame(frame);
    window.removeEventListener("resize", schedulePosition);
    window.removeEventListener("scroll", schedulePosition, true);
    document.removeEventListener("keydown", onKeydown);
    layer?.remove();
    layer = null;
    target = null;
  }

  function findTarget(guideId) {
    const id = CSS.escape(guideId);
    const element = document.querySelector(`[data-guide-id="${id}"], [data-live-guide-id="${id}"]`);
    if (!element) return null;
    // Elements inside a closed menu have no size yet but are shown once it opens.
    const hiddenInMenu = element.closest("details:not([open])");
    return element.getClientRects().length || hiddenInMenu ? element : null;
  }

  function render(message) {
    if (!layer) build();

    layer.querySelector(".guide-progress").textContent = steps.length > 1 ? `Step ${index + 1} of ${steps.length}` : "";
    layer.querySelector(".guide-text").textContent = message;
    layer.querySelector(".guide-back").hidden = index === 0;
    layer.querySelector(".guide-next").textContent = index === steps.length - 1 ? "Done" : "Next";

    position();
    layer.querySelector(".guide-next").focus({ preventScroll: true });
    window.chickyAssistant?.speak(message);
  }

  function build() {
    layer = document.createElement("div");
    layer.className = "guide-layer";
    layer.innerHTML = `
      <div class="guide-shade" data-side="top"></div>
      <div class="guide-shade" data-side="bottom"></div>
      <div class="guide-shade" data-side="left"></div>
      <div class="guide-shade" data-side="right"></div>
      <div class="guide-ring" aria-hidden="true"></div>
      <div class="guide-bubble" role="dialog" aria-label="Assistant guide" aria-describedby="guide-text">
        <p class="guide-progress"></p>
        <p class="guide-text" id="guide-text" aria-live="polite"></p>
        <div class="guide-actions">
          <button class="guide-exit" type="button">Exit</button>
          <button class="guide-back" type="button">Back</button>
          <button class="guide-next" type="button">Next</button>
        </div>
      </div>`;
    document.body.append(layer);

    layer.querySelector(".guide-exit").addEventListener("click", end);
    layer.querySelector(".guide-back").addEventListener("click", () => move(-1));
    layer.querySelector(".guide-next").addEventListener("click", () => move(1));
    window.addEventListener("resize", schedulePosition);
    window.addEventListener("scroll", schedulePosition, true);
    document.addEventListener("keydown", onKeydown);
  }

  function onKeydown(event) {
    if (event.key === "Escape") end();
  }

  function schedulePosition() {
    cancelAnimationFrame(frame);
    frame = requestAnimationFrame(position);
  }

  // The four shades cover everything around the target and block clicks there; the target
  // itself stays visible and usable.
  function position() {
    if (!layer) return;
    const width = window.innerWidth;
    const height = window.innerHeight;
    const rect = target?.getBoundingClientRect();
    const hole = rect && rect.width + rect.height > 0
      ? { top: rect.top - PADDING, left: rect.left - PADDING, right: rect.right + PADDING, bottom: rect.bottom + PADDING }
      : { top: height / 2, left: width / 2, right: width / 2, bottom: height / 2 };

    place(layer.querySelector('[data-side="top"]'), 0, 0, width, Math.max(0, hole.top));
    place(layer.querySelector('[data-side="bottom"]'), 0, hole.bottom, width, Math.max(0, height - hole.bottom));
    place(layer.querySelector('[data-side="left"]'), 0, hole.top, Math.max(0, hole.left), hole.bottom - hole.top);
    place(layer.querySelector('[data-side="right"]'), hole.right, hole.top, Math.max(0, width - hole.right), hole.bottom - hole.top);

    const ring = layer.querySelector(".guide-ring");
    ring.hidden = !rect;
    place(ring, hole.left, hole.top, hole.right - hole.left, hole.bottom - hole.top);

    const bubble = layer.querySelector(".guide-bubble");
    const bubbleWidth = Math.min(320, width - 2 * EDGE);
    bubble.style.width = `${bubbleWidth}px`;
    const bubbleHeight = bubble.offsetHeight;

    let top;
    if (!rect) {
      top = (height - bubbleHeight) / 2;
    } else if (hole.bottom + GAP + bubbleHeight <= height - EDGE) {
      top = hole.bottom + GAP;
    } else if (hole.top - GAP - bubbleHeight >= EDGE) {
      top = hole.top - GAP - bubbleHeight;
    } else {
      top = height - bubbleHeight - EDGE;
    }
    const left = rect ? Math.min(Math.max(hole.left, EDGE), width - bubbleWidth - EDGE) : (width - bubbleWidth) / 2;
    bubble.style.top = `${top}px`;
    bubble.style.left = `${left}px`;
  }

  function place(element, left, top, width, height) {
    element.style.left = `${left}px`;
    element.style.top = `${top}px`;
    element.style.width = `${width}px`;
    element.style.height = `${height}px`;
  }

  // Storage can be unavailable; tours then just don't continue across pages.
  function readState() {
    try {
      return JSON.parse(sessionStorage.getItem(STATE_KEY));
    } catch (error) {
      return null;
    }
  }

  function writeState() {
    try {
      sessionStorage.setItem(STATE_KEY, JSON.stringify({ steps, index }));
    } catch (error) {
      // Tour continues on this page only.
    }
  }

  function clearState() {
    try {
      sessionStorage.removeItem(STATE_KEY);
    } catch (error) {
      // Nothing stored.
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", resume);
  } else {
    resume();
  }

  return { start, end };
})();
