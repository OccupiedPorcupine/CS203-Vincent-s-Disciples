async function requireLogin() {
  try {
    return await apiRequest("/api/profile");
  } catch (error) {
    if (error.status === 401) {
      window.location.href = "index.html";
      return null;
    }

    throw error;
  }
}

// Sale dates are plain "YYYY-MM-DD" strings; doing the arithmetic in UTC keeps them from
// shifting a day in the browser's time zone.
function addDays(isoDate, days) {
  const date = new Date(`${isoDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function formatDay(isoDate, options) {
  return new Date(`${isoDate}T00:00:00Z`).toLocaleDateString("en-SG", { ...options, timeZone: "UTC" });
}

const sum = values => values.reduce((total, value) => total + value, 0);

// Charts by canvas ID, so they can be redrawn after new sales are saved.
const charts = {};

// Colours and fonts come from js/chart-theme.js, shared with the forecast page.
function drawChart(canvasId, type, label, labels, values) {
  charts[canvasId]?.destroy();

  charts[canvasId] = new Chart(document.getElementById(canvasId).getContext("2d"), {
    type,

    data: {
      labels,

      datasets: [{
        label,
        data: values,
        ...(type === "bar" ? chickyChart.recordedBar : chickyChart.recordedLine)
      }]
    },

    options: {
      responsive: true,
      maintainAspectRatio: false,
      indexAxis: type === "bar" ? "y" : "x",

      plugins: {
        legend: {
          display: false
        }
      },

      scales: {
        x: {
          beginAtZero: true,
          grid: {
            display: false
          }
        },

        y: {
          beginAtZero: true
        }
      }
    }
  });
}

const WEEKDAYS = ["SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"];

// Whether the stall is closed that day: a weekly rest day or a one-off closed day.
function isClosed(isoDate, { restDays, closedDates }) {
  const weekday = WEEKDAYS[new Date(`${isoDate}T00:00:00Z`).getUTCDay()];
  return restDays.includes(weekday) || closedDates.some(closed => closed.date === isoDate);
}

function showEmpty(message) {
  document.getElementById("demand-note").textContent = message;
  document.getElementById("dish-note").textContent = message;
}

async function loadDashboard() {
  const profile = await requireLogin();

  if (!profile) {
    return;
  }

  if (profile.name) {
    document.getElementById("welcome-heading").textContent = `Welcome, ${profile.name}`;
  }

  const dishes = await apiRequest("/api/dishes");

  if (dishes.length === 0) {
    showEmpty("No dishes yet. Add one to see your sales here.");
    return;
  }

  const [closures, ...salesByDish] = await Promise.all([
    apiRequest("/api/closures"),
    ...dishes.map(dish => apiRequest(`/api/dishes/${dish.id}/sales`))
  ]);

  const latestDate = salesByDish
    .flat()
    .map(sale => sale.date)
    .sort()
    .at(-1);

  if (!latestDate) {
    showEmpty("No sales recorded yet.");
    return;
  }

  // The 7 days ending on the most recent recorded sale, so older datasets still show something.
  const days = Array.from({ length: 7 }, (_, index) => addDays(latestDate, index - 6));
  const firstDay = days[0];

  // A closed day with no sales is a gap, not a day with zero sales. Sales recorded on a closed
  // day mean the stall opened after all, so that day counts as open.
  const closedDays = days.filter(day =>
    isClosed(day, closures) && !salesByDish.flat().some(sale => sale.date === day)
  );

  const dailyTotals = days.map(day =>
    closedDays.includes(day)
      ? null
      : sum(salesByDish.flat().filter(sale => sale.date === day).map(sale => sale.quantitySold))
  );

  const dishTotals = salesByDish.map(sales =>
    sum(sales.filter(sale => sale.date >= firstDay && sale.date <= latestDate).map(sale => sale.quantitySold))
  );

  const range = `${formatDay(firstDay, { day: "numeric", month: "short" })} to ${formatDay(latestDate, { day: "numeric", month: "short" })}`;

  document.getElementById("demand-total").textContent = sum(dailyTotals);
  document.getElementById("demand-note").textContent = closedDays.length
    ? `All dishes · ${range} · closed days left out`
    : `All dishes · ${range}`;
  document.getElementById("dish-note").textContent = range;

  drawChart(
    "demand-chart",
    "line",
    "Portions sold",
    days.map(day => closedDays.includes(day)
      ? `${formatDay(day, { weekday: "short" })} (closed)`
      : formatDay(day, { weekday: "short" })),
    dailyTotals
  );

  drawChart(
    "dish-chart",
    "bar",
    "Portions sold",
    dishes.map(dish => dish.name),
    dishTotals
  );
}

function refreshDashboard() {
  loadDashboard().catch(error => {
    console.error(error);
    showEmpty("Could not load sales. Is the backend running?");
  });
}

refreshDashboard();

// Sent by js/assistant.js when the user confirms sales.
window.addEventListener("chicky:sales-saved", refreshDashboard);
