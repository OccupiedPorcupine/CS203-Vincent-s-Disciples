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
  Chart.defaults.plugins.legend.display = false;
  Chart.defaults.plugins.tooltip.backgroundColor = ink;
  Chart.defaults.plugins.tooltip.cornerRadius = 6;
  Chart.defaults.plugins.tooltip.padding = 8;

  return {
    recordedLine: {
      borderColor: ink,
      backgroundColor: ink,
      borderWidth: 2,
      tension: 0.3,
      pointRadius: 3,
      pointHoverRadius: 5
    },
    recordedBar: {
      backgroundColor: ink,
      borderRadius: 4,
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
