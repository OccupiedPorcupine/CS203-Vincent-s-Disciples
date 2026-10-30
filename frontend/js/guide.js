// Guided tours from the assistant: dims everything except one element, explains it, and steps
// through the tour, moving between pages when a step is on another page. Steps come from the
// backend as [{page, url, guideId, message}]; guideId matches a data-guide-id attribute (or a
// temporary data-live-guide-id set by js/assistant.js). Load before js/assistant.js.
//
// Motion: the dimmed shade fades and blurs in, and its spotlight closes in on the first element
// and morphs from each element to the next, even across pages: the next page starts with the
// spotlight exactly where the last one left it. See "Motion" in DESIGN.md.
window.chickyGuide = (() => {
  const PAGE = location.pathname.split("/").pop().replace(/\.html$/, "") || "dashboard";
  const STATE_KEY = "chicky-guide-tour";
  const PADDING = 8;
  const GAP = 12;
  const EDGE = 16;
  const RADIUS = 10;
  // Milliseconds, in step with the --duration-* tokens in css/base.css.
  const OPEN_MS = 650;
  const MOVE_MS = 450;
  const LEAVE_MS = 250;
  const CLOSE_MS = 300;
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

  let steps = [];
  let index = 0;
  let target = null;
  let layer = null;
  let frame = 0;
  // The spotlight as drawn ({top, left, right, bottom}), and the animation moving it.
  let shown = null;
  let tween = null;
  let arrived = false;
  let leaving = false;
  let leaveTimer = 0;

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
    // Pick up exactly where the last page left off: dimmed, with the spotlight where it was, so
    // the first paint matches the last page's final frame. The step itself waits for
    // js/assistant.js, which reads it aloud.
    arrived = true;
    build({ dimmed: true });
    if (state.hole) {
      shown = state.hole;
      drawSpotlight(shown);
    }
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", showCurrent, { once: true });
    } else {
      showCurrent();
    }
  }

  function showCurrent() {
    const step = steps[index];
    writeState();

    if (step.page !== PAGE) {
      leaveFor(step.url);
      return;
    }

    if (!layer) build({ dimmed: false });
    target = findTarget(step.guideId);
    if (target) {
      // Links in the collapsed top-left menu need the menu open to be seen.
      target.closest("details:not([open])")?.setAttribute("open", "");
      target.scrollIntoView({ block: "center", inline: "nearest", behavior: animated() ? "smooth" : "auto" });
    }
    render(target ? step.message : "I couldn’t find that part of the page, so I’ll skip it.");

    // The spotlight morphs from where it is (on this page or the last one). With nothing to morph
    // from, it closes in from the whole screen.
    if (shown) {
      animate(shown, arrived ? OPEN_MS : MOVE_MS);
    } else {
      animate(arrived ? collapse(goalHole()) : viewport(), OPEN_MS);
    }
    arrived = false;
  }

  function move(delta) {
    const nextIndex = index + delta;
    if (nextIndex < 0 || leaving) return;
    if (nextIndex >= steps.length) {
      end();
      return;
    }
    index = nextIndex;
    showCurrent();
  }

  // Fades the bubble, then goes to the page with the next step. The spotlight stays put; the
  // next page carries on from it (see onPageHide). A tour that starts on a page without a step
  // dims the screen and closes in on the middle first.
  function leaveFor(url) {
    let wait = LEAVE_MS;
    if (!layer) {
      build({ dimmed: false });
      animate(viewport(), OPEN_MS, collapse(viewport()));
      wait = OPEN_MS;
    }
    leaving = true;
    layer.classList.add("is-leaving");
    leaveTimer = setTimeout(() => { location.href = url; }, animated() ? wait : 0);
  }

  // Saves where the spotlight is however the page is left: Next, Back, or the user clicking the
  // highlighted link themselves.
  function onPageHide() {
    if (layer && shown) writeState(shown);
  }

  // The page cross-fade (base.css) would show the next page's shade without its blur until the
  // fade ends, then snap. The tour already carries the shade over frame for frame, so it skips it.
  function skipPageFade(event) {
    if (!layer || !event.viewTransition) return;
    event.viewTransition.ready.catch(() => {});
    event.viewTransition.skipTransition();
  }
  window.addEventListener("pageswap", skipPageFade);
  window.addEventListener("pagereveal", skipPageFade);

  function end() {
    clearState();
    window.chickyAssistant?.stopSpeaking();
    clearTimeout(leaveTimer);
    cancelAnimationFrame(frame);
    window.removeEventListener("resize", schedulePosition);
    window.removeEventListener("scroll", schedulePosition, true);
    document.removeEventListener("keydown", onKeydown);
    window.removeEventListener("pagehide", onPageHide);
    document.documentElement.classList.remove("guide-active");

    const closing = layer;
    layer = null;
    target = null;
    shown = null;
    tween = null;
    leaving = false;
    if (!closing) return;
    closing.classList.remove("is-visible");
    closing.classList.add("is-closing");
    setTimeout(() => closing.remove(), animated() ? CLOSE_MS : 0);
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
    const bubble = layer.querySelector(".guide-bubble");
    layer.querySelector(".guide-progress").textContent = steps.length > 1 ? `Step ${index + 1} of ${steps.length}` : "";
    layer.querySelector(".guide-meter").hidden = steps.length < 2;
    layer.querySelector(".guide-meter span").style.width = `${((index + 1) / steps.length) * 100}%`;
    layer.querySelector(".guide-text").textContent = message;
    layer.querySelector(".guide-back").hidden = index === 0;
    layer.querySelector(".guide-next").textContent = index === steps.length - 1 ? "Done" : "Next";

    // Replays the bubble's entrance so each new step reads as new.
    bubble.classList.remove("is-new");
    void bubble.offsetWidth;
    bubble.classList.add("is-new");

    layer.querySelector(".guide-next").focus({ preventScroll: true });
    window.chickyAssistant?.speak(message);
  }

  function build({ dimmed }) {
    layer = document.createElement("div");
    layer.className = "guide-layer";
    layer.innerHTML = `
      <div class="guide-shade"></div>
      <div class="guide-ring" aria-hidden="true"></div>
      <div class="guide-bubble" role="dialog" aria-label="Assistant guide" aria-describedby="guide-text">
        <div class="guide-meter" aria-hidden="true"><span></span></div>
        <p class="guide-progress"></p>
        <p class="guide-text" id="guide-text" aria-live="polite"></p>
        <div class="guide-actions">
          <button class="guide-exit" type="button">Exit</button>
          <button class="guide-back" type="button">Back</button>
          <button class="guide-next" type="button">Next</button>
        </div>
      </div>`;
    if (dimmed) layer.classList.add("is-visible");
    document.body.append(layer);
    document.documentElement.classList.add("guide-active");
    // Starting from the page's own style lets the shade fade and blur in.
    if (!dimmed) {
      void layer.offsetWidth;
      layer.classList.add("is-visible");
    }

    layer.querySelector(".guide-exit").addEventListener("click", end);
    layer.querySelector(".guide-back").addEventListener("click", () => move(-1));
    layer.querySelector(".guide-next").addEventListener("click", () => move(1));
    window.addEventListener("resize", schedulePosition);
    window.addEventListener("scroll", schedulePosition, true);
    window.addEventListener("pagehide", onPageHide);
    document.addEventListener("keydown", onKeydown);
  }

  function onKeydown(event) {
    if (event.key === "Escape") end();
  }

  function animated() {
    return !reducedMotion.matches;
  }

  // Moves the spotlight from `from` to `to`, or to the current target (which may itself be moving
  // while the page scrolls).
  function animate(from, duration, to = null) {
    tween = { from, to, start: performance.now(), duration: animated() ? duration : 0 };
    schedulePosition();
  }

  function schedulePosition() {
    cancelAnimationFrame(frame);
    frame = requestAnimationFrame(tick);
  }

  function tick(now) {
    if (!layer) return;
    const goal = tween?.to || goalHole();
    if (tween) {
      const progress = tween.duration ? Math.min(1, Math.max(0, (now - tween.start) / tween.duration)) : 1;
      shown = mix(tween.from, goal, ease(progress));
      if (progress < 1) {
        frame = requestAnimationFrame(tick);
      } else {
        tween = null;
        if (target && !leaving) pulse();
      }
    } else {
      shown = goal;
    }
    draw(shown, goal);
  }

  // Where the spotlight should be: around the target, or closed in the middle when there isn't one.
  function goalHole() {
    const rect = target?.getBoundingClientRect();
    return rect && rect.width + rect.height > 0
      ? { top: rect.top - PADDING, left: rect.left - PADDING, right: rect.right + PADDING, bottom: rect.bottom + PADDING }
      : collapse(viewport());
  }

  function viewport() {
    return { top: 0, left: 0, right: window.innerWidth, bottom: window.innerHeight };
  }

  function collapse(hole) {
    const x = (hole.left + hole.right) / 2;
    const y = (hole.top + hole.bottom) / 2;
    return { top: y, left: x, right: x, bottom: y };
  }

  function mix(from, to, amount) {
    const between = key => from[key] + (to[key] - from[key]) * amount;
    return { top: between("top"), left: between("left"), right: between("right"), bottom: between("bottom") };
  }

  function ease(t) {
    return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
  }

  function draw(hole, goal) {
    drawSpotlight(hole);
    drawBubble(hole, goal);
  }

  // The shade covers the screen with a rounded hole cut out; clicks pass through the hole only, so
  // the highlighted element stays usable and everything else is blocked. The ring traces the hole
  // and shrinks away with it.
  function drawSpotlight(hole) {
    const width = window.innerWidth;
    const height = window.innerHeight;
    const holeWidth = Math.max(0, hole.right - hole.left);
    const holeHeight = Math.max(0, hole.bottom - hole.top);
    const radius = Math.min(RADIUS, holeWidth / 2, holeHeight / 2);

    layer.querySelector(".guide-shade").style.clipPath =
      `path(evenodd, "M0 0H${width}V${height}H0Z ${roundedRect(hole.left, hole.top, holeWidth, holeHeight, radius)}")`;

    const ring = layer.querySelector(".guide-ring");
    ring.hidden = holeWidth < 4 || holeHeight < 4;
    ring.style.borderRadius = `${radius}px`;
    ring.style.setProperty("--guide-radius", `${radius}px`);
    place(ring, hole.left, hole.top, holeWidth, holeHeight);
  }

  // The bubble follows the spotlight; which side it sits on is decided by where it ends up, so it
  // doesn't flip sides halfway through a move.
  function drawBubble(hole, goal) {
    const width = window.innerWidth;
    const height = window.innerHeight;
    const bubble = layer.querySelector(".guide-bubble");
    const bubbleWidth = Math.min(320, width - 2 * EDGE);
    bubble.style.width = `${bubbleWidth}px`;
    const bubbleHeight = bubble.offsetHeight;
    const lowest = height - bubbleHeight - EDGE;

    let top;
    if (!target) {
      top = (height - bubbleHeight) / 2;
    } else if (goal.bottom + GAP + bubbleHeight <= height - EDGE) {
      top = hole.bottom + GAP;
    } else if (goal.top - GAP - bubbleHeight >= EDGE) {
      top = hole.top - GAP - bubbleHeight;
    } else {
      top = lowest;
    }
    const left = target ? Math.min(Math.max(hole.left, EDGE), width - bubbleWidth - EDGE) : (width - bubbleWidth) / 2;
    bubble.style.top = `${Math.min(Math.max(top, EDGE), lowest)}px`;
    bubble.style.left = `${left}px`;
  }

  function roundedRect(x, y, width, height, r) {
    return `M${x + r} ${y}H${x + width - r}A${r} ${r} 0 0 1 ${x + width} ${y + r}V${y + height - r}`
      + `A${r} ${r} 0 0 1 ${x + width - r} ${y + height}H${x + r}A${r} ${r} 0 0 1 ${x} ${y + height - r}`
      + `V${y + r}A${r} ${r} 0 0 1 ${x + r} ${y}Z`;
  }

  // A ripple from the ring once the spotlight lands.
  function pulse() {
    const ring = layer.querySelector(".guide-ring");
    ring.classList.remove("is-pulsing");
    void ring.offsetWidth;
    ring.classList.add("is-pulsing");
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

  function writeState(hole = null) {
    try {
      sessionStorage.setItem(STATE_KEY, JSON.stringify({ steps, index, hole }));
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

  // Loaded at the end of <body>. Resuming straight away (rather than on DOMContentLoaded) puts
  // the dimmed shade up before the first paint, so arriving from another page doesn't flash.
  if (document.body) {
    resume();
  } else {
    document.addEventListener("DOMContentLoaded", resume);
  }

  return { start, end };
})();
