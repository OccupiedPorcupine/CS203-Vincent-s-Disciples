async function requireLogin() {
  try {
    await apiRequest("/api/profile");
  } catch (error) {
    if (error.status === 401) {
      window.location.href = "index.html";
      return;
    }

    throw error;
  }
}

requireLogin();

// Sample data until the dashboard reads sales from the backend. Demand matches the
// seven-day history on the forecast page (forecast.js), and revenue assumes S$5.50 a plate.
const PRICE_PER_PORTION = 5.5;

const weekLabels = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const portionsSold = [31, 35, 32, 41, 38, 47, 44];
const revenue = portionsSold.map(portions => portions * PRICE_PER_PORTION);

const sum = values => values.reduce((total, value) => total + value, 0);

document.getElementById("demand-total").textContent = sum(portionsSold);
document.getElementById("revenue-total").textContent =
  `S$${sum(revenue).toLocaleString("en-SG", { minimumFractionDigits: 2 })}`;

// Same chart style as the forecast page's history chart.
function lineChart(canvasId, label, values, formatValue) {
  return new Chart(document.getElementById(canvasId).getContext("2d"), {
    type: "line",

    data: {
      labels: weekLabels,

      datasets: [{
        label,
        data: values,
        borderWidth: 2,
        tension: 0.3,
        pointRadius: 4
      }]
    },

    options: {
      responsive: true,
      maintainAspectRatio: false,

      plugins: {
        legend: {
          display: false
        },

        tooltip: {
          callbacks: {
            label: context => `${label}: ${formatValue(context.parsed.y)}`
          }
        }
      },

      scales: {
        y: {
          beginAtZero: true,
          ticks: {
            callback: value => formatValue(value)
          }
        },

        x: {
          grid: {
            display: false
          }
        }
      }
    }
  });
}

lineChart("demand-chart", "Portions sold", portionsSold, value => value);
lineChart("revenue-chart", "Revenue", revenue, value => `S$${value}`);
