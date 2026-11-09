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

  // Charts appear as they are; only a redraw after new data eases between values.
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    Chart.defaults.animation = false;
  } else {
    Chart.defaults.animation.duration = 250;
    Chart.defaults.animation.easing = "easeOutCubic";
  }
  // Axes are drawn by the gridlines and labels alone; no heavy axis line.
  Chart.defaults.scale.border.display = false;
  Chart.defaults.scale.ticks.padding = 8;

  return {
    ink,
    recordedLine: {
      borderColor: ink,
      backgroundColor: ink,
      fill: false,
      borderWidth: 1.75,
      // Straight segments between days: a smoothed curve invents values between the points.
      tension: 0,
      pointBackgroundColor: ink,
      pointBorderColor: "#fff",
      pointBorderWidth: 1,
      pointRadius: 2.5,
      pointHoverRadius: 4.5
    },
    recordedBar: {
      backgroundColor: ink,
      hoverBackgroundColor: token("--teal-strong"),
      borderRadius: 2,
      borderSkipped: false,
      maxBarThickness: 20
    },
    predictedLine: {
      borderColor: teal,
      backgroundColor: teal,
      borderWidth: 2,
      borderDash: [6, 5],
      tension: 0,
      pointRadius: 3
    }
  };
})();
