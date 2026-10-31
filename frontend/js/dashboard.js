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
  document.getElementById("revenue-note").textContent = message;
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

  loadRevenue(firstDay, latestDate).catch(error => {
    console.error(error);
    document.getElementById("revenue-note").textContent = "Revenue could not be loaded.";
  });

  drawChart(
    "dish-chart",
    "bar",
    "Portions sold",
    dishes.map(dish => dish.name),
    dishTotals
  );
}

// ---- Revenue and margin (issue #43) ----

const money = new Intl.NumberFormat("en-SG", { style: "currency", currency: "SGD", currencyDisplay: "narrowSymbol" });
const wholeMoney = new Intl.NumberFormat("en-SG", { style: "currency", currency: "SGD", currencyDisplay: "narrowSymbol", maximumFractionDigits: 0 });

async function loadRevenue(from, to) {
  const revenue = await apiRequest(`/api/revenue?from=${from}&to=${to}`);
  const total = document.getElementById("revenue-total");
  const note = document.getElementById("revenue-note");
  const missing = document.getElementById("revenue-missing");
  const unpriced = revenue.dishes.filter(dish => dish.price === null && dish.portions > 0);

  if (revenue.revenue === null) {
    total.textContent = "--";
    note.textContent = "Set your dish prices to see revenue.";
  } else {
    total.textContent = wholeMoney.format(Number(revenue.revenue));
    const revenueValue = Number(revenue.revenue);
    note.textContent = revenue.grossProfit === null
      ? "Add each dish’s cost to see gross profit."
      : `Gross profit ${wholeMoney.format(Number(revenue.grossProfit))}`
        + (revenueValue > 0 ? ` · ${Math.round(Number(revenue.grossProfit) / revenueValue * 100)}% margin` : "")
        + (revenue.portionsWithoutCost ? " (dishes without a cost left out)" : "");
  }

  missing.replaceChildren();
  missing.hidden = unpriced.length === 0;
  if (unpriced.length) {
    const names = unpriced.map(dish => dish.name);
    const list = names.length > 3 ? `${names.slice(0, 3).join(", ")} and ${names.length - 3} more` : names.join(", ");
    const link = document.createElement("a");
    link.href = "dishes.html";
    link.textContent = "Set prices";
    missing.append(`Price not set: ${list}, so ${revenue.unpricedPortions} portions aren’t counted. `, link);
  }

  const top = document.getElementById("top-dishes");
  top.replaceChildren(...revenue.dishes.filter(dish => dish.portions > 0).slice(0, 5).map(dish => {
    const item = document.createElement("li");
    const name = document.createElement("span");
    name.textContent = dish.name;
    const detail = document.createElement("span");
    if (dish.price === null) detail.textContent = "Price not set";
    else if (dish.grossProfit === null) detail.textContent = `${money.format(Number(dish.revenue))} revenue · cost not set`;
    else detail.textContent = `${money.format(Number(dish.grossProfit))} · ${money.format(Number(dish.marginPerPortion))} a plate`;
    item.append(name, detail);
    return item;
  }));
  document.getElementById("top-dishes-note").textContent = top.children.length ? "" : "No sales in these 7 days.";
}

// ---- Actions needed (issue #53) ----

const actionList = document.getElementById("action-list");
const actionsNote = document.getElementById("actions-note");

async function loadActions() {
  try {
    renderActions(await apiRequest("/api/actions"));
  } catch (error) {
    if (error.status !== 401) actionsNote.textContent = "Actions could not be loaded.";
  }
}

function renderActions(cards) {
  actionList.replaceChildren(...cards.map(actionItem));
  actionsNote.textContent = cards.length ? "" : "Nothing needs your attention right now.";
}

