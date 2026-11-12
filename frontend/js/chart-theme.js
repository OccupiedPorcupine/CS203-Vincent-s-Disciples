// Chart.js styling from the design tokens in css/base.css, so every chart matches the app.
// Load after Chart.js and base.css. Recorded data is ink; predictions are teal and dashed
// (see DESIGN.md, "Charts").
const chickyChart = (() => {
  const token = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  const ink = token("--ink");
  const teal = token("--teal");

  Chart.defaults.font.family = token("--font-display");
  Chart.defaults.font.size = 12;
  Chart.defaults.color = token("--muted");
  Chart.defaults.borderColor = token("--divider");
  // Level axis labels; on a phone Chart.js skips some instead of tilting them all.
  Chart.defaults.scale.ticks.maxRotation = 0;
  Chart.defaults.scale.ticks.autoSkipPadding = 8;
  Chart.defaults.plugins.legend.display = false;
  Chart.defaults.plugins.tooltip.backgroundColor = ink;
  Chart.defaults.plugins.tooltip.cornerRadius = 6;
  Chart.defaults.plugins.tooltip.padding = 8;
  Chart.defaults.plugins.tooltip.displayColors = false;

  // Charts appear in their final state: no draw-in when a page loads, and a redraw after new data shows the new
  // values at once. (Hover and focus feedback is drawn by the pointer and keyboard helpers below.)
  Chart.defaults.animation = false;

  // A faint wash of ink under the recorded line, fading out towards the axis.
  const inkWash = context => {
    const { chart } = context;
    if (!chart.chartArea) return "transparent";
    const { top, bottom } = chart.chartArea;
    const gradient = chart.ctx.createLinearGradient(0, top, 0, bottom);
    gradient.addColorStop(0, `${ink}29`);
    gradient.addColorStop(1, `${ink}00`);
    return gradient;
  };


  // Keyboard access to a chart's hover details. Focus the chart, then the arrow keys (or Home and End) move
  // through its points, showing the same tooltip the pointer does, and Escape clears it. `describe(index)`
  // returns the sentence a screen reader announces for a point, and `onShow(index)` (optional) runs after each point is shown, e.g. to scroll it into view. Safe to call again after the chart is redrawn.
  function keyboard(chart, describe, { start = "last", onShow } = {}) {
    const canvas = chart.canvas;
    canvas.chickyKeyboard?.dispose();

    const live = document.createElement("span");
    live.className = "visually-hidden";
    live.setAttribute("role", "status");
    live.setAttribute("aria-live", "polite");
    canvas.after(live);
    canvas.tabIndex = 0;

    let current = null;
    const points = () => chart.data.datasets[0].data.map((value, index) => [value, index])
      .filter(([value]) => value !== null && value !== undefined).map(([, index]) => index);

    function show(index) {
      current = index;
      const active = [{ datasetIndex: 0, index }];
      const { x, y } = chart.getDatasetMeta(0).data[index].getProps(["x", "y"], true);
      chart.setActiveElements(active);
      chart.tooltip.setActiveElements(active, { x, y });
      chart.update();
      live.textContent = describe(index);
      onShow?.(index);
    }
    function clear() {
      current = null;
      chart.setActiveElements([]);
      chart.tooltip.setActiveElements([], { x: 0, y: 0 });
      chart.update();
      live.textContent = "";
    }
    const onKey = event => {
      const usable = points();
      if (!usable.length) return;
      const position = usable.indexOf(current);
      const keys = {
        ArrowRight: usable[Math.min(usable.length - 1, position + 1)],
        ArrowDown: usable[Math.min(usable.length - 1, position + 1)],
        ArrowLeft: usable[Math.max(0, position < 0 ? usable.length - 1 : position - 1)],
        ArrowUp: usable[Math.max(0, position < 0 ? usable.length - 1 : position - 1)],
        Home: usable[0],
        End: usable[usable.length - 1]
      };
      if (event.key === "Escape") { clear(); return; }
      if (!(event.key in keys)) return;
      event.preventDefault();
      show(position < 0 && /^Arrow(Right|Down)$/.test(event.key) ? usable[0] : keys[event.key]);
    };
    const onFocus = () => {
      // Only for keyboard focus: a click on the chart should leave its pointer tooltip alone.
      const usable = points();
      if (canvas.matches(":focus-visible") && usable.length) show(start === "first" ? usable[0] : usable[usable.length - 1]);
    };
    canvas.addEventListener("keydown", onKey);
    canvas.addEventListener("focus", onFocus);
    canvas.addEventListener("blur", clear);
    canvas.chickyKeyboard = {
      dispose() {
        canvas.removeEventListener("keydown", onKey);
        canvas.removeEventListener("focus", onFocus);
        canvas.removeEventListener("blur", clear);
        live.remove();
        delete canvas.chickyKeyboard;
      }
    };
  }


  // Hover and tap for charts whose marks are columns or rows. Chart.js only hit-tests inside the plot area,
  // so hovering a row's label or the figure at the end of its bar found nothing and left the previous
  // highlight stuck. This finds the nearest mark across the whole canvas instead (`axis` "x" for columns,
  // "y" for rows), in the canvas's own coordinates: the pointer's position is read against the canvas's
  // current bounding box at the moment of the event, so scrolling, browser zoom, device-pixel ratio and CSS
  // scaling are all accounted for. Charts using it set `options.events: []`, so Chart.js does not also react.
  // It only changes the active mark and redraws (no rebuild), clears when the pointer leaves, re-checks when
  // the page scrolls or resizes under a still pointer, and a tap elsewhere on a touch screen clears it.
  function pointer(chart, { axis = "x" } = {}) {
    const canvas = chart.canvas;
    canvas.chickyPointer?.dispose();

    let current = null;
    let last = null;       // the latest pointer position over the canvas
    let frame = 0;

    const marks = () => chart.getDatasetMeta(0).data;
    const local = ({ clientX, clientY }) => {
      const box = canvas.getBoundingClientRect();
      return {
        x: (clientX - box.left) * (chart.width / box.width),
        y: (clientY - box.top) * (chart.height / box.height)
      };
    };
    function nearest(point) {
      let best = null;
      let distance = Infinity;
      marks().forEach((mark, index) => {
        if (chart.data.datasets[0].data[index] === null) return;
        const gap = Math.abs(axis === "x" ? point.x - mark.x : point.y - mark.y);
        if (gap < distance) { distance = gap; best = index; }
      });
      return best;
    }
    // `force` redraws even for the same mark, so a tooltip placed beside it follows when the page scrolled.
    function activate(index, force = false) {
      if (index === current && !force) return;
      current = index;
      if (index === null) {
        chart.setActiveElements([]);
        chart.tooltip.setActiveElements([], { x: 0, y: 0 });
      } else {
        const active = [{ datasetIndex: 0, index }];
        const { x, y } = marks()[index].getProps(["x", "y"], true);
        chart.setActiveElements(active);
        chart.tooltip.setActiveElements(active, { x, y });
      }
      chart.update("none");
    }
    // Is the pointer still over the canvas itself (and not over something that moved in front of it)?
    const over = point => {
      const box = canvas.getBoundingClientRect();
      return point.clientX >= box.left && point.clientX <= box.right && point.clientY >= box.top && point.clientY <= box.bottom
        && document.elementFromPoint(point.clientX, point.clientY) === canvas;
    };
    let moved = false;
    function settle() {
      frame = 0;
      const force = moved;
      moved = false;
      if (!last) return;
      activate(over(last) ? nearest(local(last)) : null, force);
    }
    const schedule = () => { if (!frame) frame = requestAnimationFrame(settle); };

    const onMove = event => {
      if (event.pointerType === "touch") return;
      last = { clientX: event.clientX, clientY: event.clientY, pointerType: event.pointerType };
      schedule();
    };
    const onDown = event => {
      last = { clientX: event.clientX, clientY: event.clientY, pointerType: event.pointerType };
      schedule();
    };
    const onLeave = event => {
      if (event.pointerType === "touch") return;
      last = null;
      activate(null);
    };
    const onCancel = () => { last = null; activate(null); };
    // The page or chart scrolled, or the layout changed, under a pointer that did not move.
    const onShift = () => { if (last && last.pointerType !== "touch") { moved = true; schedule(); } };
    // A tap anywhere else puts the details away (there is no pointer leaving on a touch screen).
    const onOutside = event => { if (!canvas.contains(event.target)) { last = null; activate(null); } };

    canvas.addEventListener("pointermove", onMove);
    canvas.addEventListener("pointerdown", onDown);
    canvas.addEventListener("pointerleave", onLeave);
    canvas.addEventListener("pointercancel", onCancel);
    window.addEventListener("scroll", onShift, { capture: true, passive: true });
    window.addEventListener("resize", onShift);
    document.addEventListener("pointerdown", onOutside, true);
    canvas.chickyPointer = {
      dispose() {
        cancelAnimationFrame(frame);
        canvas.removeEventListener("pointermove", onMove);
        canvas.removeEventListener("pointerdown", onDown);
        canvas.removeEventListener("pointerleave", onLeave);
        canvas.removeEventListener("pointercancel", onCancel);
        window.removeEventListener("scroll", onShift, { capture: true });
        window.removeEventListener("resize", onShift);
        document.removeEventListener("pointerdown", onOutside, true);
        delete canvas.chickyPointer;
      }
    };
  }

  const recordedLine = {
    borderColor: ink,
    backgroundColor: inkWash,
    fill: "origin",
    borderWidth: 2,
    tension: 0.35,
    pointBackgroundColor: ink,
    pointBorderColor: "#fff",
    pointBorderWidth: 1.5,
    pointRadius: 3.5,
    pointHoverRadius: 6,
    pointHoverBorderWidth: 2
  };

  // ---- A line chart of recorded values ----
  // The dashboard's demand card and the forecast's history card draw the same chart: recorded values are ink (as on every
  // chart), with a faint teal wash under the line; the brand teal marks the highest value (written above it) and whatever
  // is under the pointer or keyboard focus; the axes are quiet (no axis lines, light gridlines); and the tooltip gives the
  // day in full, the value and how it compares with the average of the days shown. A missing day (null) is a gap.
  // Hover and tap come from pointer(), arrow keys from keyboard(), so neither depends on the plot area or the hover.
  //   labels   the short axis labels; dates  the full label for each point (the tooltip title and screen readers)
  //   values   one number per point, or null for a day with no record; unit  the singular noun ("portion"); averageOf  what the
  //   tooltip compares with ("the daily average")
  const count = new Intl.NumberFormat("en-SG");
  const plural = (value, word) => `${count.format(value)} ${word}${value === 1 ? "" : "s"}`;

  function recordedChart(canvas, { labels, dates, values, unit = "portion", label = "Portions sold", averageOf = "the average" }) {
    canvas.chickyKeyboard?.dispose();
    canvas.chickyPointer?.dispose();
    Chart.getChart(canvas)?.destroy();
    const tealStrong = token("--teal-strong");

    const shown = values.filter(value => value !== null);
    const average = shown.length ? shown.reduce((a, b) => a + b, 0) / shown.length : 0;
    const top = shown.length ? Math.max(...shown) : 0;
    const peak = top > 0 ? values.indexOf(top) : -1;

    // A teal wash that fades towards the axis.
    const wash = context => {
      const { chart } = context;
      if (!chart.chartArea) return "transparent";
      const gradient = chart.ctx.createLinearGradient(0, chart.chartArea.top, 0, chart.chartArea.bottom);
      gradient.addColorStop(0, `${teal}40`);
      gradient.addColorStop(1, `${teal}00`);
      return gradient;
    };

    const chart = new Chart(canvas.getContext("2d"), {
      type: "line",
      data: {
        labels,
        datasets: [{
          label,
          data: values,
          ...recordedLine,
          backgroundColor: wash,
          borderWidth: 2.5,
          pointRadius: context => context.dataIndex === peak ? 6 : 3,
          pointBackgroundColor: context => context.dataIndex === peak ? teal : ink,
          pointBorderColor: "#fff",
          pointBorderWidth: 2,
          pointHoverRadius: 7,
          pointHoverBackgroundColor: teal,
          pointHitRadius: 24
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        layout: { padding: { top: 26, right: 12, bottom: 0, left: 0 } },
        events: [],   // hover and tap come from pointer() (the whole canvas, not only the plot area)
        plugins: {
          legend: { display: false },
          tooltip: {
            titleFont: { weight: "normal" },
            padding: 10,
            callbacks: {
              title: items => dates[items[0].dataIndex],
              label: item => plural(item.parsed.y, unit),
              afterLabel: item => {
                const difference = Math.round(item.parsed.y - average);
                return difference === 0 ? `About ${averageOf}`
                  : `${count.format(Math.abs(difference))} ${difference > 0 ? "above" : "below"} ${averageOf}`;
              }
            }
          }
        },
        scales: {
          x: { grid: { display: false }, border: { display: false }, ticks: { padding: 8 } },
          y: {
            beginAtZero: true,
            border: { display: false },
            grid: { drawTicks: false },
            ticks: { maxTicksLimit: 5, precision: 0, padding: 12 }
          }
        }
      },
      plugins: [{
        id: "recordedGuides",
        // A dotted line down to the axis under the pointer or keyboard focus.
        afterDatasetsDraw(chart) {
          const { ctx, chartArea } = chart;
          const active = chart.tooltip?.getActiveElements?.() ?? [];
          if (active.length) {
            ctx.save();
            ctx.strokeStyle = `${teal}99`;
            ctx.lineWidth = 1;
            ctx.setLineDash([3, 4]);
            ctx.beginPath();
            ctx.moveTo(active[0].element.x, chartArea.top);
            ctx.lineTo(active[0].element.x, chartArea.bottom);
            ctx.stroke();
            ctx.restore();
          }
          // The focal point: the highest value, written above its marker.
          if (peak >= 0 && !active.some(item => item.index === peak)) {
            const point = chart.getDatasetMeta(0).data[peak];
            ctx.save();
            ctx.fillStyle = tealStrong;
            ctx.font = `${Chart.defaults.font.size + 2}px ${Chart.defaults.font.family}`;
            ctx.textAlign = "center";
            ctx.textBaseline = "bottom";
            ctx.fillText(count.format(values[peak]), point.x, point.y - 12);
            ctx.restore();
          }
        }
      }]
    });

    pointer(chart, { axis: "x" });
    keyboard(chart, index => `${dates[index]}: ${plural(values[index], unit)}`);
    return chart;
  }

  return {
    recordedChart,
    keyboard,
    pointer,
    recordedLine,
    recordedBar: {
      backgroundColor: ink,
      hoverBackgroundColor: token("--teal-strong"),
      borderRadius: 6,
      borderSkipped: false,
      maxBarThickness: 32
    },
    predictedLine: {
      borderColor: teal,
      backgroundColor: teal,
      borderWidth: 2,
      borderDash: [6, 5],
      tension: 0.3,
      pointRadius: 3
    }
  };
})();
