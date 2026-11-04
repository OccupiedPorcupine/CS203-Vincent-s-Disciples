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

  // Charts draw in: points and bars arrive one after another, left to right (DESIGN.md, "Motion").
  // Redraws after new data animate between values instead. None of it with reduced motion.
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    Chart.defaults.animation = false;
  } else {
    Chart.defaults.animation.duration = 900;
    Chart.defaults.animation.easing = "easeOutQuart";
    Chart.defaults.animation.delay = context =>
      context.type === "data" && context.mode === "default" ? context.dataIndex * 55 + context.datasetIndex * 150 : 0;
  }

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

  return {
    recordedLine: {
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
    },
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