function actionItem(card) {
  const item = document.createElement("li");
  item.className = "action-item";

  const text = document.createElement("div");
  text.className = "action-text";
  const title = document.createElement("strong");
  title.textContent = card.title;
  const detail = document.createElement("span");
  detail.textContent = card.detail;
  text.append(title, detail);

  const buttons = document.createElement("div");
  buttons.className = "action-buttons";
  const act = document.createElement("button");
  act.type = "button";
  act.className = "secondary-button";
  act.textContent = card.actionLabel;
  act.addEventListener("click", () => {
    if (card.recordDate) openRecordDialog(card.recordDate);
    else window.location.href = card.href;
  });
  const dismiss = document.createElement("button");
  dismiss.type = "button";
  dismiss.className = "text-button";
  dismiss.textContent = "Dismiss";
  dismiss.setAttribute("aria-label", `Dismiss: ${card.title}`);
  dismiss.addEventListener("click", async () => {
    dismiss.disabled = true;
    try {
      await apiRequest("/api/actions/dismiss", { method: "POST", body: JSON.stringify({ key: card.key }) });
      item.remove();
      if (!actionList.children.length) actionsNote.textContent = "Nothing needs your attention right now.";
    } catch (error) {
      dismiss.disabled = false;
      actionsNote.textContent = error.message || "That card could not be dismissed.";
    }
  });
  buttons.append(act, dismiss);

  item.append(text, buttons);
  return item;
}

// ---- Recording a missed day ----

const recordDialog = document.getElementById("record-dialog");
const recordForm = document.getElementById("record-form");
const recordList = document.getElementById("record-list");
const recordStatus = document.getElementById("record-status");
const recordSave = document.getElementById("record-save");
let recordDate = null;

async function openRecordDialog(date) {
  recordDate = date;
  document.getElementById("record-heading").textContent =
    `Sales for ${formatDay(date, { weekday: "short", day: "numeric", month: "short" })}`;
  recordStatus.textContent = "";
  recordStatus.className = "status-text";
  recordList.replaceChildren();
  recordSave.disabled = false;
  recordDialog.showModal();

  const dishes = await apiRequest("/api/dishes");
  recordList.replaceChildren(...dishes.map(dish => {
    const row = document.createElement("label");
    row.className = "record-row";
    const name = document.createElement("span");
    name.textContent = dish.name;
    const input = document.createElement("input");
    input.className = "input";
    input.type = "number";
    input.min = "0";
    input.max = "9999";
    input.step = "1";
    input.inputMode = "numeric";
    input.placeholder = "0";
    input.dataset.dishId = dish.id;
    input.setAttribute("aria-label", `Portions of ${dish.name} sold`);
    row.append(name, input);
    return row;
  }));
  recordList.querySelector("input")?.focus();
}

recordForm.addEventListener("submit", async event => {
  event.preventDefault();
  const filled = [...recordList.querySelectorAll("input")].filter(input => input.value !== "");
  if (!filled.length) {
    recordStatus.textContent = "Enter the portions of at least one dish.";
    recordStatus.className = "status-text is-error";
    return;
  }
  if (!filled.every(input => input.reportValidity())) return;

  recordSave.disabled = true;
  try {
    for (const input of filled) {
      await apiRequest(`/api/dishes/${input.dataset.dishId}/sales/${recordDate}`, {
        method: "PUT",
        body: JSON.stringify({ quantitySold: Number(input.value) })
      });
    }
    recordDialog.close();
    refreshDashboard();
  } catch (error) {
    recordStatus.textContent = error.message || "The sales could not be saved.";
    recordStatus.className = "status-text is-error";
    recordSave.disabled = false;
  }
});

document.getElementById("record-cancel").addEventListener("click", () => recordDialog.close());

function refreshDashboard() {
  loadDashboard().catch(error => {
    console.error(error);
    showEmpty("Could not load sales. Is the backend running?");
  });
  loadActions();
}

refreshDashboard();

// Sent by js/assistant.js when the user confirms sales.
window.addEventListener("chicky:sales-saved", refreshDashboard);
window.addEventListener("chicky:prices-saved", refreshDashboard);
