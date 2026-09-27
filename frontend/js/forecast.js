const predictedDemand = document.getElementById("predicted-demand");
const forecastDateInput = document.getElementById("forecast-date");
const forecastDateLabel = document.getElementById("forecast-date-label");
const forecastButton = document.getElementById("forecast-button");

const toggleButtons = document.querySelectorAll(".toggle-button");

const sevenDayHistory = {
    labels: ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"],
    values: [31, 35, 32, 41, 38, 47, 44]
};

const sameWeekdayHistory = {
    labels: ["4 weeks ago", "3 weeks ago", "2 weeks ago", "Last week"],
    values: [39, 43, 41, 46]
};

const chartContext = document
    .getElementById("history-chart")
    .getContext("2d");

const historyChart = new Chart(chartContext, {
    type: "line",

    data: {
        labels: sevenDayHistory.labels,

        datasets: [{
            label: "Portions sold",
            data: sevenDayHistory.values,
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

function updateHistoryChart(view) {
    const history =
        view === "same-weekday"
            ? sameWeekdayHistory
            : sevenDayHistory;

    historyChart.data.labels = history.labels;
    historyChart.data.datasets[0].data = history.values;

    historyChart.update();
}

toggleButtons.forEach(button => {
    button.addEventListener("click", () => {

        toggleButtons.forEach(button => {
            button.classList.remove("active");
        });

        button.classList.add("active");

        updateHistoryChart(button.dataset.view);
    });
});

forecastButton.addEventListener("click", async () => {
    const dishId = Number(
        document.getElementById("dish-select").value
    );

    const forecastDate = forecastDateInput.value;

    if (!forecastDate) {
        alert("Please select a forecast date.");
        return;
    }

    forecastButton.disabled = true;
    forecastButton.textContent = "Generating...";

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

    } catch (error) {
        console.error(error);

        alert("Unable to generate forecast.");
    } finally {
        forecastButton.disabled = false;
        forecastButton.textContent = "Generate Forecast";
    }
});

async function requestForecast(dishId, forecastDate) {
    return apiRequest("/api/forecast", {
        method: "POST",
        body: JSON.stringify({
            dishId,
            forecastDate
        })
    });
}