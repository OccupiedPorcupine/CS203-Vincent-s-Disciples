const predictedDemand = document.getElementById("predicted-demand");
const forecastDateInput = document.getElementById("forecast-date");
const forecastDateLabel = document.getElementById("forecast-date-label");
const forecastForm = document.getElementById("forecast-form");
const forecastButton = document.getElementById("forecast-button");
const dishSelect = document.getElementById("dish-select");
const historyNote = document.getElementById("history-note");
const forecastStatus = document.getElementById("forecast-status");

const toggleButtons = document.querySelectorAll(".toggle-button");

// Sales per dish id, fetched once per dish from the backend.
const salesCache = new Map();
let currentView = "seven-days";

const chartContext = document
    .getElementById("history-chart")
    .getContext("2d");

const historyChart = new Chart(chartContext, {
    type: "line",

    data: {
        labels: [],

        datasets: [{
            label: "Portions sold",
            data: [],
            ...chickyChart.recordedLine
        }]
    },

    options: {
        responsive: true,
        maintainAspectRatio: false,

        plugins: {
            legend: {
                display: false
            }
        },

        scales: {
            y: {
                beginAtZero: true
            },

            x: {
                grid: {
                    display: false
                }
            }
        }
    }
});

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

async function getSales(dishId) {
    if (!salesCache.has(dishId)) {
        salesCache.set(dishId, await apiRequest(`/api/dishes/${dishId}/sales`));
    }

    return salesCache.get(dishId);
}

// History leading up to the forecast date, or up to the day after the last recorded sale
// when no date is picked yet. Days without a record show as gaps.
function buildHistory(sales, view) {
    const anchor = forecastDateInput.value || addDays(sales.at(-1).date, 1);
    const quantityByDate = new Map(sales.map(sale => [sale.date, sale.quantitySold]));

    const days = view === "same-weekday"
        ? [28, 21, 14, 7].map(weeksBack => addDays(anchor, -weeksBack))
        : Array.from({ length: 7 }, (_, index) => addDays(anchor, index - 7));

    return {
        labels: days.map(day =>
            view === "same-weekday"
                ? formatDay(day, { day: "numeric", month: "short" })
                : formatDay(day, { weekday: "short", day: "numeric" })
        ),
        values: days.map(day => quantityByDate.get(day) ?? null),
        recorded: days.filter(day => quantityByDate.has(day)).length
    };
}

async function updateHistoryChart() {
    const dishId = dishSelect.value;
    let history = { labels: [], values: [] };

    if (dishId) {
        try {
            const sales = await getSales(dishId);

            if (sales.length === 0) {
                historyNote.textContent = "No sales recorded for this dish yet.";
            } else {
                history = buildHistory(sales, currentView);
                historyNote.textContent = history.recorded === 0
                    ? "No sales recorded in this period."
                    : "";
            }
        } catch (error) {
            console.error(error);
            historyNote.textContent = "Couldn’t load sales history.";
        }
    }

    historyChart.data.labels = history.labels;
    historyChart.data.datasets[0].data = history.values;

    historyChart.update();
}

async function loadDishes() {
    let dishes;

    try {
        dishes = await apiRequest("/api/dishes");
    } catch (error) {
        if (error.status === 401) {
            window.location.href = "index.html";
            return;
        }

        console.error(error);
        dishSelect.replaceChildren(new Option("Couldn’t load dishes"));
        showStatus("Couldn’t load your dishes. Refresh the page to try again.", "error");
        return;
    }

    if (dishes.length === 0) {
        dishSelect.replaceChildren(new Option("No dishes yet"));
        historyNote.textContent = "Add a dish and its sales to see history here.";
        return;
    }

    dishSelect.replaceChildren(...dishes.map(dish => new Option(dish.name, dish.id)));
    dishSelect.disabled = false;
    updateForecastButton();

    await updateHistoryChart();
}

toggleButtons.forEach(button => {
    button.addEventListener("click", () => {

        toggleButtons.forEach(other => {
            other.setAttribute("aria-pressed", String(other === button));
        });

        currentView = button.dataset.view;
        updateHistoryChart();
    });
});

// Like the upload button on the data page, the forecast button stays disabled until the form
// can run: a dish is loaded and a date is chosen.
function updateForecastButton() {
    forecastButton.disabled = dishSelect.disabled || !forecastDateInput.value;
}

dishSelect.addEventListener("change", updateHistoryChart);
forecastDateInput.addEventListener("input", updateForecastButton);
forecastDateInput.addEventListener("change", updateHistoryChart);

loadDishes();

forecastForm.addEventListener("submit", async (event) => {
    event.preventDefault();

    const dishId = Number(dishSelect.value);
    const forecastDate = forecastDateInput.value;

    if (forecastButton.disabled) return;

    forecastButton.disabled = true;
    forecastButton.textContent = "Generating…";
    showStatus("Working out expected demand…");

    try {
        const result = await requestForecast(dishId, forecastDate);

        predictedDemand.textContent =
            Math.round(result.predicted_demand);

        const formattedDate = new Date(
            `${forecastDate}T00:00:00`
        ).toLocaleDateString("en-SG", {
            weekday: "long",
            day: "numeric",
            month: "short"
        });

        forecastDateLabel.textContent = formattedDate;
        showStatus(`Forecast ready for ${formattedDate}.`, "success");

    } catch (error) {
        console.error(error);

        showStatus(
            error.status === 400
                ? "That date is outside the weather forecast. Choose today or tomorrow."
                : "Couldn’t generate a forecast. Try again in a moment.",
            "error"
        );
    } finally {
        forecastButton.textContent = "Generate forecast";
        updateForecastButton();
    }
});

function showStatus(message, type = "") {
    forecastStatus.textContent = message;
    forecastStatus.className = `status-text${type ? ` is-${type}` : ""}`;
}

async function requestForecast(dishId, forecastDate) {
    return apiRequest("/api/forecast", {
        method: "POST",
        body: JSON.stringify({
            dishId,
            forecastDate
        })
    });
}
