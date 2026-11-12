const predictedDemand = document.getElementById("predicted-demand");
const forecastDateInput = document.getElementById("forecast-date");
const forecastDateLabel = document.getElementById("forecast-date-label");
const forecastForm = document.getElementById("forecast-form");
const forecastButton = document.getElementById("forecast-button");
const forecastLabel = forecastButton.querySelector(".button-label");
const historyCaption = document.getElementById("history-caption");
const historyCanvas = document.getElementById("history-chart");
const dishSelect = document.getElementById("dish-select");
const historyNote = document.getElementById("history-note");
const forecastStatus = document.getElementById("forecast-status");
const forecastRange = document.getElementById("forecast-range");
const forecastBasis = document.getElementById("forecast-basis");
const forecastFallback = document.getElementById("forecast-fallback");
const holidayContext = document.getElementById("holiday-context");

// The backend forecasts from today up to this many days ahead (Singapore time).
const MAX_DAYS_AHEAD = 7;

const toggleButtons = document.querySelectorAll(".toggle-button");

// Sales per dish id, fetched once per dish from the backend.
const salesCache = new Map();
let currentView = "seven-days";

// The same recorded-values line chart as the dashboard's demand card (js/chart-theme.js). Only past sales are on it:
// the forecast and its likely range are written in the card above, not plotted.
let historyChart = null;

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
        dates: days.map(day => formatDay(day, { weekday: "long", day: "numeric", month: "short" })),
        values: days.map(day => quantityByDate.get(day) ?? null),
        recorded: days.filter(day => quantityByDate.has(day)).length,
        range: view === "same-weekday"
            ? `Same weekday, last 4 weeks (${formatDay(days[0], { weekday: "long" })}s)`
            : `${formatDay(days[0], { day: "numeric", month: "short" })} to ${formatDay(days.at(-1), { day: "numeric", month: "short" })}`
    };
}

async function updateHistoryChart() {
    const dishId = dishSelect.value;
    const dishName = dishSelect.selectedOptions[0]?.textContent ?? "";
    let history = { labels: [], dates: [], values: [], range: "" };

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

    // The dish stays named under the chart while you move over it, with the unit and the days shown.
    historyCaption.textContent = history.range ? `${dishName} · Portions per day · ${history.range}` : "";
    historyChart = chickyChart.recordedChart(historyCanvas, { labels: history.labels, dates: history.dates, values: history.values, averageOf: "the average of these days" });
    const spoken = history.labels.map((label, index) => `${label} ${history.values[index] ?? "no sales"}`).join(", ");
    historyCanvas.setAttribute("aria-label", history.labels.length
        ? `Line chart of portions of ${dishName} sold on past days. ${spoken}.`
        : "Line chart of portions sold on past days for the chosen dish: nothing to show yet.");
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

const todayInSingapore = new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Singapore" });
forecastDateInput.min = todayInSingapore;
forecastDateInput.max = addDays(todayInSingapore, MAX_DAYS_AHEAD);

loadDishes();

// True while a forecast is being worked out, so Enter in the date box or a double click can't send it twice.
let generating = false;

forecastForm.addEventListener("submit", async (event) => {
    event.preventDefault();

    if (generating) return;

    const dishId = Number(dishSelect.value);
    const forecastDate = forecastDateInput.value;

    if (forecastButton.disabled) return;

    generating = true;
    forecastButton.disabled = true;
    forecastLabel.textContent = "Generating…";
    showStatus("Working out expected demand…");
    showForecastDetails(null);
    window.chickyAssistant?.setForecast(null);

    try {
        const result = await requestForecast(dishId, forecastDate);

        predictedDemand.textContent =
            Math.round(result.predictedDemand);
        showForecastDetails(result);
        // So "why so high?" in the assistant refers to this forecast.
        window.chickyAssistant?.setForecast({ dishId, date: forecastDate });

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

        if (error.status === 401) {
            window.location.href = "index.html";
            return;
        }

        predictedDemand.textContent = "--";
        forecastDateLabel.textContent = "Select a forecast date";

        // 400s explain what to do ("Record a few days of sales…", "Choose a date from today…").
        showStatus(
            error.status === 400 && error.message
                ? error.message
                : "Couldn’t generate a forecast. Try again in a moment.",
            "error"
        );
    } finally {
        forecastLabel.textContent = "Generate forecast";
        generating = false;
        updateForecastButton();
    }
});

// The likely range, how the number was worked out, and whether holidays were used. Neither the model
// nor the baseline uses the weather, so its factor always says "Not used yet".
// Passing null clears them.
function showForecastDetails(result) {
    forecastRange.textContent = !result
        ? ""
        : result.roughRange
            ? `Roughly ${result.low}–${result.high} portions; this narrows as you record more sales`
            : `Usually between ${result.low} and ${result.high} portions`;
    forecastBasis.textContent = result ? `Based on: ${result.basis}` : "";
    forecastFallback.textContent = result?.note ?? "";

    const usedModel = result?.method === "MODEL";
    const factorText = !result
        ? "Included in forecast model"
        : usedModel ? "Included in this forecast" : "Not used in this forecast";
    holidayContext.textContent = factorText;
}

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
